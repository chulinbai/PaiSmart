# Qwen3-VL-Embedding FastAPI 服务

这个目录提供一个本地 FastAPI embedding 服务，直接复用模型目录里的官方脚本：

```text
F:\LLM_models\hf-model\Qwen_Qwen3-VL-Embedding-2B\scripts\qwen3_vl_embedding.py
```

默认模型路径：

```text
F:\LLM_models\hf-model\Qwen_Qwen3-VL-Embedding-2B
```

> 注意：之前提到的 `F:\LLM\_models\...` 当前不存在，本服务默认使用实际存在的 `F:\LLM_models\...` 路径。

模型目录必须包含权重文件，例如：

```text
model.safetensors
pytorch_model.bin
model-00001-of-000xx.safetensors
model.safetensors.index.json
```

如果 `/health` 中 `weight_files_present=false`，说明当前目录只有配置/tokenizer/脚本，真实 embedding 请求会在加载模型时失败，需要先补齐 Hugging Face 权重文件。

## 环境依赖

在你的 `fastapi` conda 环境中安装服务依赖：

```bash
conda activate fastapi
pip install fastapi "uvicorn[standard]" pydantic accelerate scipy opencv-python-headless decord sentence-transformers
```

完整依赖清单见：

```text
requirements.txt
```

当前已验证存在的核心依赖包括：

```text
torch==2.8.0+cu128
transformers==5.17.0
qwen-vl-utils==0.0.14
pillow==12.3.0
numpy==2.4.6
requests
av
torchvision
```

## 启动

从仓库根目录启动：

```bash
conda run -n fastapi uvicorn app:app --app-dir tools/qwen3_vl_embedding_server --host 127.0.0.1 --port 11435
```

或进入服务目录启动：

```bash
cd tools/qwen3_vl_embedding_server
conda run -n fastapi uvicorn app:app --host 127.0.0.1 --port 11435
```

也可以直接运行：

```bash
cd tools/qwen3_vl_embedding_server
python app.py
```

可选环境变量：

```text
MODEL_PATH=F:\LLM_models\hf-model\Qwen_Qwen3-VL-Embedding-2B
MODEL_ID=MedAIBase/Qwen3-VL-Embedding:2b
MODEL_DTYPE=bfloat16
MODEL_DEVICE=cuda
SERVER_HOST=127.0.0.1
SERVER_PORT=11435
PRELOAD_MODEL=true
```

如果只想先启动 API 壳子并通过 `/health` 看配置，不在启动阶段加载模型：

PowerShell：

```powershell
$env:PRELOAD_MODEL = "false"
conda run -n fastapi uvicorn app:app --app-dir tools/qwen3_vl_embedding_server --host 127.0.0.1 --port 11435
```

cmd：

```bat
set PRELOAD_MODEL=false
conda run -n fastapi uvicorn app:app --app-dir tools/qwen3_vl_embedding_server --host 127.0.0.1 --port 11435
```

## 接口

### GET /health

查看模型路径、加载状态、设备、CUDA 状态和向量维度。

PowerShell 示例：

```powershell
Invoke-RestMethod -Method Get -Uri http://127.0.0.1:11435/health
```

### POST /v1/embeddings

兼容 OpenAI embedding 返回格式，同时支持多模态输入。

纯文本：

```powershell
$body = @{
  model = "MedAIBase/Qwen3-VL-Embedding:2b"
  input = "青铜器上的饕餮纹"
} | ConvertTo-Json -Depth 10

Invoke-RestMethod -Method Post -Uri http://127.0.0.1:11435/v1/embeddings -ContentType "application/json" -Body $body
```

纯图片：

```powershell
$body = @{
  model = "MedAIBase/Qwen3-VL-Embedding:2b"
  input = @{
    image = "F:/images/relic.png"
  }
} | ConvertTo-Json -Depth 10

Invoke-RestMethod -Method Post -Uri http://127.0.0.1:11435/v1/embeddings -ContentType "application/json" -Body $body
```

图片 + 文本联合 embedding：

```powershell
$body = @{
  model = "MedAIBase/Qwen3-VL-Embedding:2b"
  input = @{
    text = "这是一件带有铭文的青铜器"
    image = "F:/images/relic.png"
  }
} | ConvertTo-Json -Depth 10

Invoke-RestMethod -Method Post -Uri http://127.0.0.1:11435/v1/embeddings -ContentType "application/json" -Body $body
```

批量输入：

```json
{
  "model": "MedAIBase/Qwen3-VL-Embedding:2b",
  "input": [
    "陶俑",
    { "image": "F:/images/relic.png" },
    { "text": "青花瓷局部纹样", "image": "F:/images/porcelain.png" }
  ]
}
```

`text + image` 在同一个 item 中会生成一个联合语义向量，不会拆成两个向量。

### POST /embed

PaiSmart 可以直接使用的简洁接口。

```json
{
  "items": [
    {
      "text": "青铜器纹样",
      "image": "F:/images/relic.png",
      "instruction": "Represent this cultural relic image and text for retrieval.",
      "dimensions": 1024
    }
  ]
}
```

返回中同时包含：

```text
data[].embedding
embeddings[]
dimension
usage
```

## 向量维度

默认返回 2048 维归一化向量。

如果请求传入 `dimensions`，服务会截取前 N 维并重新归一化：

```json
{
  "input": "青铜器",
  "dimensions": 1024
}
```

`dimensions` 范围为 `64..2048`。

## PaiSmart 接入

`MultiEmbeddingClient` 可以指向：

```text
http://127.0.0.1:11435/v1/embeddings
```

图片入库向量、图片+文本联合向量、文本查询向量都应保持相同维度，才能写入 Elasticsearch `dense_vector` 并做向量检索。

## 烟测

补齐模型权重后，可以运行：

```bash
conda run -n fastapi python tools/qwen3_vl_embedding_server/tests/smoke_test.py
```

这个脚本会依次测试：

```text
/health
/v1/embeddings 纯文本
/v1/embeddings base64 图片
/embed 文本+本地图片，并请求 1024 维截断
```
