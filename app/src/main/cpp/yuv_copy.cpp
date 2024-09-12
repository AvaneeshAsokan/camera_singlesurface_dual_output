#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <vector>
#include <queue>
#include <cstdint>
#include <mutex>
#include <thread>
#include <condition_variable>
#include <future>

#include <android/bitmap.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <arm_neon.h>


#define LOG_TAG "YuvUtils"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


std::mutex copyMutex;

struct YUVImagePlane {
    std::vector<uint8_t> byteBuffer;
    int pixelStride;
    int rowStride;

    YUVImagePlane(int size, int pixelStride, int rowStride)
            : byteBuffer(size), pixelStride(pixelStride), rowStride(rowStride) {}
};

class YUV420 {
public:
    int width;
    int height;
    long long timestampUs;
    std::vector<YUVImagePlane> planes;

    YUV420(int width, int height, long long timestampUs)
            : width(width), height(height), timestampUs(timestampUs) {
        // Pre-allocate planes for Y, U, and V
        planes.emplace_back(width * height, 1, width);  // Y plane
        planes.emplace_back(width * height / 2, 2, width);  // U plane
        planes.emplace_back(width * height / 2, 2, width);  // V plane
    }

    void update(int width, int height, long long timestampUs,
                const uint8_t *yData, int yRowStride, int yPixelStride,
                const uint8_t *uData, int uRowStride, int uPixelStride,
                const uint8_t *vData, int vRowStride, int vPixelStride) {
        this->width = width;
        this->height = height;
        this->timestampUs = timestampUs;

        // Update Y plane
        planes[0].rowStride = yRowStride;
        planes[0].pixelStride = yPixelStride;
        memcpy(planes[0].byteBuffer.data(), yData, width * height);

        // Update U plane
        planes[1].rowStride = uRowStride;
        planes[1].pixelStride = uPixelStride;
        memcpy(planes[1].byteBuffer.data(), uData, width * height / 2);

        // Update V plane
        planes[2].rowStride = vRowStride;
        planes[2].pixelStride = vPixelStride;
        memcpy(planes[2].byteBuffer.data(), vData, width * height / 2);
    }
};

class CircularArrayQueue {
private:
    std::vector<YUV420> queue;
    int front;
    int rear;
    int size;
    int capacity;
    std::mutex mutex;
    std::condition_variable notFull, notEmpty;

public:
    CircularArrayQueue(int capacity, int width, int height)
            : queue(capacity, YUV420(width, height, 0)), front(0), rear(-1), size(0), capacity(capacity) {}

    void enqueue(int width, int height, long long timestampUs,
                 const uint8_t *yData, int yRowStride, int yPixelStride,
                 const uint8_t *uData, int uRowStride, int uPixelStride,
                 const uint8_t *vData, int vRowStride, int vPixelStride) {
//        std::unique_lock<std::mutex> lock(mutex);
//        notFull.wait(lock, [this] { return size < capacity; });

        rear = (rear + 1) % capacity;
        queue[rear].update(width, height, timestampUs,
                           yData, yRowStride, yPixelStride,
                           uData, uRowStride, uPixelStride,
                           vData, vRowStride, vPixelStride);
        size++;

//        lock.unlock();
        notEmpty.notify_one();
    }

    YUV420 dequeueCopy() {
        std::unique_lock<std::mutex> lock(mutex);
        notEmpty.wait(lock, [this] { return size > 0; });

        YUV420 item = queue[front];
        front = (front + 1) % capacity;
        size--;

        lock.unlock();
        notFull.notify_one();
        return item;
    }

    YUV420 peekCopy() {
        std::unique_lock<std::mutex> lock(mutex);
        notEmpty.wait(lock, [this] { return size > 0; });

        YUV420 item = queue[front];

        lock.unlock();
        return item;
    }

    YUV420 &dequeue() {
//        std::unique_lock<std::mutex> lock(mutex);
//        notEmpty.wait(lock, [this] { return size > 0; });

        YUV420 &item = queue[front];
        front = (front + 1) % capacity;
        size--;

//        lock.unlock();
//        notFull.notify_one();
        return item;
    }

