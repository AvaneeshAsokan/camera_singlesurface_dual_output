#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <vector>
#include <queue>
#include <cstdint>
#include <mutex>
#include <thread>

#include <android/bitmap.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>


#define LOG_TAG "YuvUtils"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


std::mutex copyMutex;

struct YUVBuffer {
    std::vector<uint8_t> byteBuffer; // Stores the byte buffer data
    int pixelStride;                 // Pixel stride for this buffer
    int rowStride;                   // Row stride for this buffer

    YUVBuffer(int bufferSize, int pStride, int rStride)
            : byteBuffer(bufferSize), pixelStride(pStride), rowStride(rStride) {}
};

struct YUVImage {
    std::vector<YUVBuffer> buffers;  // Array of YUVBuffer objects

    YUVImage(int numBuffers) {
        buffers.reserve(numBuffers); // Preallocate space for the buffers
    }

    void addBuffer(int bufferSize, int pixelStride, int rowStride) {
        buffers.emplace_back(bufferSize, pixelStride, rowStride);
    }
};

JavaVM *gJvm = nullptr; // Store the JavaVM reference
std::queue<YUVImage> yuvImageQueue; // Queue to store YUVImage objects
std::mutex queueMutex;

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


/*extern "C"
JNIEXPORT void JNICALL
Java_com_qdev_singlesurfacedualquality_utils_YuvUtils_addToNativeQueue(
        JNIEnv* env,
        jobject *//* this *//*,
        jbyteArray yData,
        jbyteArray uData,
        jbyteArray vData,
        jint yRowStride,
        jint uRowStride,
        jint vRowStride,
        jint yPixelStride,
        jint uPixelStride,
        jint vPixelStride) {

    // Lock the mutex
    std::lock_guard<std::mutex> lock(queueMutex);

    // Create a new YUVImage object
    YUVImage image(3);

    // Get the Y data
    jbyte* yPtr = env->GetByteArrayElements(yData, NULL);
    int ySize = env->GetArrayLength(yData);
    image.addBuffer(ySize, yPixelStride, yRowStride);
    std::copy(yPtr, yPtr + ySize, image.buffers[0].byteBuffer.begin());
    env->ReleaseByteArrayElements(yData, yPtr, 0);

    // Get the U data
    jbyte* uPtr = env->GetByteArrayElements(uData, NULL);
    int uSize = env->GetArrayLength(uData);
    image.addBuffer(uSize, uPixelStride, uRowStride);
    std::copy(uPtr, uPtr + uSize, image.buffers[1].byteBuffer.begin());
    env->ReleaseByteArrayElements(uData, uPtr, 0);

    // Get the V data
    jbyte* vPtr = env->GetByteArrayElements(vData, NULL);
    int vSize = env->GetArrayLength(vData);
    image.addBuffer(vSize, vPixelStride, vRowStride);
    std::copy(vPtr, vPtr + vSize, image.buffers[2].byteBuffer.begin());
    env->ReleaseByteArrayElements(vData, vPtr, 0);

    // Add the YUVImage to the queue
    yuvImageQueue.push(image);
    //  log the size of the queue
    LOGI("Queue size: %d", yuvImageQueue.size());
}*/

