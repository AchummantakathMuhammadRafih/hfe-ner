package com.example.demo.service;

import com.example.demo.model.BatchFileEntity;
import com.example.demo.model.BatchJobRequest;
import com.example.demo.model.GlinerChunkResponse;
import com.example.demo.repository.BatchFileRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class PersistentBatchQueueService {

    private static final Logger log = LoggerFactory.getLogger(PersistentBatchQueueService.class);

    private final BatchFileRepository repository;
    private final GlinerClientService glinerClient;
    private final BatchFileScannerService scannerService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${gliner.default.entity-labels}")
    private List<String> defaultEntityLabels;

    @Value("${gliner.default.relation-labels}")
    private List<String> defaultRelationLabels;

    @Value("${gliner.default.threshold:0.4}")
    private Double defaultThreshold;

    @Value("${gliner.default.relation-threshold:0.28}")
    private Double defaultRelationThreshold;

    @Value("${gliner.chunk.batch-size:8}")
    private int chunkBatchSize;

    // Worker pool for up to 10 files at a time
    private ExecutorService workerPool = Executors.newFixedThreadPool(10);
    private final ExecutorService dispatcherExecutor = Executors.newSingleThreadExecutor();

    private final AtomicBoolean isQueueRunning = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    // Active file ID tracker (max concurrency slots)
    private final Set<Long> activeProcessingFileIds = ConcurrentHashMap.newKeySet();
    private int configuredConcurrency = 10;
    private boolean configuredOverwrite = false;

    public PersistentBatchQueueService(BatchFileRepository repository, GlinerClientService glinerClient, BatchFileScannerService scannerService) {
        this.repository = repository;
        this.glinerClient = glinerClient;
        this.scannerService = scannerService;
    }

    @PostConstruct
    public void init() {
        // Safe crash recovery: reset any file that was left in PROCESSING before shutdown
        int recovered = repository.resetProcessingToPending();
        if (recovered > 0) {
            log.info("Crash recovery: reset {} files from PROCESSING back to PENDING state in SQLite.", recovered);
        }
    }

    public synchronized void clearDatabase() {
        stopQueue();
        activeProcessingFileIds.clear();
        repository.deleteAll();
        log.info("Cleared all entries from SQLite batch database.");
    }

    public synchronized Map<String, Object> scanAndSyncDirectory(String inputRoot, String outputRoot) throws Exception {
        var scannedItems = scannerService.scanDirectory(inputRoot, outputRoot);
        int added = 0;
        int existing = 0;

        for (var item : scannedItems) {
            Optional<BatchFileEntity> opt = repository.findByRelativePath(item.getRelativePath());
            if (opt.isEmpty()) {
                BatchFileEntity entity = new BatchFileEntity(
                        item.getRelativePath(),
                        item.getInputFullPath(),
                        item.getOutputFullPath(),
                        item.getFileSize()
                );
                repository.save(entity);
                added++;
            } else {
                existing++;
            }
        }

        log.info("Synced directory: {} new files added to SQLite, {} already present.", added, existing);
        return Map.of("totalFound", scannedItems.size(), "newAdded", added, "alreadyInDb", existing);
    }

    public synchronized void startQueue(BatchJobRequest req) {
        this.configuredConcurrency = (req.getConcurrency() > 0 && req.getConcurrency() <= 50) ? req.getConcurrency() : 10;
        this.configuredOverwrite = req.isOverwriteExisting();

        if (req.getEntityLabels() != null && !req.getEntityLabels().isEmpty()) defaultEntityLabels = req.getEntityLabels();
        if (req.getRelationLabels() != null && !req.getRelationLabels().isEmpty()) defaultRelationLabels = req.getRelationLabels();
        if (req.getThreshold() != null) defaultThreshold = req.getThreshold();
        if (req.getRelationThreshold() != null) defaultRelationThreshold = req.getRelationThreshold();

        stopRequested.set(false);

        if (!isQueueRunning.get()) {
            isQueueRunning.set(true);
            dispatcherExecutor.submit(this::dispatchQueueLoop);
            log.info("Batch Queue started with concurrency = {}", configuredConcurrency);
        }
    }

    public void stopQueue() {
        log.info("Stopping Batch Queue dispatcher...");
        stopRequested.set(true);
        isQueueRunning.set(false);
    }

    public boolean processSingleFileById(Long id) {
        Optional<BatchFileEntity> opt = repository.findById(id);
        if (opt.isEmpty()) return false;

        BatchFileEntity entity = opt.get();
        if (activeProcessingFileIds.contains(id)) {
            return false; // Already running
        }

        activeProcessingFileIds.add(id);
        entity.setStatus("PROCESSING");
        repository.save(entity);

        workerPool.submit(() -> {
            try {
                executeFileProcessing(entity);
            } finally {
                activeProcessingFileIds.remove(id);
            }
        });
        return true;
    }

    public boolean resetFileStatus(Long id) {
        Optional<BatchFileEntity> opt = repository.findById(id);
        if (opt.isEmpty()) return false;
        BatchFileEntity entity = opt.get();
        entity.setStatus("PENDING");
        entity.setErrorMessage(null);
        entity.setFailedChunkIndex(null);
        entity.setProcessedChunks(0);
        repository.save(entity);
        return true;
    }

    public Map<String, Object> getQueueStatus() {
        long total = repository.count();
        long completed = repository.countByStatus("COMPLETED");
        long failed = repository.countByStatus("FAILED");
        long skipped = repository.countByStatus("SKIPPED");
        long pending = repository.countByStatus("PENDING");
        long processing = repository.countByStatus("PROCESSING");

        return Map.of(
                "running", isQueueRunning.get(),
                "activeWorkers", activeProcessingFileIds.size(),
                "concurrency", configuredConcurrency,
                "total", total,
                "completed", completed,
                "failed", failed,
                "skipped", skipped,
                "pending", pending,
                "processing", processing,
                "activeFileIds", new ArrayList<>(activeProcessingFileIds)
        );
    }

    private void dispatchQueueLoop() {
        log.info("Batch Queue dispatcher loop running...");
        while (isQueueRunning.get() && !stopRequested.get()) {
            try {
                int availableSlots = configuredConcurrency - activeProcessingFileIds.size();

                if (availableSlots > 0) {
                    List<BatchFileEntity> pendingFiles = repository.findByStatusOrderByIdAsc("PENDING", PageRequest.of(0, availableSlots));

                    if (pendingFiles.isEmpty()) {
                        // Check if any files are currently still processing
                        if (activeProcessingFileIds.isEmpty()) {
                            log.info("Queue empty and all workers idle. Batch complete.");
                            isQueueRunning.set(false);
                            break;
                        }
                    } else {
                        for (BatchFileEntity fileEntity : pendingFiles) {
                            if (activeProcessingFileIds.size() >= configuredConcurrency) break;
                            if (activeProcessingFileIds.add(fileEntity.getId())) {
                                fileEntity.setStatus("PROCESSING");
                                repository.save(fileEntity);

                                workerPool.submit(() -> {
                                    try {
                                        executeFileProcessing(fileEntity);
                                    } finally {
                                        activeProcessingFileIds.remove(fileEntity.getId());
                                    }
                                });
                            }
                        }
                    }
                }

                Thread.sleep(500); // Polling throttle
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                log.error("Error in queue dispatcher loop: {}", e.getMessage(), e);
            }
        }
        isQueueRunning.set(false);
        log.info("Batch Queue dispatcher exited.");
    }

    private void executeFileProcessing(BatchFileEntity entity) {
        Path inputPath = Paths.get(entity.getInputFullPath());
        Path outputPath = Paths.get(entity.getOutputFullPath());
        long startMs = System.currentTimeMillis();

        log.info("[File #{}] START processing: {}", entity.getId(), entity.getRelativePath());

        // Skip if output file already exists and overwrite is false
        if (Files.exists(outputPath) && !configuredOverwrite) {
            entity.setStatus("SKIPPED");
            entity.setDurationMs(System.currentTimeMillis() - startMs);
            repository.save(entity);
            log.info("[File #{}] SKIPPED: {} already exists", entity.getId(), entity.getRelativePath());
            return;
        }

        Path tempFile = null;
        try {
            // Replicate directory structure
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }

            JsonNode rootNode;
            try (InputStream in = Files.newInputStream(inputPath)) {
                rootNode = objectMapper.readTree(in);
            }

            if (!rootNode.isObject() || !rootNode.has("chunks") || !rootNode.get("chunks").isArray()) {
                throw new IllegalArgumentException("Invalid format: missing 'chunks' array");
            }

            ObjectNode outputRoot = (ObjectNode) rootNode;
            ArrayNode chunksArray = (ArrayNode) outputRoot.get("chunks");
            int totalChunks = chunksArray.size();
            entity.setTotalChunks(totalChunks);

            int totalNodes = 0;
            int totalEdges = 0;

            int effectiveBatchSize = chunkBatchSize > 0 ? chunkBatchSize : 8;

            for (int i = 0; i < totalChunks; i += effectiveBatchSize) {
                if (stopRequested.get() && isQueueRunning.get()) {
                    entity.setStatus("PENDING");
                    repository.save(entity);
                    return;
                }

                int batchEnd = Math.min(i + effectiveBatchSize, totalChunks);
                List<com.example.demo.model.BatchGlinerRequest.SingleChunkItem> batchItems = new ArrayList<>();
                List<ObjectNode> batchChunkNodes = new ArrayList<>();

                for (int c = i; c < batchEnd; c++) {
                    JsonNode chunkItem = chunksArray.get(c);
                    if (!chunkItem.isObject()) continue;

                    ObjectNode chunkObj = (ObjectNode) chunkItem;
                    int chunkIndex = chunkObj.has("chunk_index") ? chunkObj.get("chunk_index").asInt() : c;
                    String text = chunkObj.has("raw_text") && !chunkObj.get("raw_text").asText().isBlank()
                            ? chunkObj.get("raw_text").asText()
                            : (chunkObj.has("text") ? chunkObj.get("text").asText() : "");

                    batchItems.add(new com.example.demo.model.BatchGlinerRequest.SingleChunkItem(chunkIndex, text));
                    batchChunkNodes.add(chunkObj);
                }

                if (batchItems.isEmpty()) continue;

                // Send bulk request across SSH / HTTP to GPU
                List<GlinerChunkResponse> batchResponses;
                try {
                    batchResponses = glinerClient.extractBatch(
                            batchItems,
                            defaultEntityLabels,
                            defaultRelationLabels,
                            defaultThreshold,
                            defaultRelationThreshold
                    );
                    if (batchResponses == null || batchResponses.size() != batchItems.size()) {
                        throw new RuntimeException("Expected " + batchItems.size() + " batch responses, but received "
                                + (batchResponses != null ? batchResponses.size() : 0));
                    }
                } catch (Exception ex) {
                    throw new RuntimeException("Failed at chunk batch [" + i + " - " + (batchEnd - 1) + "]: " + ex.getMessage(), ex);
                }

                for (int b = 0; b < batchResponses.size(); b++) {
                    GlinerChunkResponse kgRes = batchResponses.get(b);
                    ObjectNode chunkObj = batchChunkNodes.get(b);

                    int nodesCount = kgRes.getNodes() != null ? kgRes.getNodes().size() : 0;
                    int edgesCount = kgRes.getEdges() != null ? kgRes.getEdges().size() : 0;
                    totalNodes += nodesCount;
                    totalEdges += edgesCount;

                    chunkObj.set("nodes", objectMapper.valueToTree(kgRes.getNodes()));
                    chunkObj.set("edges", objectMapper.valueToTree(kgRes.getEdges()));
                }

                entity.setProcessedChunks(batchEnd);
                repository.save(entity);
            }

            // Write to .tmp then rename atomically
            tempFile = outputPath.resolveSibling(outputPath.getFileName().toString() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), outputRoot);
            Files.move(tempFile, outputPath, StandardCopyOption.REPLACE_EXISTING);

            entity.setStatus("COMPLETED");
            entity.setExtractedNodes(totalNodes);
            entity.setExtractedEdges(totalEdges);
            entity.setDurationMs(System.currentTimeMillis() - startMs);
            entity.setErrorMessage(null);
            entity.setFailedChunkIndex(null);
            repository.save(entity);

            log.info("[File #{}] COMPLETED: {} in {}ms ({} nodes, {} edges)",
                    entity.getId(), entity.getRelativePath(), entity.getDurationMs(), totalNodes, totalEdges);

        } catch (Exception ex) {
            // STRICT FAILURE ROLLBACK
            if (tempFile != null && Files.exists(tempFile)) {
                try { Files.delete(tempFile); } catch (Exception ignored) {}
            }

            entity.setStatus("FAILED");
            entity.setErrorMessage(ex.getMessage() != null ? ex.getMessage() : ex.toString());
            entity.setDurationMs(System.currentTimeMillis() - startMs);
            repository.save(entity);

            log.error("[File #{}] FAILED: {} - Reason: {}", entity.getId(), entity.getRelativePath(), ex.getMessage());
        }
    }
}

