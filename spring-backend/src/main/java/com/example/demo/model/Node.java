package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Node {
    private String name;
    private String type;
    private Double score;

    public Node() {}

    public Node(String name, String type, Double score) {
        this.name = name;
        this.type = type;
        this.score = score;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
}

