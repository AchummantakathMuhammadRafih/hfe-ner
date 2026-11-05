package com.example.demo.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "batch_files")
public class BatchFileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "relative_path", nullable = false, unique = true)
    private String relativePath;

    @Column(name = "input_full_path", nullable = false, length = 1000)
    private String inputFullPath;

    @Column(name = "output_full_path", nullable = false, length = 1000)
    private String outputFullPath;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "total_chunks")
    private Integer totalChunks = 0;

    @Column(name = "processed_chunks")
    private Integer processedChunks = 0;

    @Column(name = "status", nullable = false, length = 30)
    private String status = "PENDING"; // PENDING, PROCESSING, COMPLETED, FAILED, SKIPPED

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "failed_chunk_index")
    private Integer failedChunkIndex;

    @Column(name = "extracted_nodes")
    private Integer extractedNodes = 0;

    @Column(name = "extracted_edges")
    private Integer extractedEdges = 0;

    @Column(name = "duration_ms")
    private Long durationMs = 0L;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public BatchFileEntity() {}

    public BatchFileEntity(String relativePath, String inputFullPath, String outputFullPath, Long fileSize) {
        this.relativePath = relativePath;
        this.inputFullPath = inputFullPath;
        this.outputFullPath = outputFullPath;
        this.fileSize = fileSize;
        this.status = "PENDING";
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

    public String getInputFullPath() { return inputFullPath; }
    public void setInputFullPath(String inputFullPath) { this.inputFullPath = inputFullPath; }

    public String getOutputFullPath() { return outputFullPath; }
    public void setOutputFullPath(String outputFullPath) { this.outputFullPath = outputFullPath; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public Integer getTotalChunks() { return totalChunks; }
    public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }

    public Integer getProcessedChunks() { return processedChunks; }
    public void setProcessedChunks(Integer processedChunks) { this.processedChunks = processedChunks; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Integer getFailedChunkIndex() { return failedChunkIndex; }
    public void setFailedChunkIndex(Integer failedChunkIndex) { this.failedChunkIndex = failedChunkIndex; }

    public Integer getExtractedNodes() { return extractedNodes; }
    public void setExtractedNodes(Integer extractedNodes) { this.extractedNodes = extractedNodes; }

    public Integer getExtractedEdges() { return extractedEdges; }
    public void setExtractedEdges(Integer extractedEdges) { this.extractedEdges = extractedEdges; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

