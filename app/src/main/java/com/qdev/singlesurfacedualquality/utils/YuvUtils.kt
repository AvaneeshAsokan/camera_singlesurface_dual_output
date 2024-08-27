package com.qdev.singlesurfacedualquality.utils

import android.media.Image

object YuvUtils {
    init {
        System.loadLibrary("yuv_copy")
    }

    external fun copyYUV(srcImage: Image, destImage: Image)
//    external fun copyYUV2(srcImage: Image, destImage: Image)
}