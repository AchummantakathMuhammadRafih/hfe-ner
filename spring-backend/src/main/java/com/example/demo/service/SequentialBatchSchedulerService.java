package com.example.demo.service;

import com.example.demo.model.BatchFileItem;
import com.example.demo.model.BatchJobRequest;
import com.example.demo.model.BatchJobStatus;
import com.example.demo.model.GlinerChunkResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SequentialBatchSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SequentialBatchSchedulerService.class);
    private final BatchFileScannerService scannerService;
    private final GlinerClientService glinerClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // STRICTLY ONE THREAD: Only one file processed at any time
    private final ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private final BatchJobStatus currentStatus = new BatchJobStatus();

    public SequentialBatchSchedulerService(BatchFileScannerService scannerService, GlinerClientService glinerClient) {
        this.scannerService = scannerService;
        this.glinerClient = glinerClient;
    }

    public synchronized BatchJobStatus startBatchJob(BatchJobRequest req) throws Exception {
        if (isRunning.get()) {
            throw new IllegalStateException("A batch job is already actively running!");
        }

        List<BatchFileItem> files = scannerService.scanDirectory(req.getInputRoot(), req.getOutputRoot());
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No 'docling_chunks.json' files found in: " + req.getInputRoot());
        }

        // Initialize status
        currentStatus.setRunning(true);
        currentStatus.setInputRoot(req.getInputRoot());
        currentStatus.setOutputRoot(req.getOutputRoot());
        currentStatus.setTotalFiles(files.size());
        currentStatus.setCompletedFiles(0);
        currentStatus.setFailedFiles(0);
        currentStatus.setSkippedFiles(0);
        currentStatus.setCurrentFileIndex(0);
        currentStatus.setStartedAt(System.currentTimeMillis());
        currentStatus.setFinishedAt(0);
        currentStatus.setFiles(files);

        isRunning.set(true);
        stopRequested.set(false);

        // Submit sequential processing job
        singleThreadExecutor.submit(() -> executeSequentialQueue(req, files));

        return currentStatus;
    }

    public void stopBatchJob() {
        if (isRunning.get()) {
            log.info("Batch stop requested. Job will stop after current file completes.");
            stopRequested.set(true);
        }
    }

    public BatchJobStatus getStatus() {
        return currentStatus;
    }

    private void executeSequentialQueue(BatchJobRequest req, List<BatchFileItem> files) {
        try {
            log.info("==========================================================================");
            log.info("STARTING BATCH JOB: {} files queued for sequential execution (strictly 1 file at a time)", files.size());
            log.info("Input Root: {}", req.getInputRoot());
            log.info("Output Root: {}", req.getOutputRoot());
            log.info("==========================================================================");

            for (int fileIdx = 0; fileIdx < files.size(); fileIdx++) {
                if (stopRequested.get()) {
                    log.info("Batch job stopped by user request. Terminating remaining queue.");
                    break;
                }

                BatchFileItem fileItem = files.get(fileIdx);
                currentStatus.setCurrentFileIndex(fileIdx + 1);
                currentStatus.setCurrentFileRelativePath(fileItem.getRelativePath());

                Path targetFile = Paths.get(fileItem.getOutputFullPath());

                // Skip if already exists and overwrite not requested
                if (Files.exists(targetFile) && !req.isOverwriteExisting()) {
                    log.info("[{}/{}] SKIPPING already existing: {}", fileIdx + 1, files.size(), fileItem.getRelativePath());
                    fileItem.setStatus("SKIPPED");
                    currentStatus.setSkippedFiles(currentStatus.getSkippedFiles() + 1);
                    continue;
                }

                fileItem.setStatus("PROCESSING");
                long startFileTime = System.currentTimeMillis();

                try {
                    processSingleFile(fileItem, req);
                    fileItem.setStatus("COMPLETED");
                    fileItem.setDurationMs(System.currentTimeMillis() - startFileTime);
                    currentStatus.setCompletedFiles(currentStatus.getCompletedFiles() + 1);
                    log.info("[{}/{}] COMPLETED: {} in {}ms", fileIdx + 1, files.size(), fileItem.getRelativePath(), fileItem.getDurationMs());
                } catch (Exception ex) {
                    fileItem.setStatus("FAILED");
                    fileItem.setErrorMessage(ex.getMessage());
                    fileItem.setDurationMs(System.currentTimeMillis() - startFileTime);
                    currentStatus.setFailedFiles(currentStatus.getFailedFiles() + 1);
                    log.error("[{}/{}] FAILED: {} - Error: {}", fileIdx + 1, files.size(), fileItem.getRelativePath(), ex.getMessage(), ex);
                }
            }
        } finally {
            isRunning.set(false);
            currentStatus.setRunning(false);
            currentStatus.setFinishedAt(System.currentTimeMillis());
            long totalDuration = (currentStatus.getFinishedAt() - currentStatus.getStartedAt()) / 1000;
            log.info("==========================================================================");
            log.info("BATCH JOB FINISHED in {}s: {} completed, {} skipped, {} failed",
                    totalDuration, currentStatus.getCompletedFiles(), currentStatus.getSkippedFiles(), currentStatus.getFailedFiles());
            log.info("==========================================================================");
        }
    }

    private void processSingleFile(BatchFileItem fileItem, BatchJobRequest req) throws Exception {
        Path inputPath = Paths.get(fileItem.getInputFullPath());
        Path outputPath = Paths.get(fileItem.getOutputFullPath());

        // Ensure target directory exists (replicate folder structure)
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }

        JsonNode rootNode;
        try (InputStream in = Files.newInputStream(inputPath)) {
            rootNode = objectMapper.readTree(in);
        }

        if (!rootNode.isObject() || !rootNode.has("chunks") || !rootNode.get("chunks").isArray()) {
            throw new IllegalArgumentException("Invalid format: missing 'chunks' array in " + inputPath.getFileName());
        }

        ObjectNode outputRoot = (ObjectNode) rootNode;
        ArrayNode chunksArray = (ArrayNode) outputRoot.get("chunks");
        int totalChunks = chunksArray.size();
        fileItem.setTotalChunks(totalChunks);
        currentStatus.setCurrentFileTotalChunks(totalChunks);

        log.info(">>> Processing File: {} ({} chunks)...", fileItem.getRelativePath(), totalChunks);

        int nodesTotal = 0;
        int edgesTotal = 0;

        for (int c = 0; c < totalChunks; c++) {
            if (stopRequested.get()) break;

            JsonNode chunkItem = chunksArray.get(c);
            if (!chunkItem.isObject()) continue;

            ObjectNode chunkObj = (ObjectNode) chunkItem;
            int chunkIndex = chunkObj.has("chunk_index") ? chunkObj.get("chunk_index").asInt() : c;
            String text = chunkObj.has("text") ? chunkObj.get("text").asText() : "";
            if (text.isBlank() && chunkObj.has("raw_text")) {
                text = chunkObj.get("raw_text").asText();
            }

            fileItem.setProcessedChunks(c + 1);
            currentStatus.setCurrentFileProcessedChunks(c + 1);

            try {
                GlinerChunkResponse kgRes = glinerClient.extract(
                        chunkIndex,
                        text,
                        req.getEntityLabels(),
                        req.getRelationLabels(),
                        req.getThreshold(),
                        req.getRelationThreshold()
                );

                if (kgRes != null) {
                    nodesTotal += (kgRes.getNodes() != null ? kgRes.getNodes().size() : 0);
                    edgesTotal += (kgRes.getEdges() != null ? kgRes.getEdges().size() : 0);
                    chunkObj.set("nodes", objectMapper.valueToTree(kgRes.getNodes()));
                    chunkObj.set("edges", objectMapper.valueToTree(kgRes.getEdges()));
                } else {
                    chunkObj.putArray("nodes");
                    chunkObj.putArray("edges");
                }
            } catch (Exception ex) {
                log.warn("Chunk #{} error in {}: {}", chunkIndex, fileItem.getRelativePath(), ex.getMessage());
                chunkObj.putArray("nodes");
                chunkObj.putArray("edges");
            }
        }

        fileItem.setExtractedNodes(nodesTotal);
        fileItem.setExtractedEdges(edgesTotal);

        // Fail-safe write: write to .tmp then rename to docling_chunks.json
        Path tempFile = outputPath.resolveSibling(outputPath.getFileName().toString() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), outputRoot);
        Files.move(tempFile, outputPath, StandardCopyOption.REPLACE_EXISTING);
    }
}