extern "C"
JNIEXPORT void JNICALL
Java_com_qdev_singlesurfacedualquality_utils_YuvUtils_addToNativeQueue(
        JNIEnv *env,
        jobject /* this */,
        jobject yBuffer,   // ByteBuffer
        jobject uBuffer,   // ByteBuffer
        jobject vBuffer,   // ByteBuffer
        jint yRowStride,
        jint uRowStride,
        jint vRowStride,
        jint yPixelStride,
        jint uPixelStride,
        jint vPixelStride) {

    // Lock the mutex
    std::lock_guard<std::mutex> lock(queueMutex);

    // Create a new YUVImage object
    YUVImage image(3);

    // Create global references for the ByteBuffers
    jobject yGlobalRef = env->NewGlobalRef(yBuffer);
    jobject uGlobalRef = env->NewGlobalRef(uBuffer);
    jobject vGlobalRef = env->NewGlobalRef(vBuffer);

    std::vector<std::thread> threads;

    // Access the Y data
    threads.emplace_back([&, yGlobalRef]() {
        JNIEnv* threadEnv;
        gJvm->AttachCurrentThread(&threadEnv, NULL);
        uint8_t *yPtr = static_cast<uint8_t *>(threadEnv->GetDirectBufferAddress(yGlobalRef));
        jlong ySize = threadEnv->GetDirectBufferCapacity(yGlobalRef);
        image.addBuffer(static_cast<int>(ySize), yPixelStride, yRowStride);
        memcpy(image.buffers[0].byteBuffer.data(), yPtr, ySize);
        gJvm->DetachCurrentThread();
    });

    // Access the U data
    threads.emplace_back([&, uGlobalRef]() {
        JNIEnv* threadEnv;
        gJvm->AttachCurrentThread(&threadEnv, NULL);
        uint8_t *uPtr = static_cast<uint8_t *>(threadEnv->GetDirectBufferAddress(uGlobalRef));
        jlong uSize = threadEnv->GetDirectBufferCapacity(uGlobalRef);
        image.addBuffer(static_cast<int>(uSize), uPixelStride, uRowStride);
        memcpy(image.buffers[1].byteBuffer.data(), uPtr, uSize);
        gJvm->DetachCurrentThread();
    });

    // Access the V data
    threads.emplace_back([&, vGlobalRef]() {
        JNIEnv* threadEnv;
        gJvm->AttachCurrentThread(&threadEnv, NULL);
        uint8_t *vPtr = static_cast<uint8_t *>(threadEnv->GetDirectBufferAddress(vGlobalRef));
        jlong vSize = threadEnv->GetDirectBufferCapacity(vGlobalRef);
        image.addBuffer(static_cast<int>(vSize), vPixelStride, vRowStride);
        memcpy(image.buffers[2].byteBuffer.data(), vPtr, vSize);
        gJvm->DetachCurrentThread();
    });

    for (auto &thread : threads) {
        thread.join();
    }

    // Delete the global references
    env->DeleteGlobalRef(yGlobalRef);
    env->DeleteGlobalRef(uGlobalRef);
    env->DeleteGlobalRef(vGlobalRef);

    // Add the YUVImage to the queue
    yuvImageQueue.push(image);

    // Log the size of the queue
    LOGI("Queue size: %d", yuvImageQueue.size());
}

