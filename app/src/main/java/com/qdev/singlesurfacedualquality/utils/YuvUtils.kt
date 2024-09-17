package com.qdev.singlesurfacedualquality.utils

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.qdev.singlesurfacedualquality.YUV420
import io.github.crow_misia.libyuv.Yuv
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
        // Check for YV12 format
        if (uPixelStride == 1 && vPixelStride == 1 &&
            uRowStride == width / 2 && vRowStride == width / 2) {
            val uBufferPosition = uPlane.buffer.position()
            val vBufferPosition = vPlane.buffer.position()

            return if (vBufferPosition < uBufferPosition) {
                "YV12" // VU order
            } else {
                "I420" // UV order
            }
        }

        // Check for NV12 format
        if (uPixelStride == 2 && vPixelStride == 2 &&
            uRowStride >= width && vRowStride >= width && uRowStride == vRowStride) {
            /*val uvBuffer = uPlane.buffer
            val firstU = uvBuffer.get(0)
            val secondV = uvBuffer.get(1)

            return if (firstU < secondV) {
                "NV12" // UVUV pattern
            } else {
                "NV21" // VUVU pattern
            }*/

            return when (checkNv12orNv21(image)){
                0 -> "NV12"
                1 -> "NV21"
                else -> "Unknown Format"
            }
        }

        return "Unknown Format"
    }

    fun convertNV12ToNV21(
        srcY: ByteBuffer, srcStrideY: Int, dstY: ByteBuffer, dstStrideY: Int,
        srcUV: ByteBuffer, srcStrideUV: Int, dstVU: ByteBuffer, dstStrideVU: Int,
        width: Int, height: Int
    ) {
        /*// Copy Y plane as is
        Yuv.memcopy(dstY, 0, srcY, 0, width * height)

        // Swap U and V in each pixel in UV plane
        val uvBuffer = ByteArray(width * height / 2)
        srcUV.get(uvBuffer)
        for (i in uvBuffer.indices step 2) {
            val u = uvBuffer[i]
            val v = uvBuffer[i + 1]
            uvBuffer[i] = v
            uvBuffer[i + 1] = u
        }
        dstVU.put(uvBuffer)*/

        // Copy Y plane with padding handling
        for (row in 0 until height) {
            srcY.position(row * srcStrideY)
            dstY.position(row * dstStrideY)
            // Copy the entire row from source to destination
            val srcRowSlice = srcY.slice()

            // Set the limit of the slice to the actual width of the row (ignoring padding)
            srcRowSlice.limit(width)

            // Copy the row from the source slice to the destination buffer
            dstY.put(srcRowSlice)
        }

        // Handle the UV plane conversion from NV12 (UV) to NV21 (VU)
        for (row in 0 until height / 2) {
            srcUV.position(row * srcStrideUV)
            dstVU.position(row * dstStrideVU)

            for (col in 0 until width step 2) {
                val u = srcUV.get()
                val v = srcUV.get()
                dstVU.put(v)
                dstVU.put(u)
            }
        }
    }

    fun convertNV21ToNV12(
        srcY: ByteBuffer, srcStrideY: Int, dstY: ByteBuffer, dstStrideY: Int,
        srcVU: ByteBuffer, srcStrideVU: Int, dstUV: ByteBuffer, dstStrideUV: Int,
        width: Int, height: Int
    ) {
        /*// Copy Y plane as is
        Yuv.memcopy(dstY, 0, srcY, 0, width * height)

        // Swap V and U in each pixel in VU plane
        val vuBuffer = ByteArray(width * height / 2)
        srcVU.get(vuBuffer)
        for (i in vuBuffer.indices step 2) {
            val v = vuBuffer[i]
            val u = vuBuffer[i + 1]
            vuBuffer[i] = u
            vuBuffer[i + 1] = v
        }
        dstUV.put(vuBuffer)*/
        // Copy Y plane with padding handling
        // Copy Y plane with padding handling
        for (row in 0 until height) {
            srcY.position(row * srcStrideY)
            dstY.position(row * dstStrideY)
            // Copy the entire row from source to destination
            val srcRowSlice = srcY.slice()
            srcRowSlice.limit(width)

            dstY.put(srcRowSlice)
        }

        // Handle the VU plane conversion from NV21 (VU) to NV12 (UV)
        for (row in 0 until height / 2) {
            srcVU.position(row * srcStrideVU)
            dstUV.position(row * dstStrideUV)

            for (col in 0 until width step 2) {
                val v = srcVU.get()
                val u = srcVU.get()
                dstUV.put(u)
                dstUV.put(v)
            }
        }
    }

    private fun isNV12orNV21Supported(context: Context, cameraId: String): Boolean {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
        val configurationMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false

        // Get all supported output formats
        val supportedFormats = configurationMap.outputFormats

        // Check for NV12 and NV21 (usually represented by YUV_420_888)
        // Note: NV12 and NV21 might not be directly listed; instead, they might be implicitly supported by YUV_420_888.
        for (format in supportedFormats) {
            if (format == ImageFormat.YUV_420_888) {
                // Camera supports YUV_420_888; this format can often be used for NV12 or NV21.
                return true
            }
        }
        return false
    }

    private fun isNV12orNV21SupportedForCodec(mimeType: String): Pair<Boolean, Int> {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue  // If checking for encoder support

            val codecCapabilities = try {
                codecInfo.getCapabilitiesForType(mimeType)
            } catch (e: IllegalArgumentException){
                continue
            }

            val colorFormats = codecCapabilities.colorFormats

            // Check if NV12 (COLOR_FormatYUV420SemiPlanar) or NV21 (COLOR_FormatYUV420Planar) is supported
            for (colorFormat in colorFormats) {
                if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar || // NV12
                    colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                ) {   // NV21
                    return if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                        Pair(true, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                    else Pair(true, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
                }
            }
        }
        return Pair (false, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
    }

    fun isNV12orNV21Supported(context: Context, cameraId: String, mimeType: String): Pair<Boolean, Int> {
        val imageReaderSupport = isNV12orNV21Supported(context, cameraId)
        val mediaCodecSupport = isNV12orNV21SupportedForCodec(mimeType)

        return if (imageReaderSupport && mediaCodecSupport.first)
            mediaCodecSupport
        else Pair(false, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
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

    external fun checkNv12orNv21(image: Image): Int

    /*external fun copyFromQueueToImage(image: Image, removeFromQueue: Boolean): Boolean*/

    external fun copyToImage2(image: Image, removeFromQueue: Boolean)

    external fun copyToImageV2(image: Image, removeFromQueue: Boolean)

    external fun copyToImageV3(image: Image, removeFromQueue: Boolean)

    external fun copyYUVBuffer(image: Image): ByteArray //  hits buffer overflow

    external fun copyToImage(yuv420: YUV420, image: Image)
}