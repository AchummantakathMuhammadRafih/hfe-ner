import os
# Set PyTorch CUDA memory allocator to reduce fragmentation
os.environ["PYTORCH_CUDA_ALLOC_CONF"] = "expandable_segments:True"

import re
import math
from typing import List, Dict, Any, Optional
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
import uvicorn
import torch
from gliner import GLiNER

app = FastAPI(title="GLiNER Knowledge Graph Service", version="1.0.0")

# Auto-detect GPU / CUDA device
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
MODEL_NAME = os.getenv("GLINER_MODEL", "knowledgator/gliner-relex-large-v1.0")

print("=" * 60)
print(f"Target Device: {DEVICE.upper()}")
if DEVICE == "cuda":
    print(f"GPU Model: {torch.cuda.get_device_name(0)}")
    print(f"VRAM Available: {torch.cuda.get_device_properties(0).total_memory / 1e9:.2f} GB")
print(f"Loading GLiNER Relex model: {MODEL_NAME} ...")
model = GLiNER.from_pretrained(MODEL_NAME).to(DEVICE)
print(f"Model successfully loaded and moved to {DEVICE.upper()}!")
print("=" * 60)

# Sliding window parameters
# Note: GLiNER tokenizes by punctuation / word boundaries; with 91 prompt labels (~376 tokens),
# keeping MAX_WINDOW_WORDS at 250 guarantees total sequence length stays well under 2048.
MAX_WINDOW_WORDS = 250   # Word limit per sub-window
OVERLAP_WORDS = 50       # Overlap words between consecutive windows
MAX_GPU_SUBWINDOWS_BATCH = 4  # Maximum sub-windows sent to GPU in a single forward pass (prevents OOM)

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
    threshold: float = 0.35
    relation_threshold: float = 0.28

class SingleChunkItem(BaseModel):
    chunk_index: int
    text: str

class BatchGraphChunkRequest(BaseModel):
    chunks: List[SingleChunkItem]
    entity_labels: List[str]
    relation_labels: List[str]
    threshold: float = 0.35
    relation_threshold: float = 0.28

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

class BatchGraphChunkResponse(BaseModel):
    results: List[GraphChunkResponse]

def clean_markdown_artifacts(text: str) -> str:
    """Collapses runs of markdown table separators and punctuation lines that blow up tokenizers."""
    # Collapse repetitive hyphen / dash divider lines: e.g. '---------------------' -> '---'
    text = re.sub(r'-{4,}', '---', text)
    # Collapse repetitive underscore divider lines: '______' -> '___'
    text = re.sub(r'_{4,}', '___', text)
    # Collapse repetitive pipe-dash table sequences: '|---|---|'
    text = re.sub(r'(\|\s*-{3,}\s*){2,}\|?', '|---|---|', text)
    # Collapse repetitive spaces
    text = re.sub(r'[ \t]{3,}', ' ', text)
    return text

def split_into_windows(text: str, max_words: int = MAX_WINDOW_WORDS, overlap: int = OVERLAP_WORDS) -> List[str]:
    """Splits text into overlapping sub-windows using token-aware and word-aware boundaries."""
    text = clean_markdown_artifacts(text)
    words = text.split()
    if not words:
        return []

    # Use GLiNER's token regex: \w+(?:[-_]\w+)*|\S
    # Ensure no window has > 350 actual tokens (well below model limit of 2048)
    windows = []
    step = max(1, max_words - overlap)
    start = 0

    while start < len(words):
        end = min(start + max_words, len(words))
        sub_window = " ".join(words[start:end])

        # If a single sub_window still contains excessive tokens due to unspaced characters:
        tokens = re.findall(r'\w+(?:[-_]\w+)*|\S', sub_window)
        if len(tokens) > 384:
            # Sub-slice by actual tokens to guarantee it never exceeds 2048
            token_step = 250
            for t_idx in range(0, len(tokens), token_step):
                chunk_slice = " ".join(tokens[t_idx : t_idx + token_step])
                windows.append(chunk_slice)
        else:
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

