package com.example.demo.service;

import com.example.demo.model.GlinerChunkResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DoclingEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(DoclingEnrichmentService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GlinerClientService glinerClient;

    public DoclingEnrichmentService(GlinerClientService glinerClient) {
        this.glinerClient = glinerClient;
    }

    public ObjectNode enrichDoclingJson(
            InputStream inputStream,
            List<String> entityLabels,
            List<String> relationLabels,
            Double threshold,
            Double relationThreshold,
            ProgressCallback callback
    ) throws Exception {
        JsonNode rootNode = objectMapper.readTree(inputStream);
        if (!rootNode.isObject()) {
            throw new IllegalArgumentException("Invalid Docling JSON: root must be an object");
        }

        ObjectNode outputRoot = (ObjectNode) rootNode;
        JsonNode chunksNode = outputRoot.get("chunks");
        if (chunksNode == null || !chunksNode.isArray()) {
            throw new IllegalArgumentException("Invalid Docling JSON: 'chunks' array not found");
        }

        ArrayNode chunksArray = (ArrayNode) chunksNode;
        int totalChunks = chunksArray.size();
        long startTime = System.currentTimeMillis();
        log.info("==========================================================================");
        log.info("Starting Knowledge Graph Enrichment for {} docling chunks", totalChunks);
        log.info("Active Entity Labels ({}): {}", entityLabels.size(), entityLabels);
        log.info("Active Relation Labels ({}): {}", relationLabels.size(), relationLabels);
        log.info("Thresholds -> Entity: {}, Relation: {}", threshold, relationThreshold);
        log.info("==========================================================================");

        int totalNodesExtracted = 0;
        int totalEdgesExtracted = 0;

        for (int i = 0; i < totalChunks; i++) {
            JsonNode chunkItem = chunksArray.get(i);
            if (!chunkItem.isObject()) continue;

            ObjectNode chunkObj = (ObjectNode) chunkItem;
            int chunkIndex = chunkObj.has("chunk_index") ? chunkObj.get("chunk_index").asInt() : i;
            String text = chunkObj.has("raw_text") && !chunkObj.get("raw_text").asText().isBlank()
                    ? chunkObj.get("raw_text").asText()
                    : (chunkObj.has("text") ? chunkObj.get("text").asText() : "");

            int wordCount = text.isBlank() ? 0 : text.split("\\s+").length;
            int currentStep = i + 1;
            double pct = ((double) currentStep / totalChunks) * 100.0;
            log.info(">>> [{}/{} ({:.1f}%)] Processing chunk #{} (approx {} words)...", currentStep, totalChunks, pct, chunkIndex, wordCount);

            long chunkStart = System.currentTimeMillis();
            try {
                GlinerChunkResponse kgRes = glinerClient.extract(
                        chunkIndex,
                        text,
                        entityLabels,
                        relationLabels,
                        threshold,
                        relationThreshold
                );

                if (kgRes != null) {
                    int nodeCount = kgRes.getNodes() != null ? kgRes.getNodes().size() : 0;
                    int edgeCount = kgRes.getEdges() != null ? kgRes.getEdges().size() : 0;
                    totalNodesExtracted += nodeCount;
                    totalEdgesExtracted += edgeCount;

                    chunkObj.set("nodes", objectMapper.valueToTree(kgRes.getNodes()));
                    chunkObj.set("edges", objectMapper.valueToTree(kgRes.getEdges()));

                    long chunkElapsed = System.currentTimeMillis() - chunkStart;
                    log.info("<<< [{}/{} ({:.1f}%)] Completed chunk #{} in {}ms -> Extracted: {} nodes, {} edges",
                            currentStep, totalChunks, pct, chunkIndex, chunkElapsed, nodeCount, edgeCount);
                } else {
                    chunkObj.putArray("nodes");
                    chunkObj.putArray("edges");
                    log.warn("<<< Chunk #{} returned empty response from GLiNER", chunkIndex);
                }
            } catch (Exception ex) {
                log.error("<<< Failed to extract KG for chunk #{}: {}", chunkIndex, ex.getMessage());
                chunkObj.putArray("nodes");
                chunkObj.putArray("edges");
            }

            if (callback != null) {
                callback.onProgress(i + 1, totalChunks);
            }
        }

        long totalElapsedSec = (System.currentTimeMillis() - startTime) / 1000;
        log.info("==========================================================================");
        log.info("Enrichment FINISHED: {} chunks processed in {}s", totalChunks, totalElapsedSec);
        log.info("Total Extracted Across Document: {} nodes, {} edges", totalNodesExtracted, totalEdgesExtracted);
        log.info("==========================================================================");
        return outputRoot;
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int current, int total);
    }
}

