## CompAIon — Offline AI for Android

CompAIon is an offline-first Android assistant that keeps the full voice pipeline on-device:
- Speech-to-text via `whisper.cpp` (JNI)
- LLM inference via `mlc4j` / MLC LLM
- Text-to-speech via Android TTS

### One-line GitHub description
`Offline AI assistant for Android: on-device Whisper STT + MLC LLM + Android TTS, with a stylized control-deck UI.`

### Current status
- End-to-end pipeline is wired (PTT → STT → LLM → TTS).
- Model discovery supports both:
  - `/sdcard/Android/data/<package>/files/models/llm`
  - `/data/user/0/<package>/files/models/llm`
- CPU is the safe default; OpenCL/GPU can be enabled later.

### What to consider
- Android scoped storage can break model access when pushed via `adb push`.
- For reliable testing, prefer installing models into internal app storage.
- CPU inference on phones can be slow; use small models for iteration.

### Runbooks
- Full setup + packaging: `docs/MLC_ANDROID_CPU_RUNBOOK.md`
- Quick install: `./gradlew :app:installDebugWithModel`
