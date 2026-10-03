// KomaScroll: JNI bridge running Real-ESRGAN (realesr-animevideov3) with NCNN.
//
// The tiling scheme (fixed-size tiles plus a 10 px pre-padding that is cropped away after inference)
// follows Real-ESRGAN-ncnn-vulkan, MIT License, Copyright (c) 2021 Xintao Wang.
// Unlike that project, pre/post-processing runs on the CPU through ncnn::Mat so that the same code
// path serves both the Vulkan and the CPU backend.

#include <jni.h>

#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <vector>

#include "cpu.h"
#include "gpu.h"
#include "net.h"

#define LOG_TAG "KomaScrollUpscale"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kPrepadding = 10;
constexpr int kMinTileSize = 32;
constexpr int kCpuTileSize = 128;

// Result codes, mirrored in RealEsrgan.kt.
constexpr int kOk = 0;
constexpr int kCancelled = 1;
constexpr int kErrorNotLoaded = -1;
constexpr int kErrorBitmap = -2;
constexpr int kErrorInference = -3;

std::mutex g_mutex;
bool g_gpuInstanceCreated = false;
int g_gpuCount = 0;

ncnn::Net* g_net = nullptr;
int g_scale = 0;
bool g_netUsesGpu = false;

void releaseNetLocked() {
    if (g_net != nullptr) {
        g_net->clear();
        delete g_net;
        g_net = nullptr;
    }
    g_scale = 0;
    g_netUsesGpu = false;
}

int suggestTileSizeLocked() {
    if (!g_netUsesGpu || g_gpuCount <= 0) return kCpuTileSize;
    // Same policy as Real-ESRGAN-ncnn-vulkan: bigger tiles when the GPU heap budget allows.
    const uint32_t budget = ncnn::get_gpu_device(ncnn::get_default_gpu_index())->get_heap_budget();
    if (budget > 1900) return 200;
    if (budget > 550) return 100;
    if (budget > 190) return 64;
    return kMinTileSize;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_komascroll_upscale_engine_RealEsrgan_nativeInitGpu(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_gpuInstanceCreated) {
        g_gpuInstanceCreated = true;
        const int ret = ncnn::create_gpu_instance();
        g_gpuCount = ret == 0 ? ncnn::get_gpu_count() : 0;
        LOGI("create_gpu_instance=%d, gpu count=%d", ret, g_gpuCount);
    }
    return g_gpuCount;
}

JNIEXPORT jstring JNICALL
Java_komascroll_upscale_engine_RealEsrgan_nativeGpuName(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_gpuCount <= 0) return nullptr;
    return env->NewStringUTF(ncnn::get_gpu_info(ncnn::get_default_gpu_index()).device_name());
}

JNIEXPORT jboolean JNICALL
Java_komascroll_upscale_engine_RealEsrgan_nativeLoad(
    JNIEnv* env, jclass, jobject assetManager, jint scale, jboolean useGpu) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const bool gpu = useGpu == JNI_TRUE && g_gpuCount > 0;
    if (g_net != nullptr && g_scale == scale && g_netUsesGpu == gpu) return JNI_TRUE;
    releaseNetLocked();

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == nullptr) return JNI_FALSE;

    auto* net = new ncnn::Net();
    net->opt.use_vulkan_compute = gpu;
    if (gpu) net->set_vulkan_device(ncnn::get_default_gpu_index());
    net->opt.use_fp16_packed = true;
    net->opt.use_fp16_storage = true;
    net->opt.use_fp16_arithmetic = false;
    net->opt.use_int8_storage = true;
    net->opt.num_threads = std::max(1, ncnn::get_big_cpu_count());

    char paramPath[64];
    snprintf(paramPath, sizeof(paramPath), "models/realesr-animevideov3-x%d.param", scale);
    if (net->load_param(mgr, paramPath) != 0 || net->load_model(mgr, "models/realesr-animevideov3.bin") != 0) {
        LOGE("Failed to load model %s", paramPath);
        delete net;
        return JNI_FALSE;
    }

    g_net = net;
    g_scale = scale;
    g_netUsesGpu = gpu;
    LOGI("Loaded x%d model on %s", scale, gpu ? "GPU" : "CPU");
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_komascroll_upscale_engine_RealEsrgan_nativeSuggestTileSize(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return suggestTileSizeLocked();
}

JNIEXPORT void JNICALL
Java_komascroll_upscale_engine_RealEsrgan_nativeRelease(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    releaseNetLocked();
}

