package com.example.demo.controller;

import com.example.demo.model.BatchFileEntity;
import com.example.demo.model.BatchJobRequest;
import com.example.demo.repository.BatchFileRepository;
import com.example.demo.service.PersistentBatchQueueService;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/batch")
@CrossOrigin(origins = "*")
public class BatchJobController {

    private final PersistentBatchQueueService queueService;
    private final BatchFileRepository repository;

    public BatchJobController(PersistentBatchQueueService queueService, BatchFileRepository repository) {
        this.queueService = queueService;
        this.repository = repository;
    }

    @PostMapping("/scan")
    public ResponseEntity<?> scanFiles(@RequestBody Map<String, String> body) {
        String inputRoot = body.get("inputRoot");
        String outputRoot = body.get("outputRoot");

        if (inputRoot == null || inputRoot.isBlank() || outputRoot == null || outputRoot.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Both inputRoot and outputRoot are required"));
        }

        try {
            Map<String, Object> result = queueService.scanAndSyncDirectory(inputRoot, outputRoot);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/files")
    public ResponseEntity<List<BatchFileEntity>> getFiles(
            @RequestParam(value = "status", required = false) String status
    ) {
        if (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) {
            return ResponseEntity.ok(repository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                    .filter(f -> f.getStatus().equalsIgnoreCase(status))
                    .toList());
        }
        return ResponseEntity.ok(repository.findAll(Sort.by(Sort.Direction.ASC, "id")));
    }

    @PostMapping("/start")
    public ResponseEntity<?> startQueue(@RequestBody BatchJobRequest req) {
        try {
            queueService.startQueue(req);
            return ResponseEntity.ok(queueService.getQueueStatus());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stopQueue() {
        queueService.stopQueue();
        return ResponseEntity.ok(Map.of("message", "Queue stopping requested. Active files will complete cleanly."));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(queueService.getQueueStatus());
    }

    @PostMapping("/clear")
    public ResponseEntity<?> clearDatabase() {
        queueService.clearDatabase();
        return ResponseEntity.ok(Map.of("message", "All database entries have been cleared successfully."));
    }

    @PostMapping("/file/{id}/run")
    public ResponseEntity<?> runSingleFile(@PathVariable Long id) {
        boolean queued = queueService.processSingleFileById(id);
        if (queued) {
            return ResponseEntity.ok(Map.of("message", "File #" + id + " dispatched to worker."));
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "File #" + id + " not found or already running."));
        }
    }

    @PostMapping("/file/{id}/reset")
    public ResponseEntity<?> resetFile(@PathVariable Long id) {
        boolean reset = queueService.resetFileStatus(id);
        if (reset) {
            return ResponseEntity.ok(Map.of("message", "File #" + id + " reset to PENDING."));
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "File #" + id + " not found."));
        }
    }
}
