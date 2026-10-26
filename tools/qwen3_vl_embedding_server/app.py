from __future__ import annotations

import base64
import binascii
import importlib.util
import io
import os
import sys
import threading
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, AsyncIterator, Dict, List, Optional, Tuple
from urllib.parse import unquote, urlparse

import torch
import torch.nn.functional as F
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from PIL import Image


DEFAULT_MODEL_PATH = r"F:\LLM_models\hf-model\Qwen_Qwen3-VL-Embedding-2B"
DEFAULT_MODEL_ID = "MedAIBase/Qwen3-VL-Embedding:2b"
DEFAULT_DIMENSION = 2048
MIN_DIMENSION = 64
DEFAULT_INSTRUCTION = "Represent the user's input."
WEIGHT_PATTERNS = (
    "model.safetensors",
    "pytorch_model.bin",
    "*.safetensors",
    "*.bin",
    "model.safetensors.index.json",
    "pytorch_model.bin.index.json",
)


def env_bool(name: str, default: bool) -> bool:
    raw_value = os.getenv(name)
    if raw_value is None:
        return default
    return raw_value.strip().lower() in {"1", "true", "yes", "y", "on"}


def env_int(name: str, default: int) -> int:
    raw_value = os.getenv(name)
    if raw_value is None:
        return default
    try:
        return int(raw_value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc


MODEL_PATH = Path(os.getenv("MODEL_PATH", DEFAULT_MODEL_PATH))
MODEL_ID = os.getenv("MODEL_ID", DEFAULT_MODEL_ID)
MODEL_DTYPE = os.getenv("MODEL_DTYPE", "bfloat16")
MODEL_DEVICE = os.getenv("MODEL_DEVICE", "cuda")
PRELOAD_MODEL = env_bool("PRELOAD_MODEL", True)
SERVER_HOST = os.getenv("SERVER_HOST", "127.0.0.1")
SERVER_PORT = env_int("SERVER_PORT", 11435)


class OpenAIEmbeddingRequest(BaseModel):
    model_config = ConfigDict(extra="allow")

    model: Optional[str] = None
    input: Any
    encoding_format: Optional[str] = "float"
    dimensions: Optional[int] = None
    instruction: Optional[str] = None
    normalize: Optional[bool] = True


class EmbedRequest(BaseModel):
    model_config = ConfigDict(extra="allow")

    model: Optional[str] = None
    items: List[Any] = Field(default_factory=list)
    dimensions: Optional[int] = None
    instruction: Optional[str] = None
    normalize: Optional[bool] = True


class ModelState:
    def __init__(self) -> None:
        self.embedder: Any = None
        self.loaded_at: Optional[float] = None
        self.error: Optional[str] = None
        self.dimension: int = DEFAULT_DIMENSION
        self.device: Optional[str] = None
        self.load_attempted = False


state = ModelState()
load_lock = threading.Lock()
inference_lock = threading.Lock()


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    if PRELOAD_MODEL:
        try:
            load_model()
        except Exception as exc:  # Keep health endpoint reachable when model load fails.
            state.error = f"{type(exc).__name__}: {exc}"
    yield


app = FastAPI(
    title="Qwen3-VL-Embedding FastAPI Server",
    version="0.1.0",
    description="Local multimodal embedding API for Qwen3-VL-Embedding-2B.",
    lifespan=lifespan,
)


def parse_torch_dtype(name: str) -> torch.dtype:
    normalized = (name or "").strip().lower()
    if normalized in {"bf16", "bfloat16"}:
        return torch.bfloat16
    if normalized in {"fp16", "float16", "half"}:
        return torch.float16
    if normalized in {"fp32", "float32", "float"}:
        return torch.float32
    raise ValueError(f"Unsupported MODEL_DTYPE: {name}")


def load_embedder_class(model_path: Path) -> Any:
    script_path = model_path / "scripts" / "qwen3_vl_embedding.py"
    if not script_path.exists():
        raise FileNotFoundError(f"Official embedding script not found: {script_path}")

    module_name = "qwen3_vl_embedding_local"
    spec = importlib.util.spec_from_file_location(module_name, script_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load embedding script: {script_path}")

    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)

    embedder_class = getattr(module, "Qwen3VLEmbedder", None)
    if embedder_class is None:
        raise RuntimeError("Qwen3VLEmbedder class not found in official script")
    return embedder_class


def find_model_weight_files(model_path: Path) -> List[str]:
    if not model_path.exists():
        return []

    names = set()
    for pattern in WEIGHT_PATTERNS:
        for path in model_path.glob(pattern):
            if path.is_file():
                names.add(path.name)
    return sorted(names)


def load_model() -> Any:
    with load_lock:
        if state.embedder is not None:
            return state.embedder

        state.load_attempted = True
        state.error = None

        if not MODEL_PATH.exists():
            raise FileNotFoundError(f"MODEL_PATH does not exist: {MODEL_PATH}")
        weight_files = find_model_weight_files(MODEL_PATH)
        if not weight_files:
            raise FileNotFoundError(
                "Model weights were not found. Expected model.safetensors, "
                "pytorch_model.bin, or sharded safetensors/bin files under "
                f"{MODEL_PATH}"
            )

        embedder_class = load_embedder_class(MODEL_PATH)
        dtype = parse_torch_dtype(MODEL_DTYPE)

        embedder = embedder_class(
            str(MODEL_PATH),
            default_instruction=DEFAULT_INSTRUCTION,
            torch_dtype=dtype,
        )
        requested_device = MODEL_DEVICE.strip().lower() if MODEL_DEVICE else ""
        if requested_device not in {"auto", ""}:
            if requested_device.startswith("cuda") and not torch.cuda.is_available():
                raise RuntimeError("MODEL_DEVICE=cuda was requested, but CUDA is not available")
            embedder.model.to(torch.device(MODEL_DEVICE))

        state.embedder = embedder
        state.loaded_at = time.time()
        state.device = str(getattr(embedder.model, "device", "unknown"))
        config = getattr(embedder.model, "config", None)
        text_config = getattr(config, "text_config", None)
        hidden_size = getattr(text_config, "hidden_size", None)
        if isinstance(hidden_size, int) and hidden_size > 0:
            state.dimension = hidden_size
        return embedder


def ensure_model() -> Any:
    if state.embedder is not None:
        return state.embedder
    try:
        return load_model()
    except Exception as exc:
        state.error = f"{type(exc).__name__}: {exc}"
        raise HTTPException(status_code=503, detail=f"Model is not available: {state.error}") from exc


def image_from_data_url(value: str) -> Image.Image:
    if "," not in value:
        raise ValueError("Invalid data URL image")
    header, encoded = value.split(",", 1)
    if not header.lower().startswith("data:image/") or ";base64" not in header.lower():
        raise ValueError("Only base64 image data URLs are supported")
    try:
        raw = base64.b64decode(encoded, validate=True)
    except binascii.Error as exc:
        raise ValueError("Invalid base64 image payload") from exc

    try:
        with Image.open(io.BytesIO(raw)) as image:
            return image.convert("RGB").copy()
    except Exception as exc:
        raise ValueError("Invalid image payload") from exc


def normalize_file_path(value: str) -> str:
    if not value.lower().startswith("file://"):
        return value

    parsed = urlparse(value)
    path = unquote(parsed.path)
    if parsed.netloc and parsed.netloc.lower() != "localhost":
        path = f"//{parsed.netloc}{path}"
    if os.name == "nt" and path.startswith("/") and len(path) > 2 and path[2] == ":":
        path = path[1:]
    return path


def image_from_local_path(value: str) -> Optional[Image.Image]:
    path = Path(normalize_file_path(value))
    if not path.exists():
        return None

    try:
        with Image.open(path) as image:
            return image.convert("RGB").copy()
    except Exception as exc:
        raise ValueError(f"Cannot open image path: {path}") from exc


def normalize_image_value(value: Any) -> Any:
    if isinstance(value, Image.Image):
        return value
    if isinstance(value, dict):
        if "url" in value:
            return normalize_image_value(value["url"])
        if "image_url" in value:
            return normalize_image_value(value["image_url"])
        raise ValueError("Image object must contain url or image_url")
    if not isinstance(value, str):
        raise ValueError("Image must be a string, data URL, URL, local path, or image_url object")

    if value.startswith("data:image/"):
        return image_from_data_url(value)

    lower = value.lower()
    if lower.startswith(("http://", "https://", "oss://")):
        return value

    local_image = image_from_local_path(value)
    if local_image is not None:
        return local_image

    raise ValueError(f"Image path does not exist or unsupported image URI: {value}")


def image_url_from_part(part: Dict[str, Any]) -> Any:
    image_url = part.get("image_url")
    if isinstance(image_url, dict):
        return image_url.get("url")
    return image_url


def normalize_content_parts(parts: Any, default_instruction: Optional[str]) -> Dict[str, Any]:
    if isinstance(parts, str):
        return {"text": parts, "instruction": default_instruction}
    if not isinstance(parts, list):
        raise ValueError("content must be a string or a list of content parts")

    text_parts: List[str] = []
    image_value: Any = None

    for part in parts:
        if not isinstance(part, dict):
            raise ValueError("content parts must be objects")
        part_type = part.get("type")
        if part_type in {"text", "input_text"}:
            text = part.get("text", "")
            if text:
                text_parts.append(str(text))
        elif part_type in {"image_url", "input_image", "image"}:
            if image_value is not None:
                raise ValueError("Only one image per embedding item is currently supported")
            image_value = image_url_from_part(part) or part.get("image")
        else:
            raise ValueError(f"Unsupported content part type: {part_type}")

    item: Dict[str, Any] = {"instruction": default_instruction}
    if text_parts:
        item["text"] = "\n".join(text_parts)
    if image_value is not None:
        item["image"] = image_value
    return item


def normalize_item(value: Any, default_instruction: Optional[str]) -> Dict[str, Any]:
    if isinstance(value, str):
        return {"text": value, "instruction": default_instruction}

    if not isinstance(value, dict):
        raise ValueError("Each embedding input must be a string or object")

    if "content" in value:
        item = normalize_content_parts(value["content"], default_instruction)
    elif value.get("type") in {"text", "input_text"}:
        item = {"text": str(value.get("text", "")), "instruction": default_instruction}
    elif value.get("type") in {"image_url", "input_image", "image"}:
        item = {
            "image": image_url_from_part(value) or value.get("image"),
            "instruction": default_instruction,
        }
    else:
        item = {}
        for key in ("text", "image", "video", "instruction", "fps", "max_frames", "dimensions"):
            if key in value:
                item[key] = value[key]
        if "image_url" in value and "image" not in item:
            item["image"] = image_url_from_part(value) or value["image_url"]
        item.setdefault("instruction", default_instruction)

    if not item.get("text") and not item.get("image") and not item.get("video"):
        raise ValueError("Embedding item must include text, image, or video")
    return item


def normalize_openai_input(input_value: Any, default_instruction: Optional[str]) -> List[Dict[str, Any]]:
    if isinstance(input_value, list):
        if not input_value:
            raise ValueError("input must not be empty")
        return [normalize_item(item, default_instruction) for item in input_value]
    return [normalize_item(input_value, default_instruction)]


def prepare_item_for_embedder(item: Dict[str, Any]) -> Dict[str, Any]:
    prepared = {key: value for key, value in item.items() if key != "dimensions"}
    if "image" in prepared and prepared["image"] is not None:
        prepared["image"] = normalize_image_value(prepared["image"])
    return prepared


def validate_dimension(value: Optional[int], full_dimension: int) -> Optional[int]:
    if value is None:
        return None
    if not isinstance(value, int):
        raise HTTPException(status_code=400, detail="dimensions must be an integer")
    if value < MIN_DIMENSION or value > full_dimension:
        raise HTTPException(
            status_code=400,
            detail=f"dimensions must be between {MIN_DIMENSION} and {full_dimension}",
        )
    return value


def apply_dimensions(embeddings: torch.Tensor, dimensions: List[Optional[int]]) -> List[List[float]]:
    full_dimension = int(embeddings.shape[-1])
    result: List[List[float]] = []
    for index, vector in enumerate(embeddings):
        target_dimension = validate_dimension(dimensions[index], full_dimension)
        if target_dimension is not None:
            vector = vector[:target_dimension]
            vector = F.normalize(vector, p=2, dim=-1)
        result.append(vector.detach().float().cpu().tolist())
    return result


def estimate_tokens(items: List[Dict[str, Any]]) -> int:
    total = 0
    for item in items:
        text = item.get("text")
        if text:
            total += max(1, len(str(text)) // 4)
        if item.get("image") is not None:
            total += 1024
        if item.get("video") is not None:
            total += 2048
        if not text and item.get("image") is None and item.get("video") is None:
            total += 1
    return total


def embed_items(
    items: List[Dict[str, Any]],
    request_dimensions: Optional[int],
    normalize: bool,
) -> Tuple[List[List[float]], List[Optional[int]], Dict[str, int]]:
    if not items:
        raise HTTPException(status_code=400, detail="items must not be empty")

    try:
        prepared_items = [prepare_item_for_embedder(item) for item in items]
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    dimensions = [
        item.get("dimensions") if item.get("dimensions") is not None else request_dimensions
        for item in items
    ]
    for dimension in dimensions:
        validate_dimension(dimension, state.dimension)

    embedder = ensure_model()
    try:
        with inference_lock:
            embeddings = embedder.process(prepared_items, normalize=normalize)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Embedding failed: {type(exc).__name__}: {exc}") from exc

    if not isinstance(embeddings, torch.Tensor):
        embeddings = torch.as_tensor(embeddings)
    embeddings = embeddings.detach()

    full_dimension = int(embeddings.shape[-1])
    state.dimension = full_dimension

    vectors = apply_dimensions(embeddings, dimensions)
    prompt_tokens = estimate_tokens(items)
    usage = {"prompt_tokens": prompt_tokens, "total_tokens": prompt_tokens}
    return vectors, dimensions, usage


@app.get("/")
def root() -> Dict[str, Any]:
    return {
        "service": "qwen3-vl-embedding",
        "model": MODEL_ID,
        "endpoints": ["/health", "/v1/embeddings", "/embed"],
    }


@app.get("/health")
def health() -> Dict[str, Any]:
    cuda_device = None
    if torch.cuda.is_available():
        try:
            cuda_device = torch.cuda.get_device_name(0)
        except Exception:
            cuda_device = "cuda"

    script_path = MODEL_PATH / "scripts" / "qwen3_vl_embedding.py"
    weight_files = find_model_weight_files(MODEL_PATH)
    return {
        "status": "ok" if state.embedder is not None else ("error" if state.error else "not_loaded"),
        "loaded": state.embedder is not None,
        "load_attempted": state.load_attempted,
        "error": state.error,
        "model": MODEL_ID,
        "model_path": str(MODEL_PATH),
        "model_path_exists": MODEL_PATH.exists(),
        "official_script_exists": script_path.exists(),
        "weight_files_present": bool(weight_files),
        "weight_files": weight_files,
        "dtype": MODEL_DTYPE,
        "requested_device": MODEL_DEVICE,
        "device": state.device,
        "dimension": state.dimension,
        "cuda_available": torch.cuda.is_available(),
        "cuda_device": cuda_device,
    }


@app.post("/v1/embeddings")
def openai_embeddings(request: OpenAIEmbeddingRequest) -> Dict[str, Any]:
    if request.encoding_format not in (None, "float"):
        raise HTTPException(status_code=400, detail="Only encoding_format='float' is supported")

    try:
        items = normalize_openai_input(request.input, request.instruction)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    vectors, _, usage = embed_items(
        items,
        request_dimensions=request.dimensions,
        normalize=True if request.normalize is None else bool(request.normalize),
    )

    return {
        "object": "list",
        "model": request.model or MODEL_ID,
        "data": [
            {
                "object": "embedding",
                "index": index,
                "embedding": vector,
            }
            for index, vector in enumerate(vectors)
        ],
        "usage": usage,
    }


@app.post("/embed")
def embed(request: EmbedRequest) -> Dict[str, Any]:
    try:
        items = [normalize_item(item, request.instruction) for item in request.items]
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    vectors, dimensions, usage = embed_items(
        items,
        request_dimensions=request.dimensions,
        normalize=True if request.normalize is None else bool(request.normalize),
    )

    vector_dimensions = [len(vector) for vector in vectors]
    shared_dimension = vector_dimensions[0] if len(set(vector_dimensions)) == 1 else None
    return {
        "object": "list",
        "model": request.model or MODEL_ID,
        "dimension": shared_dimension,
        "data": [
            {
                "index": index,
                "embedding": vector,
                "dimension": len(vector),
                "requested_dimensions": dimensions[index],
            }
            for index, vector in enumerate(vectors)
        ],
        "embeddings": vectors,
        "usage": usage,
    }


def main() -> None:
    import uvicorn

    uvicorn.run(app, host=SERVER_HOST, port=SERVER_PORT)


if __name__ == "__main__":
    main()