//  Function to get the front image from the queue and copy it to the destination image
extern "C"
JNIEXPORT void JNICALL
Java_com_qdev_singlesurfacedualquality_utils_YuvUtils_copyFromQueueToImage(JNIEnv *env, jobject thiz, jobject dstImage, jboolean removeFromQueue) {
    // Lock the mutex
    std::lock_guard<std::mutex> lock(queueMutex);

    jclass destImageClass = env->GetObjectClass(dstImage);

    jmethodID getDestPlanesMethod = env->GetMethodID(destImageClass, "getPlanes", "()[Landroid/media/Image$Plane;");
    jobjectArray dstPlanes = (jobjectArray) env->CallObjectMethod(dstImage, getDestPlanesMethod);

    jclass planeClass = env->FindClass("android/media/Image$Plane");
    jmethodID getBufferMethod = env->GetMethodID(planeClass, "getBuffer", "()Ljava/nio/ByteBuffer;");
    jmethodID getRowStrideMethod = env->GetMethodID(planeClass, "getRowStride", "()I");
    jmethodID getPixelStrideMethod = env->GetMethodID(planeClass, "getPixelStride", "()I");

    int srcWidth = env->CallIntMethod(dstImage, env->GetMethodID(destImageClass, "getWidth", "()I"));
    int srcHeight = env->CallIntMethod(dstImage, env->GetMethodID(destImageClass, "getHeight", "()I"));

    YUVImage image = yuvImageQueue.front();
    if (removeFromQueue) {
        yuvImageQueue.pop();
    }

    for (int i = 0; i < 3; i++) {
        jobject dstPlane = env->GetObjectArrayElement(dstPlanes, i);

        jobject dstBuffer = env->CallObjectMethod(dstPlane, getBufferMethod);
        int dstRowStride = env->CallIntMethod(dstPlane, getRowStrideMethod);
        int dstPixelStride = env->CallIntMethod(dstPlane, getPixelStrideMethod);

        uint8_t *dstData = (uint8_t *) env->GetDirectBufferAddress(dstBuffer);

        if (dstData == nullptr) {
            LOGE("Failed to get direct buffer address.");
            return;
        }

        int planeWidth = (i == 0) ? srcWidth : srcWidth / 2;
        int planeHeight = (i == 0) ? srcHeight : srcHeight / 2;

        for (int row = 0; row < planeHeight; row++) {
            uint8_t *dstRow = dstData + row * dstRowStride;

            if (dstPixelStride == 1) {
                memcpy(dstRow, image.buffers[i].byteBuffer.data() + row * image.buffers[i].rowStride, planeWidth);
            } else {
                for (int col = 0; col < planeWidth; col++) {
                    dstRow[col * dstPixelStride] = image.buffers[i].byteBuffer[row * image.buffers[i].rowStride + col * image.buffers[i].pixelStride];
                }
            }
        }
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_qdev_singlesurfacedualquality_utils_YuvUtils_copyToImage(
        JNIEnv *env,
        jobject /* this */,
        jobject yuv420Object,  // YUV420 Kotlin object
        jobject imageObject    // android.media.Image object
) {
    // Get the YUV420 class and field IDs
    jclass yuv420Class = env->GetObjectClass(yuv420Object);

    // Cache field IDs
    static jfieldID yBufferFieldID = env->GetFieldID(yuv420Class, "yBuffer", "Ljava/nio/ByteBuffer;");
    static jfieldID uBufferFieldID = env->GetFieldID(yuv420Class, "uBuffer", "Ljava/nio/ByteBuffer;");
    static jfieldID vBufferFieldID = env->GetFieldID(yuv420Class, "vBuffer", "Ljava/nio/ByteBuffer;");
    static jfieldID yRowStrideFieldID = env->GetFieldID(yuv420Class, "yRowStride", "I");
    static jfieldID uRowStrideFieldID = env->GetFieldID(yuv420Class, "uRowStride", "I");
    static jfieldID vRowStrideFieldID = env->GetFieldID(yuv420Class, "vRowStride", "I");
    static jfieldID yPixelStrideFieldID = env->GetFieldID(yuv420Class, "yPixelStride", "I");
    static jfieldID uPixelStrideFieldID = env->GetFieldID(yuv420Class, "uPixelStride", "I");
    static jfieldID vPixelStrideFieldID = env->GetFieldID(yuv420Class, "vPixelStride", "I");
    static jfieldID widthFieldID = env->GetFieldID(yuv420Class, "width", "I");
    static jfieldID heightFieldID = env->GetFieldID(yuv420Class, "height", "I");

    // Get the ByteBuffers from the YUV420 object
    jobject yBuffer = env->GetObjectField(yuv420Object, yBufferFieldID);
    jobject uBuffer = env->GetObjectField(yuv420Object, uBufferFieldID);
    jobject vBuffer = env->GetObjectField(yuv420Object, vBufferFieldID);

    // Get the strides and sizes from the YUV420 object
    jint yRowStride = env->GetIntField(yuv420Object, yRowStrideFieldID);
    jint uRowStride = env->GetIntField(yuv420Object, uRowStrideFieldID);
    jint vRowStride = env->GetIntField(yuv420Object, vRowStrideFieldID);
    jint yPixelStride = env->GetIntField(yuv420Object, yPixelStrideFieldID);
    jint uPixelStride = env->GetIntField(yuv420Object, uPixelStrideFieldID);
    jint vPixelStride = env->GetIntField(yuv420Object, vPixelStrideFieldID);
    jint width = env->GetIntField(yuv420Object, widthFieldID);
    jint height = env->GetIntField(yuv420Object, heightFieldID);
    jint chromaHeight = height / 2;

    // Get the direct buffer addresses
    uint8_t *yPtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(yBuffer));
    uint8_t *uPtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(uBuffer));
    uint8_t *vPtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(vBuffer));

    // Get the Image class and its Plane array
    jclass imageClass = env->GetObjectClass(imageObject);
    static jmethodID getPlanesMethodID = env->GetMethodID(imageClass, "getPlanes", "()[Landroid/media/Image$Plane;");
    jobjectArray planes = static_cast<jobjectArray>(env->CallObjectMethod(imageObject, getPlanesMethodID));

    // Get the Y, U, and V planes from the Image object
    jobject yPlane = env->GetObjectArrayElement(planes, 0);
    jobject uPlane = env->GetObjectArrayElement(planes, 1);
    jobject vPlane = env->GetObjectArrayElement(planes, 2);

    // Plane class and field IDs
    jclass planeClass = env->GetObjectClass(yPlane);
    static jmethodID getBufferMethodID = env->GetMethodID(planeClass, "getBuffer", "()Ljava/nio/ByteBuffer;");
    static jmethodID getRowStrideMethodID = env->GetMethodID(planeClass, "getRowStride", "()I");
    static jmethodID getPixelStrideMethodID = env->GetMethodID(planeClass, "getPixelStride", "()I");

    // Get the ByteBuffers from the Image.Planes
    jobject yPlaneBuffer = env->CallObjectMethod(yPlane, getBufferMethodID);
    jobject uPlaneBuffer = env->CallObjectMethod(uPlane, getBufferMethodID);
    jobject vPlaneBuffer = env->CallObjectMethod(vPlane, getBufferMethodID);

    uint8_t *yPlanePtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(yPlaneBuffer));
    uint8_t *uPlanePtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(uPlaneBuffer));
    uint8_t *vPlanePtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(vPlaneBuffer));

    jint yPlaneRowStride = env->CallIntMethod(yPlane, getRowStrideMethodID);
    jint uPlaneRowStride = env->CallIntMethod(uPlane, getRowStrideMethodID);
    jint vPlaneRowStride = env->CallIntMethod(vPlane, getRowStrideMethodID);
    jint yPlanePixelStride = env->CallIntMethod(yPlane, getPixelStrideMethodID);
    jint uPlanePixelStride = env->CallIntMethod(uPlane, getPixelStrideMethodID);
    jint vPlanePixelStride = env->CallIntMethod(vPlane, getPixelStrideMethodID);

    // Define thread functions for copying data
    auto copyYPlane = [&]() {
        std::lock_guard<std::mutex> guard(copyMutex);
        if (yPixelStride == 1 && yRowStride == yPlaneRowStride && yPlanePixelStride == 1) {
            memcpy(yPlanePtr, yPtr, height * yRowStride);
        } else {
            for (int i = 0; i < height; ++i) {
                uint8_t *src = yPtr + i * yRowStride;
                uint8_t *dst = yPlanePtr + i * yPlaneRowStride;
                if (yPixelStride == 1 && yPlanePixelStride == 1) {
                    memcpy(dst, src, width);
                } else {
                    for (int j = 0; j < width; ++j) {
                        dst[j * yPlanePixelStride] = src[j * yPixelStride];
                    }
                }
            }
        }
    };

    auto copyUPlane = [&]() {
        std::lock_guard<std::mutex> guard(copyMutex);
        if (uPixelStride == 1 && uRowStride == uPlaneRowStride && uPlanePixelStride == 1) {
            memcpy(uPlanePtr, uPtr, chromaHeight * uRowStride);
        } else {
            for (int i = 0; i < chromaHeight; ++i) {
                uint8_t *src = uPtr + i * uRowStride;
                uint8_t *dst = uPlanePtr + i * uPlaneRowStride;
                if (uPixelStride == 1 && uPlanePixelStride == 1) {
                    memcpy(dst, src, width / 2);
                } else {
                    for (int j = 0; j < width / 2; ++j) {
                        dst[j * uPlanePixelStride] = src[j * uPixelStride];
                    }
                }
            }
        }
    };

    auto copyVPlane = [&]() {
        std::lock_guard<std::mutex> guard(copyMutex);
        if (vPixelStride == 1 && vRowStride == vPlaneRowStride && vPlanePixelStride == 1) {
            memcpy(vPlanePtr, vPtr, chromaHeight * vRowStride);
        } else {
            for (int i = 0; i < chromaHeight; ++i) {
                uint8_t *src = vPtr + i * vRowStride;
                uint8_t *dst = vPlanePtr + i * vPlaneRowStride;
                if (vPixelStride == 1 && vPlanePixelStride == 1) {
                    memcpy(dst, src, width / 2);
                } else {
                    for (int j = 0; j < width / 2; ++j) {
                        dst[j * vPlanePixelStride] = src[j * vPixelStride];
                    }
                }
            }
        }
    };

    // Create and run threads for copying each plane
    std::vector<std::thread> threads;
    threads.emplace_back(copyYPlane);
    threads.emplace_back(copyUPlane);
    threads.emplace_back(copyVPlane);

    // Wait for all threads to finish
    for (auto& thread : threads) {
        thread.join();
    }

    // Clean up local references
    env->DeleteLocalRef(yuv420Class);
    env->DeleteLocalRef(imageClass);
    env->DeleteLocalRef(planes);
    env->DeleteLocalRef(yPlane);
    env->DeleteLocalRef(uPlane);
    env->DeleteLocalRef(vPlane);
}

