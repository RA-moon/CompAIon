#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/build_model_zip.sh --model-dir <path> --out <zip> [options]

Options:
  --model-lib <id>        Model lib id (e.g. qwen2_q4f16_1_...)
  --model-so <path>       Path to lib<model_lib>.so
  --allow-missing-so      Allow zip creation without lib<model_lib>.so (not recommended)

Example:
  scripts/build_model_zip.sh \
    --model-dir ~/.cache/mlc_llm/model_weights/hf/mlc-ai/Qwen2.5-0.5B-Instruct-q4f16_1-MLC \
    --model-lib qwen2_q4f16_1_95967267c464e10967be161a66e856d4 \
    --model-so /path/to/libqwen2_q4f16_1_95967267c464e10967be161a66e856d4.so \
    --out dist/model-zips/Qwen2.5-0.5B-Instruct-q4f16_1-MLC.zip
EOF
}

MODEL_DIR=""
MODEL_LIB=""
MODEL_SO=""
OUT=""
ALLOW_MISSING_SO="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --model-dir) MODEL_DIR="$2"; shift 2;;
    --model-lib) MODEL_LIB="$2"; shift 2;;
    --model-so) MODEL_SO="$2"; shift 2;;
    --out) OUT="$2"; shift 2;;
    --allow-missing-so) ALLOW_MISSING_SO="true"; shift 1;;
    -h|--help) usage; exit 0;;
    *) echo "Unknown arg: $1"; usage; exit 1;;
  esac
done

if [[ -z "$MODEL_DIR" || -z "$OUT" ]]; then
  usage
  exit 1
fi

MODEL_DIR="${MODEL_DIR/#\~/$HOME}"
OUT="${OUT/#\~/$HOME}"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
if [[ ! -d "$MODEL_DIR" ]]; then
  echo "Model dir not found: $MODEL_DIR" >&2
  exit 1
fi

if [[ ! -f "$MODEL_DIR/mlc-chat-config.json" ]]; then
  echo "Missing mlc-chat-config.json in $MODEL_DIR" >&2
  exit 1
fi

if [[ -z "$MODEL_LIB" ]]; then
  if [[ -f "$MODEL_DIR/model_lib.txt" ]]; then
    MODEL_LIB="$(cat "$MODEL_DIR/model_lib.txt" | tr -d '[:space:]')"
  else
    MODEL_LIB="$(python3 - <<'PY'
import json, sys, pathlib
cfg = pathlib.Path(sys.argv[1]).read_text()
data = json.loads(cfg)
print(data.get("model_lib","").strip())
PY
"$MODEL_DIR/mlc-chat-config.json")"
  fi
fi

MODEL_NAME="$(basename "$MODEL_DIR")"
if [[ -z "$MODEL_LIB" ]]; then
  echo "Could not determine model_lib. Provide --model-lib or model_lib.txt." >&2
  exit 1
fi

if [[ -z "$MODEL_SO" ]]; then
  if [[ -f "$MODEL_DIR/lib${MODEL_LIB}.so" ]]; then
    MODEL_SO="$MODEL_DIR/lib${MODEL_LIB}.so"
  else
    FOUND_SO="$(find "$MODEL_DIR" -name "lib${MODEL_LIB}.so" -maxdepth 2 2>/dev/null | head -n 1 || true)"
    if [[ -n "$FOUND_SO" ]]; then
      MODEL_SO="$FOUND_SO"
    fi
  fi
fi

if [[ -z "$MODEL_SO" && "$ALLOW_MISSING_SO" != "true" ]]; then
  echo "Missing lib${MODEL_LIB}.so. Provide --model-so or build the model lib first." >&2
  echo "Tip: run mlc_llm package for the model to generate the .so." >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
TARGET_DIR="$TMP_DIR/$MODEL_NAME"
mkdir -p "$TARGET_DIR"

rsync -a "$MODEL_DIR/" "$TARGET_DIR/"

if [[ ! -f "$TARGET_DIR/model_lib.txt" ]]; then
  echo "$MODEL_LIB" > "$TARGET_DIR/model_lib.txt"
fi

if [[ -n "$MODEL_SO" ]]; then
  cp -f "$MODEL_SO" "$TARGET_DIR/lib${MODEL_LIB}.so"
fi

mkdir -p "$(dirname "$OUT")"
if command -v zip >/dev/null 2>&1; then
  (cd "$TMP_DIR" && zip -qr "$OUT" "$MODEL_NAME")
else
  ditto -c -k --sequesterRsrc --keepParent "$TARGET_DIR" "$OUT"
fi

echo "ZIP created: $OUT"