    YUV420 &peek() {
//        std::unique_lock<std::mutex> lock(mutex);
//        notEmpty.wait(lock, [this] { return size > 0; });

        YUV420 &item = queue[front];

//        lock.unlock();
        return item;
    }

    bool isEmpty() const {
        return size == 0;
    }

    bool isFull() const {
        return size == capacity;
    }

    int getSize() const {
        return size;
    }
};

CircularArrayQueue yuvQueue(5, 3840, 2160 /*1920, 1080*/);

extern "C"
JNIEXPORT void JNICALL
Java_com_qdev_singlesurfacedualquality_utils_YuvUtils_addToNativeQueue(JNIEnv *env, jobject thiz, jobject y_data, jobject u_data, jobject v_data,
                                                                       jint y_row_stride, jint u_row_stride, jint v_row_stride, jint y_pixel_stride,
                                                                       jint u_pixel_stride, jint v_pixel_stride, jlong timestamp_us) {
    // TODO: implement addToNativeQueue()
    yuvQueue.enqueue(3840, 2160 /*1920, 1080*/, timestamp_us,
                     static_cast<const uint8_t *>(env->GetDirectBufferAddress(y_data)), y_row_stride, y_pixel_stride,
                     static_cast<const uint8_t *>(env->GetDirectBufferAddress(u_data)), u_row_stride, u_pixel_stride,
                     static_cast<const uint8_t *>(env->GetDirectBufferAddress(v_data)), v_row_stride, v_pixel_stride);

    LOGI("Native YUV queue size: %d", yuvQueue.getSize());
}

//function to return if the queue is empty
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_qdev_singlesurfacedualquality_utils_YuvUtils_isQueueEmpty(JNIEnv *env, jobject thiz) {
    return yuvQueue.isEmpty();
}

JavaVM *gJvm = nullptr; // Store the JavaVM reference
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

