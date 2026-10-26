from __future__ import annotations

import base64
import io
import math
import sys
import tempfile
import warnings
from pathlib import Path

warnings.filterwarnings(
    "ignore",
    message=r"Using `httpx` with `starlette\.testclient` is deprecated.*",
)

from fastapi.testclient import TestClient
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import app as embedding_app


def l2_norm(values: list[float]) -> float:
    return round(math.sqrt(sum(float(value) * float(value) for value in values)), 6)


def make_test_image() -> tuple[str, Path]:
    image = Image.new("RGB", (320, 240), "white")
    draw = ImageDraw.Draw(image)
    draw.rectangle((45, 45, 275, 195), outline="black", width=6)
    draw.ellipse((100, 70, 220, 190), outline="darkred", width=8)
    draw.text((80, 18), "relic test image", fill="black")

    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    data_url = "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")

    local_path = Path(tempfile.gettempdir()) / "qwen3_vl_embedding_smoke.png"
    image.save(local_path)
    return data_url, local_path


def require_ok(response, label: str) -> dict:
    print(f"{label}_status", response.status_code)
    if response.status_code != 200:
        print(response.text)
        raise SystemExit(1)
    return response.json()


def main() -> None:
    client = TestClient(embedding_app.app)
    data_url, local_path = make_test_image()

    health_before = require_ok(client.get("/health"), "health_before")
    print(
        "health_before_weight_files_present",
        health_before["weight_files_present"],
        "cuda_available",
        health_before["cuda_available"],
    )

    text_json = require_ok(
        client.post(
            "/v1/embeddings",
            json={
                "model": "MedAIBase/Qwen3-VL-Embedding:2b",
                "input": "bronze vessel taotie pattern",
            },
        ),
        "text",
    )
    text_vector = text_json["data"][0]["embedding"]
    print("text_dim", len(text_vector), "text_norm", l2_norm(text_vector))

    image_json = require_ok(
        client.post(
            "/v1/embeddings",
            json={
                "model": "MedAIBase/Qwen3-VL-Embedding:2b",
                "input": {"image": data_url},
            },
        ),
        "base64_image",
    )
    image_vector = image_json["data"][0]["embedding"]
    print("base64_image_dim", len(image_vector), "base64_image_norm", l2_norm(image_vector))

    joint_json = require_ok(
        client.post(
            "/embed",
            json={
                "items": [
                    {
                        "text": "bronze relic with circular pattern",
                        "image": str(local_path),
                        "dimensions": 1024,
                    }
                ]
            },
        ),
        "joint",
    )
    joint_vector = joint_json["embeddings"][0]
    print(
        "joint_dim",
        len(joint_vector),
        "joint_norm",
        l2_norm(joint_vector),
        "response_dimension",
        joint_json["dimension"],
    )

    health_after = require_ok(client.get("/health"), "health_after")
    print(
        "health_after_loaded",
        health_after["loaded"],
        "device",
        health_after["device"],
        "dimension",
        health_after["dimension"],
    )


if __name__ == "__main__":
    main()
