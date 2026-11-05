package com.example.demo.model;

import java.util.List;

public class BatchJobRequest {
    private String inputRoot;
    private String outputRoot;
    private boolean overwriteExisting = false;
    private int concurrency = 10;
    private List<String> entityLabels;
    private List<String> relationLabels;
    private Double threshold;
    private Double relationThreshold;

    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }

    public String getInputRoot() { return inputRoot; }
    public void setInputRoot(String inputRoot) { this.inputRoot = inputRoot; }

    public String getOutputRoot() { return outputRoot; }
    public void setOutputRoot(String outputRoot) { this.outputRoot = outputRoot; }

    public boolean isOverwriteExisting() { return overwriteExisting; }
    public void setOverwriteExisting(boolean overwriteExisting) { this.overwriteExisting = overwriteExisting; }

    public List<String> getEntityLabels() { return entityLabels; }
    public void setEntityLabels(List<String> entityLabels) { this.entityLabels = entityLabels; }

    public List<String> getRelationLabels() { return relationLabels; }
    public void setRelationLabels(List<String> relationLabels) { this.relationLabels = relationLabels; }

    public Double getThreshold() { return threshold; }
    public void setThreshold(Double threshold) { this.threshold = threshold; }

    public Double getRelationThreshold() { return relationThreshold; }
    public void setRelationThreshold(Double relationThreshold) { this.relationThreshold = relationThreshold; }
}