JNIEXPORT jint JNICALL
Java_komascroll_upscale_engine_RealEsrgan_nativeUpscale(
    JNIEnv* env, jclass, jobject inBitmap, jobject outBitmap, jint tileSize, jobject callback) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_net == nullptr) return kErrorNotLoaded;

    AndroidBitmapInfo inInfo;
    AndroidBitmapInfo outInfo;
    if (AndroidBitmap_getInfo(env, inBitmap, &inInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, outBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return kErrorBitmap;
    }
    if (inInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || outInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return kErrorBitmap;
    }

    const int w = static_cast<int>(inInfo.width);
    const int h = static_cast<int>(inInfo.height);
    const int s = g_scale;
    if (static_cast<int>(outInfo.width) != w * s || static_cast<int>(outInfo.height) != h * s) {
        return kErrorBitmap;
    }

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onProgress = env->GetMethodID(callbackClass, "onProgress", "(II)Z");
    env->DeleteLocalRef(callbackClass);
    if (onProgress == nullptr) {
        env->ExceptionClear();
        return kErrorBitmap;
    }

    void* inPixels = nullptr;
    void* outPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, inBitmap, &inPixels) != ANDROID_BITMAP_RESULT_SUCCESS) return kErrorBitmap;
    if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, inBitmap);
        return kErrorBitmap;
    }

    const int tile = tileSize > 0 ? std::max(kMinTileSize, static_cast<int>(tileSize)) : suggestTileSizeLocked();
    const int xTiles = (w + tile - 1) / tile;
    const int yTiles = (h + tile - 1) / tile;
    const int totalTiles = xTiles * yTiles;

    const float norm[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    const float denorm[3] = {255.f, 255.f, 255.f};
    std::vector<unsigned char> tilePixels;

    int result = kOk;
    int doneTiles = 0;
    for (int yi = 0; yi < yTiles && result == kOk; yi++) {
        for (int xi = 0; xi < xTiles && result == kOk; xi++) {
            const int x0 = xi * tile;
            const int y0 = yi * tile;
            const int x1 = std::min(x0 + tile, w);
            const int y1 = std::min(y0 + tile, h);
            const int px0 = std::max(x0 - kPrepadding, 0);
            const int py0 = std::max(y0 - kPrepadding, 0);
            const int px1 = std::min(x1 + kPrepadding, w);
            const int py1 = std::min(y1 + kPrepadding, h);

            ncnn::Mat in = ncnn::Mat::from_pixels_roi(
                static_cast<const unsigned char*>(inPixels), ncnn::Mat::PIXEL_RGBA2RGB, w, h,
                static_cast<int>(inInfo.stride), px0, py0, px1 - px0, py1 - py0);
            in.substract_mean_normalize(nullptr, norm);

            ncnn::Mat out;
            {
                ncnn::Extractor ex = g_net->create_extractor();
                if (ex.input("data", in) != 0 || ex.extract("output", out) != 0) {
                    LOGE("Inference failed on tile %d,%d", xi, yi);
                    result = kErrorInference;
                    break;
                }
            }
            if (out.w != (px1 - px0) * s || out.h != (py1 - py0) * s || out.c != 3) {
                LOGE("Unexpected output %dx%dx%d", out.w, out.h, out.c);
                result = kErrorInference;
                break;
            }

            out.substract_mean_normalize(nullptr, denorm);
            tilePixels.resize(static_cast<size_t>(out.w) * out.h * 4);
            out.to_pixels(tilePixels.data(), ncnn::Mat::PIXEL_RGB2RGBA);

            // Copy the tile without its padding into the output bitmap.
            const int cropX = (x0 - px0) * s;
            const int cropY = (y0 - py0) * s;
            const int cropW = (x1 - x0) * s;
            const int cropH = (y1 - y0) * s;
            for (int row = 0; row < cropH; row++) {
                const unsigned char* src =
                    tilePixels.data() + (static_cast<size_t>(cropY + row) * out.w + cropX) * 4;
                unsigned char* dst = static_cast<unsigned char*>(outPixels) +
                    static_cast<size_t>(y0 * s + row) * outInfo.stride + static_cast<size_t>(x0) * s * 4;
                memcpy(dst, src, static_cast<size_t>(cropW) * 4);
            }

            doneTiles++;
            const jboolean keepGoing = env->CallBooleanMethod(callback, onProgress, doneTiles, totalTiles);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                result = kCancelled;
            } else if (keepGoing != JNI_TRUE) {
                result = kCancelled;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, outBitmap);
    AndroidBitmap_unlockPixels(env, inBitmap);
    return result;
}

}  // extern "C"
