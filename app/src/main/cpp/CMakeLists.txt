cmake_minimum_required(VERSION 3.22)
project(appunti_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# ggml è statica ma finisce dentro una libreria condivisa: serve -fPIC.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Sorgenti di whisper.cpp 1.8.5 (licenza MIT) inclusi nel progetto:
# la build non deve scaricare nulla da GitHub.
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# ggml come libreria statica, solo backend CPU.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

if (ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    # ARMv8.2 con fp16 e dotprod: tutti i telefoni con SoC dal 2018 circa in poi
    # (Snapdragon 845+, Exynos 9810+, Dimensity, Tensor). Su telefoni più vecchi
    # sostituisci con "armv8-a".
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif ()

add_subdirectory(${WHISPER_DIR}/ggml ggml)

add_library(appunti_whisper SHARED
        ${WHISPER_DIR}/src/whisper.cpp
        whisper_jni.cpp)

target_include_directories(appunti_whisper PRIVATE
        ${WHISPER_DIR}/include
        ${WHISPER_DIR}/src
        ${WHISPER_DIR}/ggml/include)

target_compile_definitions(appunti_whisper PRIVATE WHISPER_VERSION="1.8.5")
target_compile_options(appunti_whisper PRIVATE -O3)

if (ANDROID)
    target_compile_options(appunti_whisper PRIVATE
            -fvisibility=hidden -fvisibility-inlines-hidden
            -ffunction-sections -fdata-sections)
    target_link_options(appunti_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
    find_library(LOG_LIB log)
    target_link_libraries(appunti_whisper PRIVATE ggml ${LOG_LIB} android)
else ()
    # Build sul PC solo per verificare che il codice compili.
    find_package(JNI REQUIRED)
    target_include_directories(appunti_whisper PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(appunti_whisper PRIVATE ggml)
endif ()
