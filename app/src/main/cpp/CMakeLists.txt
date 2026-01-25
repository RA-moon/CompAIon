cmake_minimum_required(VERSION 3.22.1)
project("offlinevoice")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
set(GGML_SRC ${WHISPER_DIR}/ggml/src)
set(GGML_CPU_SRC ${GGML_SRC}/ggml-cpu)

add_library(ggml STATIC
    ${GGML_SRC}/ggml.c
    ${GGML_SRC}/ggml-alloc.c
    ${GGML_SRC}/ggml-backend.cpp
    ${GGML_SRC}/ggml-backend-reg.cpp
    ${GGML_SRC}/ggml-quants.c
    ${GGML_SRC}/ggml-threading.cpp
    ${GGML_SRC}/ggml-opt.cpp
    ${GGML_CPU_SRC}/ggml-cpu.c
    ${GGML_CPU_SRC}/ggml-cpu.cpp
    ${GGML_CPU_SRC}/repack.cpp
    ${GGML_CPU_SRC}/hbm.cpp
    ${GGML_CPU_SRC}/quants.c
    ${GGML_CPU_SRC}/traits.cpp
    ${GGML_CPU_SRC}/binary-ops.cpp
    ${GGML_CPU_SRC}/unary-ops.cpp
    ${GGML_CPU_SRC}/vec.cpp
    ${GGML_CPU_SRC}/ops.cpp
    ${GGML_CPU_SRC}/arch/arm/quants.c
    ${GGML_CPU_SRC}/arch/arm/repack.cpp
)

target_include_directories(ggml PUBLIC
    ${WHISPER_DIR}/ggml/include
    ${GGML_SRC}
    ${GGML_CPU_SRC}
)

add_library(whisper STATIC
    ${WHISPER_DIR}/src/whisper.cpp
)
target_compile_definitions(whisper PRIVATE
        WHISPER_VERSION=0
)

target_include_directories(whisper PUBLIC
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/src
)

target_link_libraries(whisper PUBLIC ggml)

add_library(native-lib SHARED native-lib.cpp)

find_library(log-lib log)

target_compile_definitions(ggml PRIVATE
        GGML_VERSION=0
        GGML_COMMIT=\"unknown\"
        GGML_USE_CPU
)

target_include_directories(native-lib PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
    ${GGML_SRC}
)

target_link_libraries(native-lib
    whisper
    ggml
    ${log-lib}
)
