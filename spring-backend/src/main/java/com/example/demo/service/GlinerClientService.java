package com.example.demo.service;

import com.example.demo.model.BatchGlinerRequest;
import com.example.demo.model.BatchGlinerResponse;
import com.example.demo.model.GlinerChunkRequest;
import com.example.demo.model.GlinerChunkResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Service
public class GlinerClientService {

    @Value("${gliner.service.url:http://127.0.0.1:8000}")
    private String glinerServiceUrl;

    private final RestTemplate restTemplate;

    public GlinerClientService(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(30))
                .setReadTimeout(Duration.ofSeconds(180))
                .build();
    }

    public boolean isHealthy() {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(glinerServiceUrl + "/health", String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    public GlinerChunkResponse extract(int chunkIndex, String text, List<String> entityLabels, List<String> relationLabels, Double threshold, Double relationThreshold) {
        String url = glinerServiceUrl + "/api/extract";

        GlinerChunkRequest req = new GlinerChunkRequest(
                chunkIndex,
                text,
                entityLabels,
                relationLabels,
                threshold != null ? threshold : 0.35,
                relationThreshold != null ? relationThreshold : 0.28
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<GlinerChunkRequest> entity = new HttpEntity<>(req, headers);

        ResponseEntity<GlinerChunkResponse> resp = restTemplate.postForEntity(url, entity, GlinerChunkResponse.class);
        return resp.getBody();
    }

    public List<GlinerChunkResponse> extractBatch(
            List<BatchGlinerRequest.SingleChunkItem> items,
            List<String> entityLabels,
            List<String> relationLabels,
            Double threshold,
            Double relationThreshold
    ) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        String url = glinerServiceUrl + "/api/extract/batch";

        BatchGlinerRequest req = new BatchGlinerRequest(
                items,
                entityLabels,
                relationLabels,
                threshold != null ? threshold : 0.35,
                relationThreshold != null ? relationThreshold : 0.28
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<BatchGlinerRequest> entity = new HttpEntity<>(req, headers);

        ResponseEntity<BatchGlinerResponse> resp = restTemplate.postForEntity(url, entity, BatchGlinerResponse.class);
        return resp.getBody() != null ? resp.getBody().getResults() : Collections.emptyList();
    }
}

