package com.example.demo.service;

import com.example.demo.model.BatchFileItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Service
public class BatchFileScannerService {

    private static final Logger log = LoggerFactory.getLogger(BatchFileScannerService.class);
    public static final String TARGET_FILENAME = "docling_chunks.json";

    public List<BatchFileItem> scanDirectory(String inputRootStr, String outputRootStr) throws IOException {
        Path inputRoot = Paths.get(inputRootStr).toAbsolutePath().normalize();
        Path outputRoot = Paths.get(outputRootStr).toAbsolutePath().normalize();

        if (!Files.exists(inputRoot) || !Files.isDirectory(inputRoot)) {
            throw new IllegalArgumentException("Input root directory does not exist or is not a directory: " + inputRootStr);
        }

        List<BatchFileItem> items = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(inputRoot)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equalsIgnoreCase(TARGET_FILENAME))
                .forEach(path -> {
                    Path relPath = inputRoot.relativize(path);
                    Path targetPath = outputRoot.resolve(relPath);

                    long size = 0;
                    try {
                        size = Files.size(path);
                    } catch (IOException ignored) {}

                    BatchFileItem item = new BatchFileItem(
                            relPath.toString().replace('\\', '/'),
                            path.toString(),
                            targetPath.toString(),
                            size
                    );
                    items.add(item);
                });
        }

        log.info("Discovered {} matching '{}' files in {}", items.size(), TARGET_FILENAME, inputRoot);
        return items;
    }
}

