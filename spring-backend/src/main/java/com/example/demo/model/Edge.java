package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Edge {
    private String source;
    private String target;
    private String type;
    private Double weight;

    public Edge() {}

    public Edge(String source, String target, String type, Double weight) {
        this.source = source;
        this.target = target;
        this.type = type;
        this.weight = weight;
    }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Double getWeight() { return weight; }
    public void setWeight(Double weight) { this.weight = weight; }
}

