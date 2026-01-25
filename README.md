# CompAIon – Clean Android Skeleton (PTT + WAV + JNI)

This is a clean-from-scratch project that builds in Android Studio (AndroidX enabled).

## What works out of the box
- Kotlin app with PTT button
- Records 16kHz mono PCM16 → WAV
- JNI bridge returns placeholder string
- Android TTS speaks responses

## Next: Enable Whisper STT
### 1) Add submodule
From project root:
```bash
git init
git submodule add https://github.com/ggerganov/whisper.cpp app/src/main/cpp/whisper.cpp
git submodule update --init --recursive
```

### 2) Replace `app/src/main/cpp/CMakeLists.txt` with this whisper-enabled version
```cmake
cmake_minimum_required(VERSION 3.22.1)
project("offlinevoice")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

add_library(ggml STATIC
    ${WHISPER_DIR}/ggml/src/ggml.c
    ${WHISPER_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_DIR}/ggml/src/ggml-backend.c
    ${WHISPER_DIR}/ggml/src/ggml-quants.c
    ${WHISPER_DIR}/ggml/src/ggml-threading.c
)
target_include_directories(ggml PUBLIC
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
)

add_library(whisper STATIC
    ${WHISPER_DIR}/src/whisper.cpp
)
target_include_directories(whisper PUBLIC
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/src
)
target_link_libraries(whisper PUBLIC ggml)

add_library(native-lib SHARED native-lib.cpp)
find_library(log-lib log)

target_include_directories(native-lib PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
)

target_link_libraries(native-lib
    whisper
    ggml
    ${log-lib}
)
```

### 3) Replace `native-lib.cpp` with the real whisper JNI (provided in our chat)
### 4) Push whisper model to device
```bash
adb shell mkdir -p /sdcard/Android/data/com.example.offlinevoice/files/models/stt
adb push ggml-base.bin /sdcard/Android/data/com.example.offlinevoice/files/models/stt/ggml-base.bin
```

## Notes
- AndroidX is enabled in `gradle.properties` to avoid build/runtime issues.
- Gradle wrapper scripts are not included; Android Studio can generate them if needed.

## Astral Pirates styling (Jan 2026)
This app UI was styled to match astralpirates.com (Special Elite font, animated rainbow H1/H2, space gradient, haze panel, and the rotating Values GLB background).

### What was added
- `io.github.sceneview:sceneview:2.3.3` dependency in `app/build.gradle.kts` for Filament rendering.
- `app/src/main/assets/models/values.glb` copied from `astralpirates.com/frontend/public/assets/models/values.glb`.
- Custom views:
  - `AnimatedGradientTextView` (animated gradient text)
- New styles + tokens + drawables in `app/src/main/res/values` and `app/src/main/res/drawable`.
- `activity_main.xml` rebuilt with layered background + frosted panel.

### 3D model setup
- SceneView is configured as transparent so the gradient/haze background shows through.
- Rotation and camera are configured in `setupValuesScene()` in `app/src/main/java/com/example/offlinevoice/MainActivity.kt`.
- Lighting/material tuning happens in:
  - `tuneAstralLighting(sceneView)`
  - `tuneAstralMaterials(modelNode)`

### Adjusting the look
- Bigger/smaller model: change `scaleToUnits`, `scale`, or `cameraNode.position`.
- More glassy: lower `roughnessFactor`, raise `transmissionFactor`, raise `clearCoatFactor`.
- More glow: increase `emissiveFactor` or add bloom (not enabled by default).

### Replacing the model
- Drop a new GLB at `app/src/main/assets/models/values.glb` (same path), or change the path in `setupValuesScene()`.
