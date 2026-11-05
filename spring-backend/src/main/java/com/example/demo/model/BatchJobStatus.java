package com.example.demo.model;

import java.util.ArrayList;
import java.util.List;

public class BatchJobStatus {
    private boolean running;
    private String inputRoot;
    private String outputRoot;
    private int totalFiles;
    private int completedFiles;
    private int failedFiles;
    private int skippedFiles;
    private int concurrency = 10;
    private int currentFileIndex;
    private String currentFileRelativePath;
    private int currentFileTotalChunks;
    private int currentFileProcessedChunks;
    private long startedAt;
    private long finishedAt;
    private List<BatchFileItem> files = new ArrayList<>();

    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }

    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }

    public String getInputRoot() { return inputRoot; }
    public void setInputRoot(String inputRoot) { this.inputRoot = inputRoot; }

    public String getOutputRoot() { return outputRoot; }
    public void setOutputRoot(String outputRoot) { this.outputRoot = outputRoot; }

    public int getTotalFiles() { return totalFiles; }
    public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }

    public int getCompletedFiles() { return completedFiles; }
    public void setCompletedFiles(int completedFiles) { this.completedFiles = completedFiles; }

    public int getFailedFiles() { return failedFiles; }
    public void setFailedFiles(int failedFiles) { this.failedFiles = failedFiles; }

    public int getSkippedFiles() { return skippedFiles; }
    public void setSkippedFiles(int skippedFiles) { this.skippedFiles = skippedFiles; }

    public int getCurrentFileIndex() { return currentFileIndex; }
    public void setCurrentFileIndex(int currentFileIndex) { this.currentFileIndex = currentFileIndex; }

    public String getCurrentFileRelativePath() { return currentFileRelativePath; }
    public void setCurrentFileRelativePath(String currentFileRelativePath) { this.currentFileRelativePath = currentFileRelativePath; }

    public int getCurrentFileTotalChunks() { return currentFileTotalChunks; }
    public void setCurrentFileTotalChunks(int currentFileTotalChunks) { this.currentFileTotalChunks = currentFileTotalChunks; }

    public int getCurrentFileProcessedChunks() { return currentFileProcessedChunks; }
    public void setCurrentFileProcessedChunks(int currentFileProcessedChunks) { this.currentFileProcessedChunks = currentFileProcessedChunks; }

    public long getStartedAt() { return startedAt; }
    public void setStartedAt(long startedAt) { this.startedAt = startedAt; }

    public long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(long finishedAt) { this.finishedAt = finishedAt; }

    public List<BatchFileItem> getFiles() { return files; }
    public void setFiles(List<BatchFileItem> files) { this.files = files; }
}

