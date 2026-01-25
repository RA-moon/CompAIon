## MLC on Android (CPU runbook for MI Mix 4)

This project is now set up to run MLC **on CPU by default** because OpenCL is not accessible from the app namespace on the MI Mix 4.

This document explains:
- what was changed,
- why it was necessary,
- how to repeat the setup later,
- and how to switch models safely.

---

## Current working baseline

- Model: `Qwen2.5-0.5B-Instruct-q4f16_1-MLC`
- Model lib id: `qwen2_q4f16_1_95967267c464e10967be161a66e856d4`
- Device mode: CPU

Key config locations:
- `mlc-package-config.json:5`
- `app/build.gradle.kts:74`
- `app/build.gradle.kts:129`
- `app/src/main/java/com/example/offlinevoice/MlcEngine.kt:36`
- `app/src/main/java/com/example/offlinevoice/MlcEngine.kt:240`
- `/Users/ramunriklin/Projects/mlc-llm/android/mlc4j/src/main/java/ai/mlc/mlcllm/JSONFFIEngine.java:39`

---

## Why GPU/OpenCL was disabled

On the MI Mix 4, the runtime crashed when trying to open OpenCL from within the app:
- `Cannot open libOpenCL!`
- linker namespace errors for `/vendor/lib64/libOpenCL.so`

Because of this, OpenCL must be treated as unavailable even if it appears present on the device.

---

## What was changed (important)

### 1) Force CPU by default in mlc-llm

OpenCL is now opt-in and CPU is the default:
- `/Users/ramunriklin/Projects/mlc-llm/android/mlc4j/src/main/java/ai/mlc/mlcllm/JSONFFIEngine.java:39`

Behavior:
- Default: CPU
- OpenCL only if `-Dcompaion.mlc.device=opencl` is explicitly set

### 2) Allow bundled model lib inside `tvm4j_runtime_packed`

The app no longer hard-fails if the model `.so` is missing but the packed runtime exists:
- `app/src/main/java/com/example/offlinevoice/MlcEngine.kt:240`

This matches the packaging approach where the model is linked into `libtvm4j_runtime_packed.so`.

### 3) Prefer the model specified in `mlc-app-config.json`

Model selection now respects the configured model id instead of picking the first directory:
- `app/src/main/java/com/example/offlinevoice/MlcEngine.kt:50`
- `app/src/main/java/com/example/offlinevoice/MlcEngine.kt:182`

### 4) Fix external storage permissions after `adb push`

`adb push` created unreadable directories for the app. The install task now normalizes permissions:
- `app/build.gradle.kts:151`

It runs:
- directories → `755`
- files → `644`

---

## Critical gotcha: external storage vs internal storage

Even with fixed permissions, the app still failed to discover models under:
- `/sdcard/Android/data/com.example.offlinevoice/files/...`

Reliable solution:
- copy the model into **internal app storage** via `run-as`
- use `/data/user/0/com.example.offlinevoice/files/models/llm`

This is currently the most important operational detail.

---

## Tooling setup (container + mounts)

Work is performed inside the running container `mlcpack`.

Current mounts:
- `/Users/ramunriklin/Projects/CompAIon -> /workspace/CompAIon`
- `/Users/ramunriklin/Projects/mlc-llm -> /workspace/mlc-llm`
- `/Users/ramunriklin/.cache/mlc_llm -> /root/.cache/mlc_llm`
- NDK cache: `/Users/ramunriklin/Projects/CompAIon/build/cache/ndk -> /opt/ndk-cache`

Check:

```bash
docker ps --format '{{.Names}}\t{{.Status}}'
docker inspect mlcpack --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}'
```

---

## End-to-end workflow (repeatable)

### Step 1 — Choose model in repo config

Set the model here:
- `mlc-package-config.json:5`

Set the model id + model lib here:
- `app/build.gradle.kts:74`

The model lib id must match what packaging generates later.

---

### Step 2 — Compile a CPU model library (inside container)

Compile to a `.tar` containing object files:

```bash
docker exec mlcpack bash -lc "
set -euo pipefail
python -m mlc_llm compile \
  HF://mlc-ai/Qwen2.5-0.5B-Instruct-q4f16_1-MLC \
  --device cpu \
  --host aarch64-linux-android \
  --opt O0 \
  --overrides 'context_window_size=512;prefill_chunk_size=8;max_batch_size=1' \
  -o /workspace/CompAIon/build/qwen2_0p5b_cpu.tar
"
```

Create a static library from the objects:

```bash
docker exec mlcpack bash -lc '
set -euo pipefail
rm -rf /workspace/CompAIon/build/qwen2_0p5b_cpu_obj
mkdir -p /workspace/CompAIon/build/qwen2_0p5b_cpu_obj
tar -xf /workspace/CompAIon/build/qwen2_0p5b_cpu.tar -C /workspace/CompAIon/build/qwen2_0p5b_cpu_obj
AR=/opt/ndk-cache/android-ndk-r26d/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar
"$AR" rcs /workspace/CompAIon/build/lib/libmodel_android_cpu_0p5b.a /workspace/CompAIon/build/qwen2_0p5b_cpu_obj/*.o
'
```

---

### Step 3 — Package mlc4j using the prebuilt CPU lib

This uses custom hooks in:
- `/Users/ramunriklin/Projects/mlc-llm/android/mlc4j/prepare_libs.py:119`