extern "C"
JNIEXPORT void JNICALL
Java_com_qdev_singlesurfacedualquality_utils_YuvUtils_copyToImage2(
        JNIEnv *env,
        jobject /* this */,
        jobject image,  // The Image object from Kotlin
        jboolean removeFromQueue) {

    // Check if the queue is empty
    if (yuvQueue.isEmpty()) {
        LOGI("Queue is empty.");
        return;
    }

    //  time to fetch references
    long long startTime = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();

    // Dequeue the next YUV frame from the circular queue
    YUV420 &yuvFrame = yuvQueue.peek();
    if (removeFromQueue) {
        yuvQueue.dequeue();
    }

    long long dequeueTime = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();

    LOGI("Time to dequeue: %lld", dequeueTime - startTime);


    long long refStartTime = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();

    // Get the Image class and its Plane array
    jclass imageClass = env->GetObjectClass(image);
    static jmethodID getPlanesMethodID = env->GetMethodID(imageClass, "getPlanes", "()[Landroid/media/Image$Plane;");
    jobjectArray planes = static_cast<jobjectArray>(env->CallObjectMethod(image, getPlanesMethodID));

    // Get the Y, U, and V planes from the Image object
    jobject yPlane = env->GetObjectArrayElement(planes, 0);
    jobject uPlane = env->GetObjectArrayElement(planes, 1);
    jobject vPlane = env->GetObjectArrayElement(planes, 2);

    // Plane class and field IDs
    jclass planeClass = env->GetObjectClass(yPlane);
    static jmethodID getBufferMethodID = env->GetMethodID(planeClass, "getBuffer", "()Ljava/nio/ByteBuffer;");
    static jmethodID getRowStrideMethodID = env->GetMethodID(planeClass, "getRowStride", "()I");
    static jmethodID getPixelStrideMethodID = env->GetMethodID(planeClass, "getPixelStride", "()I");
    //  method to get the timestamp
    static jmethodID setTimestampMethodID = env->GetMethodID(imageClass, "setTimestamp", "(J)V");
    static jmethodID getTimestampMethodID = env->GetMethodID(imageClass, "getTimestamp", "()J");

    // Get the ByteBuffers from the Image.Planes
    jobject yPlaneBuffer = env->CallObjectMethod(yPlane, getBufferMethodID);
    jobject uPlaneBuffer = env->CallObjectMethod(uPlane, getBufferMethodID);
    jobject vPlaneBuffer = env->CallObjectMethod(vPlane, getBufferMethodID);

    auto *yPlanePtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(yPlaneBuffer));
    auto *uPlanePtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(uPlaneBuffer));
    auto *vPlanePtr = static_cast<uint8_t *>(env->GetDirectBufferAddress(vPlaneBuffer));

    if (yPlanePtr == nullptr || uPlanePtr == nullptr || vPlanePtr == nullptr) {
        LOGE("Failed to get direct buffer address.");
        return;
    }

    jint yPlaneRowStride = env->CallIntMethod(yPlane, getRowStrideMethodID);
    jint uPlaneRowStride = env->CallIntMethod(uPlane, getRowStrideMethodID);
    jint vPlaneRowStride = env->CallIntMethod(vPlane, getRowStrideMethodID);
    jint yPlanePixelStride = env->CallIntMethod(yPlane, getPixelStrideMethodID);
    jint uPlanePixelStride = env->CallIntMethod(uPlane, getPixelStrideMethodID);
    jint vPlanePixelStride = env->CallIntMethod(vPlane, getPixelStrideMethodID);

    // Get the YUV frame data
    const uint8_t *yPtr = yuvFrame.planes[0].byteBuffer.data();
    const uint8_t *uPtr = yuvFrame.planes[1].byteBuffer.data();
    const uint8_t *vPtr = yuvFrame.planes[2].byteBuffer.data();
    int yRowStride = yuvFrame.planes[0].rowStride;
    int uRowStride = yuvFrame.planes[1].rowStride;
    int vRowStride = yuvFrame.planes[2].rowStride;
    int yPixelStride = yuvFrame.planes[0].pixelStride;
    int uPixelStride = yuvFrame.planes[1].pixelStride;
    int vPixelStride = yuvFrame.planes[2].pixelStride;
    int width = yuvFrame.width;
    int height = yuvFrame.height;
    int chromaHeight = height / 2;


    long long prepWorkTime = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();

    LOGI("Time to do prep work: %lld", prepWorkTime - refStartTime);

    // Define thread functions for copying data
    auto copyYPlane = [&]() {
        if (yPixelStride == 1 && yRowStride == yPlaneRowStride && yPlanePixelStride == 1) {
            memcpy(yPlanePtr, yPtr, height * yRowStride);
        } else {
            for (int row = 0; row < height; row++) {
                memcpy(yPlanePtr + row * yPlaneRowStride, yPtr + row * yRowStride, width);
            }
            /*for (int row = 0; row < height; ++row) {
                uint8_t *src = const_cast<uint8_t *>(yPtr + row * yRowStride);
                uint8_t *dst = yPlanePtr + row * yPlaneRowStride;
                if (yPixelStride == 1 && yPlanePixelStride == 1) {
                    memcpy(dst, src, width);
                } else {
                    for (int col = 0; col < width; ++col) {
                        dst[col * yPlanePixelStride] = src[col * yPixelStride];
                    }
                }
            }*/
        }
    };

    auto copyUPlane = [&]() {
        //  time to copy the U plane
        if (uPixelStride == 1 && uRowStride == uPlaneRowStride && uPlanePixelStride == 1) {
            memcpy(uPlanePtr, uPtr, chromaHeight * uRowStride);
        } else {
            for (int row = 0; row < chromaHeight; row++) {
                memcpy(uPlanePtr + row * uPlaneRowStride, uPtr + row * uRowStride, width);
            }
            /*for (int row = 0; row < chromaHeight; ++row) {
                uint8_t *src = const_cast<uint8_t *>(uPtr + row * uRowStride);
                uint8_t *dst = uPlanePtr + row * uPlaneRowStride;
                if (uPixelStride == 1 && uPlanePixelStride == 1) {
                    memcpy(dst, src, width / 2);
                } else {
                    for (int col = 0; col < width / 2; ++col) {
                        dst[col * uPlanePixelStride] = src[col * uPixelStride];
                    }
                }
            }*/
        }
    };

    auto copyVPlane = [&]() {
        //  time to copy the V plane
        if (vPixelStride == 1 && vRowStride == vPlaneRowStride && vPlanePixelStride == 1) {
            memcpy(vPlanePtr, vPtr, chromaHeight * vRowStride);
        } else {
            for (int row = 0; row < chromaHeight; row++) {
                memcpy(vPlanePtr + row * vPlaneRowStride, vPtr + row * vRowStride, width);
            }
            /*for (int row = 0; row < chromaHeight; ++row) {
                uint8_t *src = const_cast<uint8_t *>(vPtr + row * vRowStride);
                uint8_t *dst = vPlanePtr + row * vPlaneRowStride;
                if (vPixelStride == 1 && vPlanePixelStride == 1) {
                    memcpy(dst, src, width / 2);
                } else {
                    for (int col = 0; col < width / 2; ++col) {
                        dst[col * vPlanePixelStride] = src[col * vPixelStride];
                    }
                }
            }*/
        }
    };

    //  time to trigger copy
    long long copyTriggerTime = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();

    LOGI("Time to trigger the copy: %lld", copyTriggerTime - refStartTime);

    // Create and run threads for copying each plane
    std::vector<std::thread> threads;
    threads.emplace_back(copyYPlane);
    threads.emplace_back(copyUPlane);
    threads.emplace_back(copyVPlane);

    // Wait for all threads to finish
    for (auto &thread: threads) {
        thread.join();
    }

    // Clean up local references
    env->DeleteLocalRef(yPlane);
    env->DeleteLocalRef(uPlane);
    env->DeleteLocalRef(vPlane);
    env->DeleteLocalRef(planes);
    env->DeleteLocalRef(imageClass);

    // Log to verify successful copying
    LOGI("Successfully copied YUV data to Image object.");

    //  time to complete the copy
    long long endTime = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();

    LOGI("Total Time to do copy: %lld", endTime - startTime);

    return;
}

