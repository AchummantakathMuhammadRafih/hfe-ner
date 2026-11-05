package com.example.demo.model;

public class BatchFileItem {
    private String relativePath;
    private String inputFullPath;
    private String outputFullPath;
    private long fileSize;
    private int totalChunks;
    private int processedChunks;
    private String status; // PENDING, PROCESSING, COMPLETED, FAILED, SKIPPED
    private String errorMessage;
    private long durationMs;
    private int extractedNodes;
    private int extractedEdges;

    public BatchFileItem() {}

    public BatchFileItem(String relativePath, String inputFullPath, String outputFullPath, long fileSize) {
        this.relativePath = relativePath;
        this.inputFullPath = inputFullPath;
        this.outputFullPath = outputFullPath;
        this.fileSize = fileSize;
        this.status = "PENDING";
    }

    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

    public String getInputFullPath() { return inputFullPath; }
    public void setInputFullPath(String inputFullPath) { this.inputFullPath = inputFullPath; }

    public String getOutputFullPath() { return outputFullPath; }
    public void setOutputFullPath(String outputFullPath) { this.outputFullPath = outputFullPath; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public int getProcessedChunks() { return processedChunks; }
    public void setProcessedChunks(int processedChunks) { this.processedChunks = processedChunks; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public int getExtractedNodes() { return extractedNodes; }
    public void setExtractedNodes(int extractedNodes) { this.extractedNodes = extractedNodes; }

    public int getExtractedEdges() { return extractedEdges; }
    public void setExtractedEdges(int extractedEdges) { this.extractedEdges = extractedEdges; }
}