/*extern "C"
JNIEXPORT void JNICALL
Java_com_qdev_singlesurfacedualquality_utils_YuvUtils_copyToImage(
        JNIEnv *env,
        jobject *//* this *//*,
        jobject yuv420Object,  // YUV420 Kotlin object
        jobject imageObject    // android.media.Image object
) {
    // Get the YUV420 class and field IDs
    jclass yuv420Class = env->GetObjectClass(yuv420Object);

    // Cache field IDs
    static jfieldID yBufferFieldID = env->GetFieldID(yuv420Class, "yBuffer", "Ljava/nio/ByteBuffer;");
    static jfieldID uBufferFieldID = env->GetFieldID(yuv420Class, "uBuffer", "Ljava/nio/ByteBuffer;");
    static jfieldID vBufferFieldID = env->GetFieldID(yuv420Class, "vBuffer", "Ljava/nio/ByteBuffer;");
    static jfieldID yRowStrideFieldID = env->GetFieldID(yuv420Class, "yRowStride", "I");
    static jfieldID uRowStrideFieldID = env->GetFieldID(yuv420Class, "uRowStride", "I");
    static jfieldID vRowStrideFieldID = env->GetFieldID(yuv420Class, "vRowStride", "I");
    static jfieldID yPixelStrideFieldID = env->GetFieldID(yuv420Class, "yPixelStride", "I");
    static jfieldID uPixelStrideFieldID = env->GetFieldID(yuv420Class, "uPixelStride", "I");
    static jfieldID vPixelStrideFieldID = env->GetFieldID(yuv420Class, "vPixelStride", "I");
    static jfieldID widthFieldID = env->GetFieldID(yuv420Class, "width", "I");
    static jfieldID heightFieldID = env->GetFieldID(yuv420Class, "height", "I");

    // Get the ByteBuffers from the YUV420 object
    jobject yBuffer = env->GetObjectField(yuv420Object, yBufferFieldID);
    jobject uBuffer = env->GetObjectField(yuv420Object, uBufferFieldID);
    jobject vBuffer = env->GetObjectField(yuv420Object, vBufferFieldID);

    // Get the strides and sizes from the YUV420 object
    jint yRowStride = env->GetIntField(yuv420Object, yRowStrideFieldID);
    jint uRowStride = env->GetIntField(yuv420Object, uRowStrideFieldID);
    jint vRowStride = env->GetIntField(yuv420Object, vRowStrideFieldID);
    jint yPixelStride = env->GetIntField(yuv420Object, yPixelStrideFieldID);
    jint uPixelStride = env->GetIntField(yuv420Object, uPixelStrideFieldID);
    jint vPixelStride = env->GetIntField(yuv420Object, vPixelStrideFieldID);
    jint width = env->GetIntField(yuv420Object, widthFieldID);
    jint height = env->GetIntField(yuv420Object, heightFieldID);
    jint chromaHeight = height / 2;

    // Get the direct buffer addresses
    uint8_t *yPtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(yBuffer));
    uint8_t *uPtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(uBuffer));
    uint8_t *vPtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(vBuffer));

    // Get the Image class and its Plane array
    jclass imageClass = env->GetObjectClass(imageObject);
    static jmethodID getPlanesMethodID = env->GetMethodID(imageClass, "getPlanes", "()[Landroid/media/Image$Plane;");
    jobjectArray planes = static_cast<jobjectArray>(env->CallObjectMethod(imageObject, getPlanesMethodID));

    // Get the Y, U, and V planes from the Image object
    jobject yPlane = env->GetObjectArrayElement(planes, 0);
    jobject uPlane = env->GetObjectArrayElement(planes, 1);
    jobject vPlane = env->GetObjectArrayElement(planes, 2);

    // Plane class and field IDs
    jclass planeClass = env->GetObjectClass(yPlane);
    static jmethodID getBufferMethodID = env->GetMethodID(planeClass, "getBuffer", "()Ljava/nio/ByteBuffer;");
    static jmethodID getRowStrideMethodID = env->GetMethodID(planeClass, "getRowStride", "()I");
    static jmethodID getPixelStrideMethodID = env->GetMethodID(planeClass, "getPixelStride", "()I");

    // Get the ByteBuffers from the Image.Planes
    jobject yPlaneBuffer = env->CallObjectMethod(yPlane, getBufferMethodID);
    jobject uPlaneBuffer = env->CallObjectMethod(uPlane, getBufferMethodID);
    jobject vPlaneBuffer = env->CallObjectMethod(vPlane, getBufferMethodID);

    uint8_t *yPlanePtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(yPlaneBuffer));
    uint8_t *uPlanePtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(uPlaneBuffer));
    uint8_t *vPlanePtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(vPlaneBuffer));

    jint yPlaneRowStride = env->CallIntMethod(yPlane, getRowStrideMethodID);
    jint uPlaneRowStride = env->CallIntMethod(uPlane, getRowStrideMethodID);
    jint vPlaneRowStride = env->CallIntMethod(vPlane, getRowStrideMethodID);
    jint yPlanePixelStride = env->CallIntMethod(yPlane, getPixelStrideMethodID);
    jint uPlanePixelStride = env->CallIntMethod(uPlane, getPixelStrideMethodID);
    jint vPlanePixelStride = env->CallIntMethod(vPlane, getPixelStrideMethodID);

    // Copy the Y data
    if (yPixelStride == 1 && yRowStride == yPlaneRowStride && yPlanePixelStride == 1) {
        memcpy(yPlanePtr, yPtr, height * yRowStride);
    } else {
        for (int i = 0; i < height; ++i) {
            uint8_t *src = yPtr + i * yRowStride;
            uint8_t *dst = yPlanePtr + i * yPlaneRowStride;
            if (yPixelStride == 1 && yPlanePixelStride == 1) {
                memcpy(dst, src, width);
            } else {
                for (int j = 0; j < width; ++j) {
                    dst[j * yPlanePixelStride] = src[j * yPixelStride];
                }
            }
        }
    }

    // Copy the U data
    if (uPixelStride == 1 && uRowStride == uPlaneRowStride && uPlanePixelStride == 1) {
        memcpy(uPlanePtr, uPtr, chromaHeight * uRowStride);
    } else {
        for (int i = 0; i < chromaHeight; ++i) {
            uint8_t *src = uPtr + i * uRowStride;
            uint8_t *dst = uPlanePtr + i * uPlaneRowStride;
            if (uPixelStride == 1 && uPlanePixelStride == 1) {
                memcpy(dst, src, width / 2);
            } else {
                for (int j = 0; j < width / 2; ++j) {
                    dst[j * uPlanePixelStride] = src[j * uPixelStride];
                }
            }
        }
    }

    // Copy the V data
    if (vPixelStride == 1 && vRowStride == vPlaneRowStride && vPlanePixelStride == 1) {
        memcpy(vPlanePtr, vPtr, chromaHeight * vRowStride);
    } else {
        for (int i = 0; i < chromaHeight; ++i) {
            uint8_t *src = vPtr + i * vRowStride;
            uint8_t *dst = vPlanePtr + i * vPlaneRowStride;
            if (vPixelStride == 1 && vPlanePixelStride == 1) {
                memcpy(dst, src, width / 2);
            } else {
                for (int j = 0; j < width / 2; ++j) {
                    dst[j * vPlanePixelStride] = src[j * vPixelStride];
                }
            }
        }
    }
}*/


extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_qdev_singlesurfacedualquality_utils_YuvUtils_copyYUVBuffer(JNIEnv *env, jobject thiz, jobject image) {
    // Get the Image class and methods to access the planes
    jclass imageClass = env->GetObjectClass(image);
    jmethodID getPlanesMethod = env->GetMethodID(imageClass, "getPlanes", "()[Landroid/media/Image$Plane;");
    jobjectArray planesArray = (jobjectArray)env->CallObjectMethod(image, getPlanesMethod);

    // Get the Plane class and methods
    jclass planeClass = env->FindClass("android/media/Image$Plane");
    jmethodID getBufferMethod = env->GetMethodID(planeClass, "getBuffer", "()Ljava/nio/ByteBuffer;");
    jmethodID getRowStrideMethod = env->GetMethodID(planeClass, "getRowStride", "()I");

    // Get the Y plane
    jobject yPlane = env->GetObjectArrayElement(planesArray, 0);
    jobject yBufferObj = env->CallObjectMethod(yPlane, getBufferMethod);
    jint yRowStride = env->CallIntMethod(yPlane, getRowStrideMethod);
    jbyte* yBuffer = (jbyte*)env->GetDirectBufferAddress(yBufferObj);
    jint ySize = env->GetDirectBufferCapacity(yBufferObj);

    // Get the U plane
    jobject uPlane = env->GetObjectArrayElement(planesArray, 1);
    jobject uBufferObj = env->CallObjectMethod(uPlane, getBufferMethod);
    jint uRowStride = env->CallIntMethod(uPlane, getRowStrideMethod);
    jbyte* uBuffer = (jbyte*)env->GetDirectBufferAddress(uBufferObj);
    jint uSize = env->GetDirectBufferCapacity(uBufferObj);

    // Get the V plane
    jobject vPlane = env->GetObjectArrayElement(planesArray, 2);
    jobject vBufferObj = env->CallObjectMethod(vPlane, getBufferMethod);
    jint vRowStride = env->CallIntMethod(vPlane, getRowStrideMethod);
    jbyte* vBuffer = (jbyte*)env->GetDirectBufferAddress(vBufferObj);
    jint vSize = env->GetDirectBufferCapacity(vBufferObj);

    // Allocate a byte array to hold the YUV data
    jbyteArray yuvData = env->NewByteArray(ySize + uSize + vSize);
    jbyte* yuvBuffer = env->GetByteArrayElements(yuvData, nullptr);

    // Copy the Y plane data
    memcpy(yuvBuffer, yBuffer, ySize);

    // Copy the U plane data
    memcpy(yuvBuffer + ySize, uBuffer, uSize);

    // Copy the V plane data
    memcpy(yuvBuffer + ySize + uSize, vBuffer, vSize);

    // Release the byte array back to the JVM
    env->ReleaseByteArrayElements(yuvData, yuvBuffer, 0);

    // Return the byte array containing the YUV data
    return yuvData;
}


// JNI_OnLoad function to store the JavaVM reference
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /* reserved */) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}