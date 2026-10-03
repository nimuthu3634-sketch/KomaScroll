cmake_minimum_required(VERSION 3.22.1)

project(komascroll_upscale CXX)

if(NOT NCNN_ROOT OR NOT EXISTS "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake")
    message(FATAL_ERROR
        "NCNN prebuilt not found at '${NCNN_ROOT}'. "
        "Run `gradlew :komascroll-upscale:downloadNcnn` (it needs internet access once), then sync again.")
endif()

set(ncnn_DIR "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

add_library(komascroll_upscale SHARED realesrgan_jni.cpp)

target_compile_options(komascroll_upscale PRIVATE -Wall -Wextra -O2)
target_link_libraries(komascroll_upscale ncnn)

# Smaller library: drop unused sections and keep only the JNI entry points exported.
target_link_options(komascroll_upscale PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    # 16 KB page alignment, required for apps targeting Android 15+ devices with 16 KB pages.
    -Wl,-z,max-page-size=16384
)
