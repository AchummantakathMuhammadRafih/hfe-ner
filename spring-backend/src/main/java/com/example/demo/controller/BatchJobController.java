package com.example.demo.controller;

import com.example.demo.model.BatchFileItem;
import com.example.demo.model.BatchJobRequest;
import com.example.demo.model.BatchJobStatus;
import com.example.demo.service.BatchFileScannerService;
import com.example.demo.service.SequentialBatchSchedulerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/batch")
@CrossOrigin(origins = "*")
public class BatchJobController {

    private final BatchFileScannerService scannerService;
    private final SequentialBatchSchedulerService schedulerService;

    @Value("${gliner.default.entity-labels}")
    private List<String> defaultEntityLabels;

    @Value("${gliner.default.relation-labels}")
    private List<String> defaultRelationLabels;

    @Value("${gliner.default.threshold:0.4}")
    private Double defaultThreshold;

    @Value("${gliner.default.relation-threshold:0.4}")
    private Double defaultRelationThreshold;

    public BatchJobController(BatchFileScannerService scannerService, SequentialBatchSchedulerService schedulerService) {
        this.scannerService = scannerService;
        this.schedulerService = schedulerService;
    }

    @PostMapping("/scan")
    public ResponseEntity<?> scanFiles(@RequestBody Map<String, String> body) {
        String inputRoot = body.get("inputRoot");
        String outputRoot = body.get("outputRoot");

        if (inputRoot == null || inputRoot.isBlank() || outputRoot == null || outputRoot.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Both inputRoot and outputRoot are required"));
        }

        try {
            List<BatchFileItem> files = scannerService.scanDirectory(inputRoot, outputRoot);
            return ResponseEntity.ok(Map.of(
                    "totalFiles", files.size(),
                    "files", files
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/start")
    public ResponseEntity<?> startBatch(@RequestBody BatchJobRequest req) {
        if (req.getInputRoot() == null || req.getInputRoot().isBlank() ||
            req.getOutputRoot() == null || req.getOutputRoot().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Both inputRoot and outputRoot are required"));
        }

        if (req.getEntityLabels() == null || req.getEntityLabels().isEmpty()) {
            req.setEntityLabels(defaultEntityLabels);
        }
        if (req.getRelationLabels() == null || req.getRelationLabels().isEmpty()) {
            req.setRelationLabels(defaultRelationLabels);
        }
        if (req.getThreshold() == null) {
            req.setThreshold(defaultThreshold);
        }
        if (req.getRelationThreshold() == null) {
            req.setRelationThreshold(defaultRelationThreshold);
        }

        try {
            BatchJobStatus status = schedulerService.startBatchJob(req);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stopBatch() {
        schedulerService.stopBatchJob();
        return ResponseEntity.ok(Map.of("message", "Stop request received. Job will terminate after current file finishes."));
    }

    @GetMapping("/status")
    public ResponseEntity<BatchJobStatus> getBatchStatus() {
        return ResponseEntity.ok(schedulerService.getStatus());
    }
}

