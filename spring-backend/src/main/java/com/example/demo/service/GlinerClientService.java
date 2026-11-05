package com.example.demo.service;

import com.example.demo.model.GlinerChunkRequest;
import com.example.demo.model.GlinerChunkResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class GlinerClientService {

    @Value("${gliner.service.url:http://127.0.0.1:8000}")
    private String glinerServiceUrl;

    private final RestTemplate restTemplate = new RestTemplate();

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
                threshold != null ? threshold : 0.4,
                relationThreshold != null ? relationThreshold : 0.4
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<GlinerChunkRequest> entity = new HttpEntity<>(req, headers);

        ResponseEntity<GlinerChunkResponse> resp = restTemplate.postForEntity(url, entity, GlinerChunkResponse.class);
        return resp.getBody();
    }
}

