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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SequentialBatchSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SequentialBatchSchedulerService.class);
    private final BatchFileScannerService scannerService;
    private final GlinerClientService glinerClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Background master job runner
    private final ExecutorService masterExecutor = Executors.newSingleThreadExecutor();
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

        int concurrency = req.getConcurrency() > 0 ? req.getConcurrency() : 10;
        req.setConcurrency(concurrency);

        // Initialize status
        currentStatus.setRunning(true);
        currentStatus.setInputRoot(req.getInputRoot());
        currentStatus.setOutputRoot(req.getOutputRoot());
        currentStatus.setTotalFiles(files.size());
        currentStatus.setCompletedFiles(0);
        currentStatus.setFailedFiles(0);
        currentStatus.setSkippedFiles(0);
        currentStatus.setCurrentFileIndex(0);
        currentStatus.setConcurrency(concurrency);
        currentStatus.setStartedAt(System.currentTimeMillis());
        currentStatus.setFinishedAt(0);
        currentStatus.setFiles(files);

        isRunning.set(true);
        stopRequested.set(false);

        // Submit concurrent batch processing job
        masterExecutor.submit(() -> executeConcurrentBatch(req, files));

        return currentStatus;
    }

    public void stopBatchJob() {
        if (isRunning.get()) {
            log.info("Batch stop requested. Job will stop after currently running files complete.");
            stopRequested.set(true);
        }
    }

    public BatchJobStatus getStatus() {
        return currentStatus;
    }

    private void executeConcurrentBatch(BatchJobRequest req, List<BatchFileItem> files) {
        int poolSize = req.getConcurrency();
        ExecutorService workerPool = Executors.newFixedThreadPool(poolSize);

        try {
            log.info("==========================================================================");
            log.info("STARTING BATCH JOB: {} files queued with CONCURRENCY = {} simultaneous files", files.size(), poolSize);
            log.info("Input Root: {}", req.getInputRoot());
            log.info("Output Root: {}", req.getOutputRoot());
            log.info("==========================================================================");

            CompletableFuture<?>[] futures = new CompletableFuture<?>[files.size()];

            for (int i = 0; i < files.size(); i++) {
                final int fileIdx = i;
                final BatchFileItem fileItem = files.get(fileIdx);

                futures[i] = CompletableFuture.runAsync(() -> {
                    if (stopRequested.get()) {
                        log.info("Skipping queued file due to stop request: {}", fileItem.getRelativePath());
                        return;
                    }

                    Path targetFile = Paths.get(fileItem.getOutputFullPath());

                    // Skip if already exists and overwrite not requested
                    if (Files.exists(targetFile) && !req.isOverwriteExisting()) {
                        log.info("[File #{}] SKIPPING already existing: {}", fileIdx + 1, fileItem.getRelativePath());
                        fileItem.setStatus("SKIPPED");
                        synchronized (currentStatus) {
                            currentStatus.setSkippedFiles(currentStatus.getSkippedFiles() + 1);
                        }
                        return;
                    }

                    fileItem.setStatus("PROCESSING");
                    synchronized (currentStatus) {
                        currentStatus.setCurrentFileIndex(fileIdx + 1);
                        currentStatus.setCurrentFileRelativePath(fileItem.getRelativePath());
                    }

                    long startFileTime = System.currentTimeMillis();
                    try {
                        processSingleFile(fileItem, req);
                        fileItem.setStatus("COMPLETED");
                        fileItem.setDurationMs(System.currentTimeMillis() - startFileTime);
                        synchronized (currentStatus) {
                            currentStatus.setCompletedFiles(currentStatus.getCompletedFiles() + 1);
                        }
                        log.info("[File #{}] COMPLETED: {} in {}ms", fileIdx + 1, fileItem.getRelativePath(), fileItem.getDurationMs());
                    } catch (Exception ex) {
                        fileItem.setStatus("FAILED");
                        fileItem.setErrorMessage(ex.getMessage());
                        fileItem.setDurationMs(System.currentTimeMillis() - startFileTime);
                        synchronized (currentStatus) {
                            currentStatus.setFailedFiles(currentStatus.getFailedFiles() + 1);
                        }
                        log.error("[File #{}] FAILED: {} - Error: {}", fileIdx + 1, fileItem.getRelativePath(), ex.getMessage(), ex);
                    }
                }, workerPool);
            }

            CompletableFuture.allOf(futures).join();

        } catch (Exception e) {
            log.error("Batch execution encountered an error: {}", e.getMessage(), e);
        } finally {
            workerPool.shutdown();
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

