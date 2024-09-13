package com.qdev.singlesurfacedualquality.utils

import android.media.Image
import com.qdev.singlesurfacedualquality.YUV420
import java.nio.ByteBuffer

object YuvUtils {
    init {
        System.loadLibrary("yuv_copy")
    }

    fun determineYuvFormat(image: Image): String {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride

        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        // Assume width and height are known or obtainable from the image reader
        val width = image.width
        val height = image.height

        // Check for I420 format
        if (uPixelStride == 1 && vPixelStride == 1 &&
            uRowStride == width / 2 && vRowStride == width / 2) {
            return "I420"
        }

        // Check for NV12 format
        if (uPixelStride == 2 && vPixelStride == 2 &&
            uRowStride == width && vRowStride == width) {
            val uvBuffer = uPlane.buffer
            val firstU = uvBuffer.get(0)
            val secondV = uvBuffer.get(1)

            return if (firstU < secondV) {
                "NV12" // UVUV pattern
            } else {
                "NV21" // VUVU pattern
            }
        }

        return "Unknown Format"
    }


    external fun setupQueue(capacity: Int, width: Int, height: Int)

    external fun cleanupQueue()

    external fun copyYUV(srcImage: Image, destImage: Image)
//    external fun copyYUV2(srcImage: Image, destImage: Image)
    external fun addToNativeQueue(yData: ByteBuffer,
                                  uData: ByteBuffer,
                                  vData: ByteBuffer,
                                  yRowStride: Int,
                                  uRowStride: Int,
                                  vRowStride: Int,
                                  yPixelStride: Int,
                                  uPixelStride: Int,
                                  vPixelStride: Int,
                                  timestamp: Long,
                                  width: Int,
                                  height: Int)

    external fun isQueueEmpty(): Boolean

    /*external fun copyFromQueueToImage(image: Image, removeFromQueue: Boolean): Boolean*/

    external fun copyToImage2(image: Image, removeFromQueue: Boolean)

    external fun copyToImageV2(image: Image, removeFromQueue: Boolean)

    external fun copyToImageV3(image: Image, removeFromQueue: Boolean)

    external fun copyYUVBuffer(image: Image): ByteArray //  hits buffer overflow

    external fun copyToImage(yuv420: YUV420, image: Image)
}