// Helper function to split the plane copy into row ranges and run it on separate threads
void copyPlaneInParallel(const uint8_t *src, int srcRowStride, int srcPixelStride,
                         uint8_t *dst, int dstRowStride, int dstPixelStride,
                         int width, int height) {

    if (srcPixelStride == dstPixelStride && srcRowStride == dstRowStride) {
        // Direct memcpy when strides are the same
        memcpy(dst, src, height * srcRowStride);
    } else {
        // Slow path: copy pixel by pixel
        for (int row = 0; row < height; ++row) {
            for (int col = 0; col < width; ++col) {
                uint8_t *srcPixel = const_cast<uint8_t *>(&src[row * srcRowStride + col * srcPixelStride]);
                uint8_t *dstPixel = &dst[row * dstRowStride + col * dstPixelStride];
                *dstPixel = *srcPixel;
            }
        }
    }
}

void copyPlane(const uint8_t *src, uint8_t *dst, int width, int height, int srcRowStride, int dstRowStride, int srcPixelStride, int dstPixelStride) {
    for (int row = 0; row < height; ++row) {
        for (int col = 0; col < width; ++col) {
            dst[row * dstRowStride + col * dstPixelStride] = src[row * srcRowStride + col * srcPixelStride];
        }
    }
}

void neonCopyPlane(uint8_t *src, uint8_t *dst, int width, int height, int srcRowStride, int dstRowStride) {
    for (int row = 0; row < height; ++row) {
        uint8_t *srcRow = src + row * srcRowStride;
        uint8_t *dstRow = dst + row * dstRowStride;

        for (int col = 0; col < width; col += 16) {
            // Load 16 bytes from src
            uint8x16_t data = vld1q_u8(srcRow + col);
            // Store 16 bytes to dst
            vst1q_u8(dstRow + col, data);
        }
    }
}

