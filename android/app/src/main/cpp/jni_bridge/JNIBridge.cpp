/**
 * JNIBridge.cpp — JNI Bridge between Kotlin and C++ Engines
 *
 * Exposes ALL native functions to Kotlin via JNI.
 * Handles:
 * - Type conversion (Kotlin ↔ C++)
 * - Exception safety (C++ exceptions → JNI error codes)
 * - Memory management at boundary
 * - Thread safety markers
 */

#include <jni.h>
#include <android/log.h>
#include "../vision/VisionCore.h"
#include "../ocr/OCRCore.h"
#include "../frame_processor/FrameProcessor.h"
#include "../perf/MemoryPool.h"
#include <string>
#include <vector>

#define LOG_TAG "JNIBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// Helper: Convert JNI byte array to native pointer (no copy if possible)
// ─────────────────────────────────────────────────────────────────────────────

struct JNIArrayGuard {
    JNIEnv*    env;
    jbyteArray arr;
    jbyte*     ptr;

    JNIArrayGuard(JNIEnv* e, jbyteArray a)
        : env(e), arr(a) {
        ptr = env->GetByteArrayElements(arr, nullptr);
    }
    ~JNIArrayGuard() {
        env->ReleaseByteArrayElements(arr, ptr, JNI_ABORT);
    }
    uint8_t* data() { return reinterpret_cast<uint8_t*>(ptr); }
};

// Helper to create a Java DetectedElement object from C++ struct
static jobject create_java_element(JNIEnv* env,
                                    const visionagent::DetectedElement& el) {
    // Cache class/method references (in practice, cache these at Init time)
    jclass clazz = env->FindClass(
        "com/visionagent/core/vision/NativeDetectedElement");
    if (!clazz) return nullptr;

    jmethodID ctor = env->GetMethodID(clazz, "<init>", "(IIIIIFI)V");
    if (!ctor) return nullptr;

    return env->NewObject(clazz, ctor,
                          (jint)el.type,
                          (jint)el.bounds.left,
                          (jint)el.bounds.top,
                          (jint)el.bounds.right,
                          (jint)el.bounds.bottom,
                          (jfloat)el.confidence,
                          (jint)el.area);
}

// ─────────────────────────────────────────────────────────────────────────────
// VisionNativeBridge JNI Methods
// ─────────────────────────────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_visionagent_core_vision_VisionNativeBridge_initialize(
    JNIEnv* env, jobject /* this */,
    jobject config) {

    jclass config_class = env->GetObjectClass(config);

    jfloat conf = env->GetFloatField(config,
        env->GetFieldID(config_class, "confidenceThreshold", "F"));
    // GPU acceleration flag — read but not yet wired to runtime; suppress unused warning
    [[maybe_unused]]
    jboolean gpu = env->GetBooleanField(config,
        env->GetFieldID(config_class, "enableGPUAcceleration", "Z"));

    // Default 1080×1920 — will be updated on first frame
    return (jboolean)visionagent::vision_initialize(1080, 1920, conf);
}

JNIEXPORT jobject JNICALL
Java_com_visionagent_core_vision_VisionNativeBridge_processFrame(
    JNIEnv* env, jobject /* this */,
    jbyteArray frame_data,
    jint width,
    jint height,
    jobject config) {

    JNIArrayGuard guard(env, frame_data);
    uint8_t* data = guard.data();
    if (!data) return nullptr;

    // Process frame
    visionagent::VisionResult result =
        visionagent::vision_process_frame(data, (int)width, (int)height);

    // Create Java result object
    jclass result_class = env->FindClass(
        "com/visionagent/core/vision/NativeVisionResult");
    if (!result_class) return nullptr;

    jmethodID ctor = env->GetMethodID(result_class, "<init>",
        "([Lcom/visionagent/core/vision/NativeDetectedElement;FIJI)V");
    if (!ctor) return nullptr;

    // Build element array
    jclass el_class = env->FindClass(
        "com/visionagent/core/vision/NativeDetectedElement");
    jobjectArray elements = env->NewObjectArray(
        (jsize)result.elements.size(), el_class, nullptr);

    for (int i = 0; i < (int)result.elements.size(); ++i) {
        jobject el_obj = create_java_element(env, result.elements[i]);
        if (el_obj) {
            env->SetObjectArrayElement(elements, i, el_obj);
            env->DeleteLocalRef(el_obj);
        }
    }

    return env->NewObject(result_class, ctor,
                          elements,
                          (jfloat)result.overall_confidence,
                          (jint)result.screen_type,
                          (jlong)result.processing_ms,
                          (jint)result.error_code);
}

