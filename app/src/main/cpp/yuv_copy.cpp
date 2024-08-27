#include <jni.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "YuvUtils"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT void JNICALL
Java_com_qdev_singlesurfacedualquality_utils_YuvUtils_copyYUV(JNIEnv *env, jobject thiz, jobject srcImage, jobject dstImage) {
    jclass srcImageClass = env->GetObjectClass(srcImage);
    jclass destImageClass = env->GetObjectClass(dstImage);
    jmethodID getSrcPlanesMethod = env->GetMethodID(srcImageClass, "getPlanes", "()[Landroid/media/Image$Plane;");
    jmethodID getDestPlanesMethod = env->GetMethodID(destImageClass, "getPlanes", "()[Landroid/media/Image$Plane;");

    jobjectArray srcPlanes = (jobjectArray) env->CallObjectMethod(srcImage, getSrcPlanesMethod);
    jobjectArray dstPlanes = (jobjectArray) env->CallObjectMethod(dstImage, getDestPlanesMethod);

    jclass planeClass = env->FindClass("android/media/Image$Plane");
    jmethodID getBufferMethod = env->GetMethodID(planeClass, "getBuffer", "()Ljava/nio/ByteBuffer;");
    jmethodID getRowStrideMethod = env->GetMethodID(planeClass, "getRowStride", "()I");
    jmethodID getPixelStrideMethod = env->GetMethodID(planeClass, "getPixelStride", "()I");

    int srcWidth = env->CallIntMethod(srcImage, env->GetMethodID(srcImageClass, "getWidth", "()I"));
    int srcHeight = env->CallIntMethod(srcImage, env->GetMethodID(srcImageClass, "getHeight", "()I"));

    for (int i = 0; i < 3; i++) {
        jobject srcPlane = env->GetObjectArrayElement(srcPlanes, i);
        jobject dstPlane = env->GetObjectArrayElement(dstPlanes, i);

        jobject srcBuffer = env->CallObjectMethod(srcPlane, getBufferMethod);
        jobject dstBuffer = env->CallObjectMethod(dstPlane, getBufferMethod);

        int srcRowStride = env->CallIntMethod(srcPlane, getRowStrideMethod);
        int dstRowStride = env->CallIntMethod(dstPlane, getRowStrideMethod);

        int srcPixelStride = env->CallIntMethod(srcPlane, getPixelStrideMethod);
        int dstPixelStride = env->CallIntMethod(dstPlane, getPixelStrideMethod);

        uint8_t *srcData = (uint8_t *) env->GetDirectBufferAddress(srcBuffer);
        uint8_t *dstData = (uint8_t *) env->GetDirectBufferAddress(dstBuffer);

        if (srcData == nullptr || dstData == nullptr) {
            LOGE("Failed to get direct buffer address.");
            return;
        }

        int planeWidth = (i == 0) ? srcWidth : srcWidth / 2;
        int planeHeight = (i == 0) ? srcHeight : srcHeight / 2;

        for (int row = 0; row < planeHeight; row++) {
            uint8_t *srcRow = srcData + row * srcRowStride;
            uint8_t *dstRow = dstData + row * dstRowStride;

            if (srcPixelStride == 1 && dstPixelStride == 1) {
                memcpy(dstRow, srcRow, planeWidth);
            } else {
                for (int col = 0; col < planeWidth; col++) {
                    dstRow[col * dstPixelStride] = srcRow[col * srcPixelStride];
                }
            }
        }
    }
}
