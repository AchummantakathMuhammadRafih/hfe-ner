package com.example.demo.controller;

import com.example.demo.service.DoclingEnrichmentService;
import com.example.demo.service.GlinerClientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class DoclingApiController {

    private final DoclingEnrichmentService enrichmentService;
    private final GlinerClientService glinerClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Value("${gliner.default.entity-labels}")
    private List<String> defaultEntityLabels;

    @org.springframework.beans.factory.annotation.Value("${gliner.default.relation-labels}")
    private List<String> defaultRelationLabels;

    @org.springframework.beans.factory.annotation.Value("${gliner.default.threshold:0.4}")
    private Double defaultThreshold;

    @org.springframework.beans.factory.annotation.Value("${gliner.default.relation-threshold:0.4}")
    private Double defaultRelationThreshold;

    // Cache of processed results by session ID for download
    private final Map<String, byte[]> resultCache = new ConcurrentHashMap<>();

    public DoclingApiController(DoclingEnrichmentService enrichmentService, GlinerClientService glinerClient) {
        this.enrichmentService = enrichmentService;
        this.glinerClient = glinerClient;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("spring_boot", "UP");
        status.put("gliner_python_service", glinerClient.isHealthy() ? "UP" : "DOWN");
        status.put("default_entity_labels", defaultEntityLabels);
        status.put("default_relation_labels", defaultRelationLabels);
        status.put("default_threshold", defaultThreshold);
        status.put("default_relation_threshold", defaultRelationThreshold);
        return ResponseEntity.ok(status);
    }

    @PostMapping(value = "/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> processFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "entityLabels", required = false) List<String> entityLabels,
            @RequestParam(value = "relationLabels", required = false) List<String> relationLabels,
            @RequestParam(value = "threshold", required = false) Double threshold,
            @RequestParam(value = "relationThreshold", required = false) Double relationThreshold
    ) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Uploaded file is empty"));
        }

        if (entityLabels == null || entityLabels.isEmpty()) {
            entityLabels = defaultEntityLabels;
        }

        if (relationLabels == null || relationLabels.isEmpty()) {
            relationLabels = defaultRelationLabels;
        }

        if (threshold == null) {
            threshold = defaultThreshold;
        }

        if (relationThreshold == null) {
            relationThreshold = defaultRelationThreshold;
        }

        try {
            ObjectNode enriched = enrichmentService.enrichDoclingJson(
                    file.getInputStream(),
                    entityLabels,
                    relationLabels,
                    threshold,
                    relationThreshold,
                    null
            );

            byte[] jsonBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(enriched);
            String fileId = UUID.randomUUID().toString();
            resultCache.put(fileId, jsonBytes);

            int totalChunks = enriched.has("chunks") ? enriched.get("chunks").size() : 0;

            Map<String, Object> resp = new HashMap<>();
            resp.put("fileId", fileId);
            resp.put("fileName", "enriched_" + file.getOriginalFilename());
            resp.put("totalChunks", totalChunks);
            resp.put("preview", enriched.get("chunks").get(0));

            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Processing failed: " + e.getMessage()));
        }
    }

    @GetMapping("/download/{fileId}")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable String fileId,
            @RequestParam(value = "name", defaultValue = "docling_chunks_kg.json") String name
    ) {
        byte[] data = resultCache.get(fileId);
        if (data == null) {
            return ResponseEntity.notFound().build();
        }

        ByteArrayResource resource = new ByteArrayResource(data);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(data.length)
                .body(resource);
    }
}

