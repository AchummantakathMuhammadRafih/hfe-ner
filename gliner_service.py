import os
import re
import math
from typing import List, Dict, Any, Optional
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
import uvicorn
from gliner import GLiNER

app = FastAPI(title="GLiNER Knowledge Graph Service", version="1.0.0")

MODEL_NAME = os.getenv("GLINER_MODEL", "knowledgator/gliner-relex-large-v1.0")
print(f"Loading GLiNER Relex model: {MODEL_NAME} ...")
model = GLiNER.from_pretrained(MODEL_NAME)
print("Model loaded successfully.")

# Sliding window parameters
MAX_WINDOW_WORDS = 320   # Word limit per sub-window (well within 384)
OVERLAP_WORDS = 60       # Overlap words between consecutive windows

class GraphChunkRequest(BaseModel):
    chunk_index: int
    text: str
    entity_labels: List[str] = Field(default_factory=lambda: [
        "USE_ENVIRONMENT", "USER", "DEVICE", "HAZARD", "TASK", 
        "HFE_STUDY", "CLINICAL_CONSIDERATION", "REGULATION", "ORGANIZATION"
    ])
    relation_labels: List[str] = Field(default_factory=lambda: [
        "CAUSED_BY", "USED_IN", "PERFORMED_BY", "ASSOCIATED_WITH", 
        "APPLIED_TO", "REQUIRES", "REGULATED_BY"
    ])
    threshold: float = 0.4
    relation_threshold: float = 0.4

class Node(BaseModel):
    name: str
    type: str
    score: float

class Edge(BaseModel):
    source: str
    target: str
    type: str
    weight: float

class GraphChunkResponse(BaseModel):
    chunk_index: int
    nodes: List[Node]
    edges: List[Edge]

def split_into_windows(text: str, max_words: int = MAX_WINDOW_WORDS, overlap: int = OVERLAP_WORDS) -> List[str]:
    """Splits a long chunk text into overlapping sub-windows based on words."""
    words = text.split()
    if len(words) <= max_words:
        return [text]
    
    windows = []
    step = max_words - overlap
    start = 0
    while start < len(words):
        end = min(start + max_words, len(words))
        sub_window = " ".join(words[start:end])
        windows.append(sub_window)
        if end == len(words):
            break
        start += step
    return windows

def normalize_weight(score: float, min_val: float = 1.0, max_val: float = 5.0) -> float:
    """Normalizes a confidence score (0.0 to 1.0) into the 1.0 - 5.0 range rounded to 2 decimal places."""
    clamped = max(0.0, min(1.0, float(score)))
    res = min_val + (clamped * (max_val - min_val))
    return round(res, 2)

@app.post("/api/extract", response_model=GraphChunkResponse)
def extract_graph(req: GraphChunkRequest):
    try:
        if not req.text or not req.text.strip():
            return GraphChunkResponse(chunk_index=req.chunk_index, nodes=[], edges=[])

        # Step 1: Sliding window for 1024-token chunks
        windows = split_into_windows(req.text, max_words=MAX_WINDOW_WORDS, overlap=OVERLAP_WORDS)

        # Step 2: Run inference on each sub-window using GLiNER Knowledge Graph Construction method
        entities_batch, relations_batch = model.inference(
            windows,
            labels=req.entity_labels,
            relations=req.relation_labels,
            threshold=req.threshold,
            relation_threshold=req.relation_threshold
        )

        # Step 3: Deduplicate entities across windows (keep highest score)
        # Key: (clean_name_lower, entity_type)
        node_map: Dict[tuple, Dict[str, Any]] = {}

        for w_idx, win_entities in enumerate(entities_batch):
            for ent in win_entities:
                raw_text = ent.get("text", "").strip()
                ent_type = ent.get("label", "").strip()
                score = round(float(ent.get("score", 0.0)), 3)
                if not raw_text:
                    continue
                
                key = (raw_text.lower(), ent_type.upper())
                if key not in node_map or score > node_map[key]["score"]:
                    node_map[key] = {
                        "name": raw_text,
                        "type": ent_type,
                        "score": score
                    }

        # Step 4: Extract and deduplicate edges across windows
        # Key: (source_lower, target_lower, relation_type)
        edge_map: Dict[tuple, Dict[str, Any]] = {}

        for w_idx in range(len(windows)):
            win_entities = entities_batch[w_idx]
            win_relations = relations_batch[w_idx]

            for rel in win_relations:
                try:
                    head_idx = rel["head"]["entity_idx"]
                    tail_idx = rel["tail"]["entity_idx"]
                    head_ent = win_entities[head_idx]
                    tail_ent = win_entities[tail_idx]

                    source_name = head_ent["text"].strip()
                    target_name = tail_ent["text"].strip()
                    rel_type = rel.get("relation", "").strip()
                    rel_score = float(rel.get("score", 0.7))

                    if not source_name or not target_name or not rel_type:
                        continue

                    # Don't create self-loops unless meaningful
                    if source_name.lower() == target_name.lower():
                        continue

                    edge_key = (source_name.lower(), target_name.lower(), rel_type.upper())
                    
                    if edge_key not in edge_map or rel_score > edge_map[edge_key]["raw_score"]:
                        edge_map[edge_key] = {
                            "source": source_name,
                            "target": target_name,
                            "type": rel_type,
                            "raw_score": rel_score,
                            "weight": normalize_weight(rel_score)
                        }
                except Exception as inner_e:
                    continue

        nodes_list = [
            Node(name=v["name"], type=v["type"], score=v["score"])
            for v in node_map.values()
        ]
        edges_list = [
            Edge(source=v["source"], target=v["target"], type=v["type"], weight=v["weight"])
            for v in edge_map.values()
        ]

        return GraphChunkResponse(
            chunk_index=req.chunk_index,
            nodes=nodes_list,
            edges=edges_list
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/health")
def health():
    return {"status": "UP", "model": MODEL_NAME}

if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8000)