def safe_batch_inference(
    windows: List[str],
    labels: List[str],
    relations: List[str],
    threshold: float,
    relation_threshold: float
):
    """Executes GLiNER relation extraction in safe micro-batches with automatic OOM recovery."""
    all_entities = []
    all_relations = []

    for i in range(0, len(windows), MAX_GPU_SUBWINDOWS_BATCH):
        micro_batch = windows[i : i + MAX_GPU_SUBWINDOWS_BATCH]
        try:
            with torch.no_grad():
                ents, rels = model.inference(
                    texts=micro_batch,
                    labels=labels,
                    relations=relations,
                    threshold=threshold,
                    relation_threshold=relation_threshold
                )
            all_entities.extend(ents)
            all_relations.extend(rels)
        except torch.cuda.OutOfMemoryError:
            # Automatic fallback: clear fragmented cache and process 1 by 1
            if DEVICE == "cuda":
                torch.cuda.empty_cache()
            for single_win in micro_batch:
                with torch.no_grad():
                    ents, rels = model.inference(
                        texts=[single_win],
                        labels=labels,
                        relations=relations,
                        threshold=threshold,
                        relation_threshold=relation_threshold
                    )
                all_entities.extend(ents)
                all_relations.extend(rels)
                if DEVICE == "cuda":
                    torch.cuda.empty_cache()

    if DEVICE == "cuda":
        torch.cuda.empty_cache()

    return all_entities, all_relations

def aggregate_chunk_graph(
    chunk_index: int,
    windows: List[str],
    entities_batch: List[List[Dict[str, Any]]],
    relations_batch: List[List[Dict[str, Any]]]
) -> GraphChunkResponse:
    """Aggregates and deduplicates nodes and edges across sub-windows for a chunk."""
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
            except Exception:
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
        chunk_index=chunk_index,
        nodes=nodes_list,
        edges=edges_list
    )

@app.post("/api/extract", response_model=GraphChunkResponse)
def extract_graph(req: GraphChunkRequest):
    try:
        if not req.text or not req.text.strip():
            return GraphChunkResponse(chunk_index=req.chunk_index, nodes=[], edges=[])

        windows = split_into_windows(req.text, max_words=MAX_WINDOW_WORDS, overlap=OVERLAP_WORDS)
        entities_batch, relations_batch = safe_batch_inference(
            windows=windows,
            labels=req.entity_labels,
            relations=req.relation_labels,
            threshold=req.threshold,
            relation_threshold=req.relation_threshold
        )

        return aggregate_chunk_graph(req.chunk_index, windows, entities_batch, relations_batch)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/api/extract/batch", response_model=BatchGraphChunkResponse)
def extract_graph_batch(req: BatchGraphChunkRequest):
    try:
        if not req.chunks:
            return BatchGraphChunkResponse(results=[])

        # Step 1: Flatten all chunks into sub-windows while mapping window index -> chunk index
        all_windows = []
        # Store for each chunk: (chunk_index, start_win_idx, end_win_idx)
        chunk_window_spans = []

        for item in req.chunks:
            if not item.text or not item.text.strip():
                chunk_window_spans.append((item.chunk_index, 0, 0, []))
                continue
            c_windows = split_into_windows(item.text, max_words=MAX_WINDOW_WORDS, overlap=OVERLAP_WORDS)
            start_idx = len(all_windows)
            all_windows.extend(c_windows)
            end_idx = len(all_windows)
            chunk_window_spans.append((item.chunk_index, start_idx, end_idx, c_windows))

        if not all_windows:
            empty_results = [
                GraphChunkResponse(chunk_index=item.chunk_index, nodes=[], edges=[])
                for item in req.chunks
            ]
            return BatchGraphChunkResponse(results=empty_results)

        # Step 2: Run inference in memory-safe micro-batches on GPU
        all_entities, all_relations = safe_batch_inference(
            windows=all_windows,
            labels=req.entity_labels,
            relations=req.relation_labels,
            threshold=req.threshold,
            relation_threshold=req.relation_threshold
        )

        # Step 3: Reconstruct each chunk's nodes and edges
        results = []
        for chunk_idx, start_idx, end_idx, c_windows in chunk_window_spans:
            if not c_windows:
                results.append(GraphChunkResponse(chunk_index=chunk_idx, nodes=[], edges=[]))
                continue

            c_entities = all_entities[start_idx:end_idx]
            c_relations = all_relations[start_idx:end_idx]

            chunk_resp = aggregate_chunk_graph(chunk_idx, c_windows, c_entities, c_relations)
            results.append(chunk_resp)

        return BatchGraphChunkResponse(results=results)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/health")
def health():
    return {
        "status": "UP",
        "model": MODEL_NAME,
        "device": DEVICE,
        "cuda_available": torch.cuda.is_available(),
        "gpu_name": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None
    }

if __name__ == "__main__":
    # Host on 0.0.0.0 so remote machines / Spring Boot can connect
    port = int(os.getenv("PORT", "8000"))
    uvicorn.run(app, host="0.0.0.0", port=port)