void multiThreadedCopyYUV420(const uint8_t *ySrc, const uint8_t *uSrc, const uint8_t *vSrc,
                             uint8_t *yDst, uint8_t *uDst, uint8_t *vDst,
                             int width, int height, int ySrcRowStride, int yDstRowStride,
                             int uSrcRowStride, int uDstRowStride, int vSrcRowStride, int vDstRowStride,
                             int numThreads) {
    std::vector<std::thread> threads;

    // Process Y plane in multiple threads
    int yHeight = height;
    int rowsPerThread = yHeight / numThreads;
    int extraRows = yHeight % numThreads;
    int startRow = 0;

    for (int i = 0; i < numThreads; ++i) {
        int rowsToCopy = rowsPerThread + (i < extraRows ? 1 : 0);
        threads.emplace_back([=]() {
            copyPlane(ySrc + startRow * ySrcRowStride, yDst + startRow * yDstRowStride,
                      width, rowsToCopy, ySrcRowStride, yDstRowStride, 1, 1);
        });
        startRow += rowsToCopy;
    }

    // Wait for Y plane threads
    for (auto &thread: threads) {
        thread.join();
    }

    // Process U and V planes (subsampled)
    threads.clear();
    int uvWidth = width / 2;
    int uvHeight = height / 2;

    rowsPerThread = uvHeight / numThreads;
    extraRows = uvHeight % numThreads;
    startRow = 0;

    for (int i = 0; i < numThreads; ++i) {
        int rowsToCopy = rowsPerThread + (i < extraRows ? 1 : 0);
        threads.emplace_back([=]() {
            copyPlane(uSrc + startRow * uSrcRowStride, uDst + startRow * uDstRowStride,
                      uvWidth, rowsToCopy, uSrcRowStride, uDstRowStride, 1, 1);
            copyPlane(vSrc + startRow * vSrcRowStride, vDst + startRow * vDstRowStride,
                      uvWidth, rowsToCopy, vSrcRowStride, vDstRowStride, 1, 1);
        });
        startRow += rowsToCopy;
    }

    // Wait for U/V plane threads
    for (auto &thread: threads) {
        thread.join();
    }
}