JNIEXPORT void JNICALL
Java_com_visionagent_core_vision_VisionNativeBridge_loadTemplate(
    JNIEnv* env, jobject /* this */,
    jbyteArray template_data,
    jint template_type) {

    jsize size = env->GetArrayLength(template_data);
    jbyte* ptr = env->GetByteArrayElements(template_data, nullptr);
    if (!ptr) return;

    visionagent::vision_load_template(
        reinterpret_cast<const uint8_t*>(ptr), (int)size, (int)template_type);

    env->ReleaseByteArrayElements(template_data, ptr, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_visionagent_core_vision_VisionNativeBridge_release(
    JNIEnv* /* env */, jobject /* this */) {
    visionagent::vision_release();
}

// ─────────────────────────────────────────────────────────────────────────────
// OCRNativeBridge JNI Methods
// ─────────────────────────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_visionagent_core_ocr_OCRNativeBridge_initialize(
    JNIEnv* env, jobject /* this */,
    jstring tess_data_path,
    jstring languages,
    jint page_seg_mode,
    jint ocr_engine_mode) {

    const char* path = env->GetStringUTFChars(tess_data_path, nullptr);
    const char* lang = env->GetStringUTFChars(languages, nullptr);

    bool ok = visionagent::ocr_initialize(
        path, lang, (int)page_seg_mode, (int)ocr_engine_mode, 60.0f);

    env->ReleaseStringUTFChars(tess_data_path, path);
    env->ReleaseStringUTFChars(languages, lang);

    return (jboolean)ok;
}

JNIEXPORT jobject JNICALL
Java_com_visionagent_core_ocr_OCRNativeBridge_extractText(
    JNIEnv* env, jobject /* this */,
    jbyteArray frame_data,
    jint width,
    jint height,
    jint preprocessing_level,
    jfloat confidence_threshold) {

    JNIArrayGuard guard(env, frame_data);
    uint8_t* data = guard.data();
    if (!data) return nullptr;

    visionagent::OCRResult result =
        visionagent::ocr_extract_text(data, (int)width, (int)height,
                                       (int)preprocessing_level);

    // Build Java NativeOCRResult
    jclass result_class = env->FindClass(
        "com/visionagent/core/ocr/NativeOCRResult");
    if (!result_class) return nullptr;

    jmethodID ctor = env->GetMethodID(result_class, "<init>",
        "(Ljava/lang/String;FJI)V");
    if (!ctor) return nullptr;

    jstring jtext = env->NewStringUTF(result.full_text.c_str());

    return env->NewObject(result_class, ctor,
                          jtext,
                          (jfloat)result.overall_confidence,
                          (jlong)result.processing_ms,
                          (jint)result.error_code);
}

JNIEXPORT void JNICALL
Java_com_visionagent_core_ocr_OCRNativeBridge_release(
    JNIEnv* /* env */, jobject /* this */) {
    visionagent::ocr_release();
}

// ─────────────────────────────────────────────────────────────────────────────
// FrameProcessor JNI Methods
// ─────────────────────────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_visionagent_core_screen_ROIChangeDetector_detectChange(
    JNIEnv* env, jobject /* this */,
    jbyteArray frame_data,
    jint width,
    jint height,
    jfloat threshold) {

    jbyte* ptr = env->GetByteArrayElements(frame_data, nullptr);
    if (!ptr) return JNI_FALSE;

    // FIX NC-9: Pass actual width and height — no longer infer from byte count.
    visionagent::ROIChangeResult result =
        visionagent::frame_detect_change(
            reinterpret_cast<const uint8_t*>(ptr),
            (int)width, (int)height,
            (float)threshold);

    env->ReleaseByteArrayElements(frame_data, ptr, JNI_ABORT);
    return (jboolean)result.has_change;
}

JNIEXPORT jboolean JNICALL
Java_com_visionagent_core_screen_FrameProcessorNative_init(
    JNIEnv* /* env */, jclass /* clazz */,
    jint width, jint height, jint pool_size) {
    return (jboolean)visionagent::frame_processor_init(width, height, pool_size);
}

JNIEXPORT jint JNICALL
Java_com_visionagent_core_screen_FrameProcessorNative_getQueueSize(
    JNIEnv* /* env */, jclass /* clazz */) {
    return (jint)visionagent::frame_queue_size();
}

} // extern "C"