Important environment variables:
- `MLC4J_PREBUILT_MODEL_LIB`
- `MLC4J_PREBUILT_TOKENIZERS_LIB`
- `MLC4J_BUILD_JOBS`

Run:

```bash
docker exec mlcpack bash -lc '
set -euo pipefail
rm -rf /workspace/mlc-llm/android/mlc4j/native_build
NDK_DIR=/opt/ndk-cache/android-ndk-r26d
NDK_CC="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang++"
export ANDROID_NDK_HOME="$NDK_DIR"
export ANDROID_NDK="$NDK_DIR"
export TVM_NDK_CC="$NDK_CC"
export MLC4J_BUILD_JOBS=1
export MLC4J_PREBUILT_TOKENIZERS_LIB=/workspace/mlc-llm/android/mlc4j/native_build_host_1769304276/mlc_llm/tokenizers/aarch64-linux-android/release/libtokenizers_c.a
export MLC4J_PREBUILT_MODEL_LIB=/workspace/CompAIon/build/lib/libmodel_android_cpu_0p5b.a
python -m mlc_llm package \
  --mlc-llm-source-dir /workspace/mlc-llm \
  --package-config /workspace/CompAIon/mlc-package-config.json
'
```

---

### Step 4 — Update the model lib id in Gradle

Read the generated lib id:

```bash
sed -n '1,40p' dist/lib/mlc4j/src/main/assets/mlc-app-config.json
```

Then update:
- `app/build.gradle.kts:75`

---

### Step 5 — Ensure weights exist on the host

Packaging may download into the container cache. Copy it back:

```bash
mkdir -p ~/.cache/mlc_llm/model_weights/hf/mlc-ai
docker cp mlcpack:/root/.cache/mlc_llm/model_weights/hf/mlc-ai/Qwen2.5-0.5B-Instruct-q4f16_1-MLC ~/.cache/mlc_llm/model_weights/hf/mlc-ai/
```

---

### Step 6 — Build + install the app

```bash
./gradlew :app:installDebugWithModel --rerun-tasks
```

---

### Step 7 — Copy the model into internal storage (required)

This avoids scoped-storage issues.

Copy weights into internal app storage:

```bash
tar -cf - -C ~/.cache/mlc_llm/model_weights/hf/mlc-ai Qwen2.5-0.5B-Instruct-q4f16_1-MLC \
  | adb -s 70f27038 shell "run-as com.example.offlinevoice sh -c 'mkdir -p /data/user/0/com.example.offlinevoice/files/models/llm && tar -xf - -C /data/user/0/com.example.offlinevoice/files/models/llm'"
```

Write `model_lib.txt` internally:

```bash
printf '%s' qwen2_q4f16_1_95967267c464e10967be161a66e856d4 \
  | adb -s 70f27038 shell "run-as com.example.offlinevoice sh -c 'cat > /data/user/0/com.example.offlinevoice/files/models/llm/Qwen2.5-0.5B-Instruct-q4f16_1-MLC/model_lib.txt'"
```

Write `mlc-app-config.json` internally:

```bash
adb -s 70f27038 shell "run-as com.example.offlinevoice sh -c 'cat > /data/user/0/com.example.offlinevoice/files/models/llm/mlc-app-config.json'" \
  < app/build/generated/assets/mlc/mlc-app-config.json
```

Optional cleanup of stale internal models:

```bash
adb -s 70f27038 shell run-as com.example.offlinevoice rm -rf \
  /data/user/0/com.example.offlinevoice/files/models/llm/Qwen2.5-3B-Instruct-q4f16_1-MLC \
  /data/user/0/com.example.offlinevoice/files/models/llm/Qwen2.5-1.5B-Instruct-q4f16_1-MLC
```

---

## How to verify quickly

Clear logs, reproduce once, then check:

```bash
adb -s 70f27038 logcat -c
# press PTT once
adb -s 70f27038 logcat -d -v time | rg "CompAIon.MlcEngine|resolveModel|modelLib|TVMError|IllegalStateException"
```

Healthy signs:
- model dir resolves to internal storage
- model lib resolves correctly
- no “Kein MLC-Modell gefunden”
- no “Model lib nicht gefunden”

Example good sequence:
- `resolveModel preferredIds=Qwen2.5-0.5B...`
- `resolveModel modelDir=/data/user/0/.../Qwen2.5-0.5B...`
- `resolveModelLib reading /data/user/0/.../mlc-app-config.json`

---

## Performance expectations on CPU

This works but is slow:
- observed: first token ~75 seconds

For faster iteration:
- try a smaller model
- reduce `context_window_size`
- keep `prefill_chunk_size` low

---

## Switching models later (checklist)

When you change the model:

1) Update model in:
- `mlc-package-config.json:5`

2) Re-run:
- compile (CPU)
- package (with `MLC4J_PREBUILT_MODEL_LIB`)

3) Update lib id in:
- `app/build.gradle.kts:75`

4) Reinstall:
- `./gradlew :app:installDebugWithModel --rerun-tasks`

5) Re-copy to internal storage:
- tar pipe via `run-as`
- write internal `model_lib.txt`
- write internal `mlc-app-config.json`

If you skip step 5, you can still see “Kein MLC-Modell gefunden” even after a successful build.

