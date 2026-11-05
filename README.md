# Docling Knowledge Graph Construction with GLiNER

Spring Boot (Java 21) & GLiNER application that ingests `docling_chunks.json`, constructs a Knowledge Graph (Entities & Relations) using **`knowledgator/gliner-relex-large-v1.0`**, and exports an enriched JSON file.

---

## 1. Features
- **Zero-Shot Joint NER & Relation Extraction**: Uses the official GLiNER Knowledge Graph pipeline (`knowledgator/gliner-relex-large-v1.0`).
- **Handles 1024-Token Chunks**: Implements a sliding window with overlap (stride) and cross-window deduplication so no text or boundary entities are lost.
- **Normalized Edge Weights**: Maps relation extraction confidence scores to a `1.0 - 5.0` scale.
- **Spring Boot 3.3.5 / Java 21 REST API & UI**:
  - Drag-and-drop web interface at `http://localhost:8080`.
  - File upload endpoint: `POST /api/process`.
  - Enriched JSON download endpoint: `GET /api/download/{fileId}`.
- **Exact Schema Preservation**: Preserves all original Docling metadata and chunk fields, appending:
  ```json
  {
    "nodes": [
      {
        "name": "Combination Product",
        "type": "DEVICE",
        "score": 0.85
      }
    ],
    "edges": [
      {
        "source": "Combination Product",
        "target": "Human Factors Study",
        "type": "REQUIRES",
        "weight": 4.40
      }
    ]
  }
  ```

---

## 2. Quick Start

### One-Click Startup (Windows)
Double-click or run:
```cmd
start_app.bat
```
This starts:
1. The GLiNER Python inference service on `http://127.0.0.1:8000`.
2. The Spring Boot backend & web UI on `http://localhost:8080`.

---

### Manual Startup

#### Step 1: Start Python GLiNER Service
```bash
python gliner_service.py
```
*(Runs on `http://127.0.0.1:8000`)*

#### Step 2: Start Spring Boot (Java 21)
```cmd
set "JAVA_HOME=C:\Users\Muhammad.Rafih\Apps\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd spring-backend
java -jar target\hfe-ner-app-0.0.1-SNAPSHOT.jar
```
*(Runs on `http://localhost:8080`)*

---

## 3. Usage
1. Open your browser at **`http://localhost:8080`**.
2. Drag and drop your [`docling_chunks.json`](file:///c:/Users/Muhammad.Rafih/Code/hfe_ner/docling_chunks.json).
3. (Optional) Expand **Advanced Extraction Schema & Thresholds** to customize entity/relation labels or thresholds.
4. Click **Process & Construct Knowledge Graph**.
5. Once complete, preview the generated nodes & edges for Chunk 0, and click **Download Enriched JSON**.