extern "C"
JNIEXPORT void JNICALL
Java_com_qdev_singlesurfacedualquality_utils_YuvUtils_copyToImageV2(
        JNIEnv *env,
        jobject /* this */,
        jobject image,  // The Image object from Kotlin
        jboolean removeFromQueue) {
    // Step 1: Initialize the Android Image object
    jclass imageClass = env->GetObjectClass(image);
    jmethodID getPlanesMethod = env->GetMethodID(imageClass, "getPlanes", "()[Landroid/media/Image$Plane;");
    jobjectArray planes = (jobjectArray) env->CallObjectMethod(image, getPlanesMethod);
    int numPlanes = env->GetArrayLength(planes);

    if (numPlanes != 3) {
        // Ensure the Image object has three planes (YUV 420 format)
        return;
    }

    // Step 2: Dequeue or Peek frame from CircularArrayQueue
    YUV420 &frame = removeFromQueue ? yuvQueue.dequeue() : yuvQueue.peek();

    // Step 3: Copy YUV data from frame to Image object
    for (int i = 0; i < numPlanes; i++) {
        jobject planeObj = env->GetObjectArrayElement(planes, i);
        jclass planeClass = env->GetObjectClass(planeObj);

        // Get the ByteBuffer for each plane
        jmethodID getBufferMethod = env->GetMethodID(planeClass, "getBuffer", "()Ljava/nio/ByteBuffer;");
        jobject bufferObj = env->CallObjectMethod(planeObj, getBufferMethod);
        uint8_t *destBuffer = (uint8_t *) env->GetDirectBufferAddress(bufferObj);

        // Get the pixel and row strides for each plane
        jmethodID getRowStrideMethod = env->GetMethodID(planeClass, "getRowStride", "()I");
        jint destRowStride = env->CallIntMethod(planeObj, getRowStrideMethod);

        jmethodID getPixelStrideMethod = env->GetMethodID(planeClass, "getPixelStride", "()I");
        jint destPixelStride = env->CallIntMethod(planeObj, getPixelStrideMethod);

        // Source YUVImagePlane data
        YUVImagePlane &srcPlane = frame.planes[i];
        int srcRowStride = srcPlane.rowStride;
        int srcPixelStride = srcPlane.pixelStride;
        uint8_t *srcBuffer = srcPlane.byteBuffer.data();

        // Step 4: Copy the data using NEON intrinsics
        int height = (i == 0) ? frame.height : frame.height / 2;
        int width = (i == 0) ? frame.width : frame.width / 2;

        for (int y = 0; y < height; ++y) {
            uint8_t *srcRow = srcBuffer + y * srcRowStride;
            uint8_t *destRow = destBuffer + y * destRowStride;

            // Check alignment
            if ((((uintptr_t) srcRow % 16) == 0) && (((uintptr_t) destRow % 16) == 0) &&
                srcPixelStride == destPixelStride && srcPixelStride > 1) {
                // Strides are equal but greater than 1 and both source and destination are aligned
                int stride = srcPixelStride;
                int x = 0;

                // Use NEON to process pixels in chunks of 16 bytes
                for (; x <= (width - 16); x += 16) {
                    // Load pixels using stride
                    uint8x16x2_t srcData = vld2q_u8(srcRow + x * stride);

                    // Store pixels using stride
                    vst2q_u8(destRow + x * stride, srcData);
                }

                // Handle any remaining pixels after processing in chunks of 16
                for (; x < width; ++x) {
                    uint8_t pixelValue = *(srcRow + x * stride);
                    *(destRow + x * stride) = pixelValue;
                }
            } else if (srcPixelStride == 1 && destPixelStride == 1) {
                // Both strides are 1, use memcpy for the entire row
                memcpy(destRow, srcRow, width);
            } else {
                // Fallback to manual copy if pixel strides are different or alignment is incorrect
                for (int x = 0; x < width; ++x) {
                    uint8_t pixelValue = *(srcRow + x * srcPixelStride);
                    *(destRow + x * destPixelStride) = pixelValue;
                }
            }
        }
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_qdev_singlesurfacedualquality_utils_YuvUtils_copyToImageV3(
        JNIEnv *env,
        jobject /* this */,
        jobject image,  // The Image object from Kotlin
        jboolean removeFromQueue) {
    // Step 1: Initialize the Android Image object
    jclass imageClass = env->GetObjectClass(image);
    jmethodID getPlanesMethod = env->GetMethodID(imageClass, "getPlanes", "()[Landroid/media/Image$Plane;");
    jobjectArray planes = (jobjectArray) env->CallObjectMethod(image, getPlanesMethod);
    int numPlanes = env->GetArrayLength(planes);

    if (numPlanes != 3) {
        // Ensure the Image object has three planes (YUV 420 format)
        return;
    }

    // Step 2: Dequeue or Peek frame from CircularArrayQueue
    YUV420 &frame = removeFromQueue ? yuvQueue.dequeue() : yuvQueue.peek();

    // Step 3: Copy YUV data from frame to Image object
    for (int i = 0; i < numPlanes; i++) {
        jobject planeObj = env->GetObjectArrayElement(planes, i);
        jclass planeClass = env->GetObjectClass(planeObj);

        // Get the ByteBuffer for each plane
        jmethodID getBufferMethod = env->GetMethodID(planeClass, "getBuffer", "()Ljava/nio/ByteBuffer;");
        jobject bufferObj = env->CallObjectMethod(planeObj, getBufferMethod);
        uint8_t *destBuffer = (uint8_t *) env->GetDirectBufferAddress(bufferObj);

        if (destBuffer == nullptr) {
            // Direct buffer address is not valid; return or handle the error
            return;
        }

        // Get the pixel and row strides for each plane
        jmethodID getRowStrideMethod = env->GetMethodID(planeClass, "getRowStride", "()I");
        jint destRowStride = env->CallIntMethod(planeObj, getRowStrideMethod);

        jmethodID getPixelStrideMethod = env->GetMethodID(planeClass, "getPixelStride", "()I");
        jint destPixelStride = env->CallIntMethod(planeObj, getPixelStrideMethod);

        // Source YUVImagePlane data
        YUVImagePlane &srcPlane = frame.planes[i];
        int srcRowStride = srcPlane.rowStride;
        int srcPixelStride = srcPlane.pixelStride;
        uint8_t *srcBuffer = srcPlane.byteBuffer.data();

        if (srcBuffer == nullptr) {
            // Source buffer is not valid; return or handle the error
            return;
        }

        // Step 4: Copy the data using NEON intrinsics
        int height = (i == 0) ? frame.height : frame.height / 2;
        int width = (i == 0) ? frame.width : frame.width / 2;

        for (int y = 0; y < height; ++y) {
            uint8_t *srcRow = srcBuffer + y * srcRowStride;
            uint8_t *destRow = destBuffer + y * destRowStride;

            // Ensure not to exceed the buffer boundary
            if ((srcRow >= srcBuffer + (height * srcRowStride)) ||
                (destRow >= destBuffer + (height * destRowStride))) {
                // Avoid accessing memory beyond allocated buffers
                return;
            }

            if ((((uintptr_t)srcRow % 16) == 0) && (((uintptr_t)destRow % 16) == 0) &&
                srcPixelStride == destPixelStride && srcPixelStride > 1) {
                // Strides are equal but greater than 1 and both source and destination are aligned
                int stride = srcPixelStride;
                int x = 0;

                // Use NEON to process pixels in chunks of 16 bytes
                for (; x <= (width - 16); x += 16) {
                    // Load pixels using stride
                    uint8x16x2_t srcData = vld2q_u8(srcRow + x * stride);
                    // Store pixels using stride
                    vst2q_u8(destRow + x * stride, srcData);
                }

                // Handle any remaining pixels after processing in chunks of 16
                for (; x < width; ++x) {
                    uint8_t pixelValue = *(srcRow + x * stride);
                    *(destRow + x * stride) = pixelValue;
                }
            } else if (srcPixelStride == 1 && destPixelStride == 1) {
                // Both strides are 1, use memcpy for the entire row
                // Ensure that memcpy does not copy more than available space
                int copySize = std::min(width, destRowStride);
                memcpy(destRow, srcRow, copySize);
            } else {
                // Fallback to manual copy if pixel strides are different or alignment is incorrect
                for (int x = 0; x < width; ++x) {
                    uint8_t pixelValue = *(srcRow + x * srcPixelStride);
                    *(destRow + x * destPixelStride) = pixelValue;
                }
            }
        }
    }
}


/**
 * Function to copy YUV data from a YUV420 object to an Image object
 */
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
    for (auto &thread: threads) {
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

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_qdev_singlesurfacedualquality_utils_YuvUtils_copyYUVBuffer(JNIEnv *env, jobject thiz, jobject image) {
    // Get the Image class and methods to access the planes
    jclass imageClass = env->GetObjectClass(image);
    jmethodID getPlanesMethod = env->GetMethodID(imageClass, "getPlanes", "()[Landroid/media/Image$Plane;");
    jobjectArray planesArray = (jobjectArray) env->CallObjectMethod(image, getPlanesMethod);

    // Get the Plane class and methods
    jclass planeClass = env->FindClass("android/media/Image$Plane");
    jmethodID getBufferMethod = env->GetMethodID(planeClass, "getBuffer", "()Ljava/nio/ByteBuffer;");
    jmethodID getRowStrideMethod = env->GetMethodID(planeClass, "getRowStride", "()I");

    // Get the Y plane
    jobject yPlane = env->GetObjectArrayElement(planesArray, 0);
    jobject yBufferObj = env->CallObjectMethod(yPlane, getBufferMethod);
    jint yRowStride = env->CallIntMethod(yPlane, getRowStrideMethod);
    jbyte *yBuffer = (jbyte *) env->GetDirectBufferAddress(yBufferObj);
    jint ySize = env->GetDirectBufferCapacity(yBufferObj);

    // Get the U plane
    jobject uPlane = env->GetObjectArrayElement(planesArray, 1);
    jobject uBufferObj = env->CallObjectMethod(uPlane, getBufferMethod);
    jint uRowStride = env->CallIntMethod(uPlane, getRowStrideMethod);
    jbyte *uBuffer = (jbyte *) env->GetDirectBufferAddress(uBufferObj);
    jint uSize = env->GetDirectBufferCapacity(uBufferObj);

    // Get the V plane
    jobject vPlane = env->GetObjectArrayElement(planesArray, 2);
    jobject vBufferObj = env->CallObjectMethod(vPlane, getBufferMethod);
    jint vRowStride = env->CallIntMethod(vPlane, getRowStrideMethod);
    jbyte *vBuffer = (jbyte *) env->GetDirectBufferAddress(vBufferObj);
    jint vSize = env->GetDirectBufferCapacity(vBufferObj);

    // Allocate a byte array to hold the YUV data
    jbyteArray yuvData = env->NewByteArray(ySize + uSize + vSize);
    jbyte *yuvBuffer = env->GetByteArrayElements(yuvData, nullptr);

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
