package com.qdev.singlesurfacedualquality.utils

import android.media.Image
import com.qdev.singlesurfacedualquality.YUV420
import java.nio.ByteBuffer

object YuvUtils {
    init {
        System.loadLibrary("yuv_copy")
    }

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
                                  timestamp: Long)

    external fun isQueueEmpty(): Boolean

    /*external fun copyFromQueueToImage(image: Image, removeFromQueue: Boolean): Boolean*/

    external fun copyToImage2(image: Image, removeFromQueue: Boolean)

    external fun copyToImageV2(image: Image, removeFromQueue: Boolean)

    external fun copyToImageV3(image: Image, removeFromQueue: Boolean)

    external fun copyYUVBuffer(image: Image): ByteArray //  hits buffer overflow

    external fun copyToImage(yuv420: YUV420, image: Image)
}