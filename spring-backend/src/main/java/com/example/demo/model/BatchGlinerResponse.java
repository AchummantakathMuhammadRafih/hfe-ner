package com.example.demo.model;

import java.util.List;

public class BatchGlinerResponse {
    private List<GlinerChunkResponse> results;

    public BatchGlinerResponse() {}

    public BatchGlinerResponse(List<GlinerChunkResponse> results) {
        this.results = results;
    }

    public List<GlinerChunkResponse> getResults() {
        return results;
    }

    public void setResults(List<GlinerChunkResponse> results) {
        this.results = results;
    }
}

