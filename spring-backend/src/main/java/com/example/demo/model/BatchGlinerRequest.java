package com.example.demo.model;

import java.util.List;

public class BatchGlinerRequest {
    private List<SingleChunkItem> chunks;
    private List<String> entity_labels;
    private List<String> relation_labels;
    private Double threshold;
    private Double relation_threshold;

    public BatchGlinerRequest() {}

    public BatchGlinerRequest(List<SingleChunkItem> chunks, List<String> entity_labels, List<String> relation_labels, Double threshold, Double relation_threshold) {
        this.chunks = chunks;
        this.entity_labels = entity_labels;
        this.relation_labels = relation_labels;
        this.threshold = threshold;
        this.relation_threshold = relation_threshold;
    }

    public List<SingleChunkItem> getChunks() {
        return chunks;
    }

    public void setChunks(List<SingleChunkItem> chunks) {
        this.chunks = chunks;
    }

    public List<String> getEntity_labels() {
        return entity_labels;
    }

    public void setEntity_labels(List<String> entity_labels) {
        this.entity_labels = entity_labels;
    }

    public List<String> getRelation_labels() {
        return relation_labels;
    }

    public void setRelation_labels(List<String> relation_labels) {
        this.relation_labels = relation_labels;
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double threshold) {
        this.threshold = threshold;
    }

    public Double getRelation_threshold() {
        return relation_threshold;
    }

    public void setRelation_threshold(Double relation_threshold) {
        this.relation_threshold = relation_threshold;
    }

    public static class SingleChunkItem {
        private int chunk_index;
        private String text;

        public SingleChunkItem() {}

        public SingleChunkItem(int chunk_index, String text) {
            this.chunk_index = chunk_index;
            this.text = text;
        }

        public int getChunk_index() {
            return chunk_index;
        }

        public void setChunk_index(int chunk_index) {
            this.chunk_index = chunk_index;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}

