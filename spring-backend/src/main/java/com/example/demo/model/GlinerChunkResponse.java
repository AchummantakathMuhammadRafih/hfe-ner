package com.example.demo.model;

import java.util.List;

public class GlinerChunkResponse {
    private int chunk_index;
    private List<Node> nodes;
    private List<Edge> edges;

    public GlinerChunkResponse() {}

    public int getChunk_index() { return chunk_index; }
    public void setChunk_index(int chunk_index) { this.chunk_index = chunk_index; }

    public List<Node> getNodes() { return nodes; }
    public void setNodes(List<Node> nodes) { this.nodes = nodes; }

    public List<Edge> getEdges() { return edges; }
    public void setEdges(List<Edge> edges) { this.edges = edges; }
}

