package com.qdev.singlesurfacedualquality

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.CamcorderProfile
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.qdev.singlesurfacedualquality.databinding.ActivityMainBinding
import com.qdev.singlesurfacedualquality.utils.CircularArrayQueue
import com.qdev.singlesurfacedualquality.utils.InputSurface
import com.qdev.singlesurfacedualquality.utils.OutputSurface
import com.qdev.singlesurfacedualquality.utils.Utils
import com.qdev.singlesurfacedualquality.utils.YuvUtils
import com.qdev.singlesurfacedualquality.views.ResolutionsSpinnerAdapter
import io.github.crow_misia.libyuv.RowStride
import io.github.crow_misia.libyuv.Yuv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.system.measureTimeMillis


class MainActivity : AppCompatActivity() {
    private val TAG = MainActivity::class.java.canonicalName

    private lateinit var binding: ActivityMainBinding

    private val permissions: Array<String> = arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)

    private var encodeThread: HandlerThread? = null
    private var encodeHandler: Handler? = null
    private var encodeLqThread: HandlerThread? = null
    private var encodeLqHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var processThread: HandlerThread? = null
    private var processHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var previewSession: CameraCaptureSession? = null
    private var captureSession: CameraCaptureSession? = null
    private var isRecording: Boolean = false
    private var selectedResolution: Int = -1

    private var mediaCodec: MediaCodec? = null
    private var lqMediaCodec: MediaCodec? = null
    private var hqMuxer: MediaMuxer? = null
    private var lqMuxer: MediaMuxer? = null
    private var imageReader: ImageReader? = null
    private var highQualityVideoTrackIndex: Int = -1
    private var lowQualityVideoTrackIndex: Int = -1
    private var hqCodecStarted: Boolean = false
    private var lqCodecStarted: Boolean = false
    private var decoderStarted: Boolean = false
    private var isHighQualityMuxerStarted: Boolean = false
    private var isLowQualityMuxerStarted: Boolean = false
    private var semaphore: Semaphore = Semaphore(1)

    private val FRAGMENT_SHADER: String =
        "#extension GL_OES_EGL_image_external : require\n" + "precision mediump float;\n" + "varying vec2 vTextureCoord;\n" + "uniform samplerExternalOES sTexture;\n" + "void main() {\n" + "  gl_FragColor = texture2D(sTexture, vTextureCoord).rbga;\n" + "}\n"

    private val imReaderBufferInfo = MediaCodec.BufferInfo()
    private var hqDone: AtomicBoolean = AtomicBoolean(false)
    private var lqDone: AtomicBoolean = AtomicBoolean(false)

    //  the frame counts for each output file
    private var hqFrameCount: Int = 0
    private var lqFrameCount: Int = 0

    //  the output file counts
    private var hqFileCount: Int = 0
    private var lqFileCount: Int = 0

    private lateinit var queue: CircularArrayQueue

    private val supportedResolutions by lazy(::getSupportedResolutionsList)

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val cameraImage = reader.acquireLatestImage() ?: return@OnImageAvailableListener/*Log.d(TAG, "onImageAvailable: plane 0: Buffer size = ${cameraImage.planes[0].buffer.remaining()}, " +
                "plane 1: Buffer size = ${cameraImage.planes[1].buffer.remaining()}, \n" +
                "plane 2: Buffer size = ${cameraImage.planes[2].buffer.remaining()} \n" +
                "width * height = ${cameraImage.width * cameraImage.height} \n" +
                "width * height / 4 = ${(cameraImage.width / 2) * (cameraImage.height / 2)}")*/
        Log.d(
            TAG,
            "onImageAvailable: " + "\nwidth x height = ${cameraImage.width} x ${cameraImage.height}" + "\npixel strides = ${cameraImage.planes[0].pixelStride}, ${cameraImage.planes[1].pixelStride}, ${cameraImage.planes[2].pixelStride}" + "\nrow strides = ${cameraImage.planes[0].rowStride}, ${cameraImage.planes[1].rowStride}, ${cameraImage.planes[2].rowStride}"
        )
        if (isRecording) {
            //  enqueue the image to the NDK queue
            /*val timeToCreateQueueEntry = measureTimeMillis {
                YuvUtils.addToNativeQueue(
                    yData = cameraImage.planes[0].buffer,
                    uData = cameraImage.planes[1].buffer,
                    vData = cameraImage.planes[2].buffer,
                    yRowStride = cameraImage.planes[0].rowStride,
                    uRowStride = cameraImage.planes[1].rowStride,
                    vRowStride = cameraImage.planes[2].rowStride,
                    yPixelStride = cameraImage.planes[0].pixelStride,
                    uPixelStride = cameraImage.planes[1].pixelStride,
                    vPixelStride = cameraImage.planes[2].pixelStride,
                    timestamp = cameraImage.timestamp,
                    width = cameraImage.width,
                    height = cameraImage.height
                )
                Log.d(TAG, "onImageAvailable: to queue frame time stamp = ${cameraImage.timestamp}")
            }*/

            Log.d(TAG, "onImageAvailable: colour format = ${cameraImage.format}")

            val timestamp = cameraImage.timestamp

            //  enqueue the image to the kotlin queue
            /*val timeToCreateQueueEntry = measureTimeMillis {
                if (!this::queue.isInitialized){
                    queue = CircularArrayQueue(
                        6,
                        cameraImage.planes[0].buffer.capacity(),
                        cameraImage.planes[1].buffer.capacity(),
                        cameraImage.planes[2].buffer.capacity()
                    )
                }

                queue.enqueue(
                    width = cameraImage.width,
                    height = cameraImage.height,
                    y = cameraImage.planes[0].buffer,
                    u = cameraImage.planes[1].buffer,
                    v = cameraImage.planes[2].buffer,
                    yRowStride = cameraImage.planes[0].rowStride,
                    uRowStride = cameraImage.planes[1].rowStride,
                    vRowStride = cameraImage.planes[2].rowStride,
                    yPixelStride = cameraImage.planes[0].pixelStride,
                    uPixelStride = cameraImage.planes[1].pixelStride,
                    vPixelStride = cameraImage.planes[2].pixelStride,
                    timestampUs = cameraImage.timestamp
                )
            }

            Log.d(TAG, "onImageAvailable: time taken to add to queue $timeToCreateQueueEntry ms")*/

            if (hqCodecStarted) {
                handleHqInputBuffers(cameraImage)
            }
            if (lqCodecStarted) {
                handleLqInputBuffers(cameraImage)
            }
            cameraImage.close()
            if (hqCodecStarted) { handleHqCodecOutputBuffer() }
            if (lqCodecStarted) { handleLqCodecOutputBuffer() }

        } else {
            cameraImage.close()

            if (isHighQualityMuxerStarted) {
                try {
                    isHighQualityMuxerStarted = false
                    hqMuxer?.stop()
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                } finally {
                    hqMuxer?.release()
                    hqMuxer = null
                }
            }

            if (isLowQualityMuxerStarted) {
                try {
                    isLowQualityMuxerStarted = false
                    lqMuxer?.stop()
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                } finally {
                    lqMuxer?.release()
                    lqMuxer = null
                }
            }

            if (lqCodecStarted) {
                try {
                    Log.d(TAG, "onImageAvailable: stopping low quality codec")
                    lqMediaCodec?.stop()
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                } finally {
                    lqMediaCodec?.release()
                    lqMediaCodec = null
                }
            }

            if (hqCodecStarted) {
                try {
                    mediaCodec?.stop()
                    Log.d(TAG, "onImageAvailable: stopping high quality codec")
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                } finally {
                    mediaCodec?.release()
                    mediaCodec = null
                }
            }

            /*imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null*/
        }


        /*val planes = cameraImage.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        // Get the strides and buffer sizes
        val yRowStride = planes[0].rowStride
        val uRowStride = planes[1].rowStride
        val vRowStride = planes[2].rowStride

        val yPixelStride = planes[0].pixelStride
        val uPixelStride = planes[1].pixelStride
        val vPixelStride = planes[2].pixelStride

        Log.d(TAG, "onImageAvailable: yRowStride = $yRowStride, uRowStride = $uRowStride, vRowStride = $vRowStride")
        Log.d(TAG, "onImageAvailable: yPixelStride = $yPixelStride, uPixelStride = $uPixelStride, vPixelStride = $vPixelStride")

        YuvUtils.addToNativeQueue(
            yBuffer,
            uBuffer,
            vBuffer,
            yRowStride,
            uRowStride,
            vRowStride,
            yPixelStride,
            uPixelStride,
            vPixelStride
        )

        cameraImage.close()*/

        /*Log.d(TAG, "onImageAvailable: image format ${cameraImage.format}, plane 0 size ${cameraImage.planes[0].buffer.remaining()}")

        encodeHandler?.post {
            handleHqInputBuffers(cameraImage)
            hqDone.set(true)
            handleHqCodecOutputBuffer()
        }
        encodeLqHandler?.post {
            handleLqInputBuffers(cameraImage)
            lqDone.set(true)
            handleLqCodecOutputBuffer()
        }

        while (!hqDone.get() || !lqDone.get()) {
            Thread.sleep(10)
        }
        if (semaphore.tryAcquire(1, 100, TimeUnit.MILLISECONDS)) {
            cameraImage.close()
            semaphore.release()
        }

        hqDone.set(false)
        lqDone.set(false)

        if (!isRecording) {
//            mediaCodec?.signalEndOfInputStream()
            try {
                mediaCodec?.stop()
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            } finally {
                mediaCodec?.release()
                mediaCodec = null
            }

            try {
                isHighQualityMuxerStarted = false
                hqMuxer?.stop()
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            } finally {
                hqMuxer?.release()
                hqMuxer = null
            }

            try {
                lqMediaCodec?.stop()
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            } finally {
                lqMediaCodec?.release()
                lqMediaCodec = null
            }

            try {
                isLowQualityMuxerStarted = false
                lqMuxer?.stop()
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            } finally {
                lqMuxer?.release()
                lqMuxer = null
            }

            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null
        }*/
    }

    private fun processQueue() {
        val corePoolSize = 4
        val maximumPoolSize = corePoolSize * 4
        val keepAliveTime = 100L
        val workQueue = /*SynchronousQueue<Runnable>()*/ LinkedBlockingQueue<Runnable>()
        val workerPool: ExecutorService = ThreadPoolExecutor(
            corePoolSize, maximumPoolSize, keepAliveTime, TimeUnit.SECONDS, workQueue
        )

        while (true) {/*if (!this::queue.isInitialized || queue.isEmpty()) {
                Thread.sleep(10)
                continue
            }*/

            if (YuvUtils.isQueueEmpty()) {
                Thread.sleep(5)
                continue
            }

//            val cameraImage = queue.dequeue() ?: return

            workerPool.submit {
//                handleHqInputBuffers()
//                handleLqInputBuffers()
            }
            workerPool.submit {
                handleHqCodecOutputBuffer()
                handleLqCodecOutputBuffer()
            }

            /*encodeHandler?.post {
                handleHqInputBuffers(cameraImage)
                Log.d(TAG, "processQueue: hq input taken ${System.currentTimeMillis() - startTime} ms")
                hqDone.set(true)
                handleHqCodecOutputBuffer()
            }*//*encodeLqHandler?.post {
                handleLqInputBuffers(cameraImage)
                Log.d(TAG, "processQueue: lq input taken ${System.currentTimeMillis() - startTime} ms")
                lqDone.set(true)
                handleLqCodecOutputBuffer()
            }*/

            /*while (!hqDone.get() || !lqDone.get()) {
                Thread.sleep(10)
                Log.d(TAG, "processQueue: waiting for hq and lq to finish")
            }
            if (semaphore.tryAcquire(1, 100, TimeUnit.MILLISECONDS)) {
                cameraImage?.close()
                Log.d(TAG, "processQueue: image closed after ${System.currentTimeMillis() - startTime} ms")
                semaphore.release()
            }*/

            hqDone.set(false)
            lqDone.set(false)

            if (!isRecording) {
                try {
                    mediaCodec?.stop()
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                } finally {
                    mediaCodec?.release()
                    mediaCodec = null
                }

                try {
                    isHighQualityMuxerStarted = false
                    hqMuxer?.stop()
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                } finally {
                    hqMuxer?.release()
                    hqMuxer = null
                }

                try {
                    lqMediaCodec?.stop()
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                } finally {
                    lqMediaCodec?.release()
                    lqMediaCodec = null
                }

                try {
                    isLowQualityMuxerStarted = false
                    lqMuxer?.stop()
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                } finally {
                    lqMuxer?.release()
                    lqMuxer = null
                }

                imageReader?.setOnImageAvailableListener(null, null)
                imageReader?.close()
                imageReader = null

                break
            }
        }
    }

    private fun handleHqInputBuffers(cameraImage: Image) {
        if (semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
            val index = mediaCodec?.dequeueInputBuffer(500)
            if (index != null && index >= 0) {
                val inBuff = mediaCodec?.getInputBuffer(index)

                if ((inBuff?.capacity() ?: -1) >= 0) {
                    //  copy the ImageReader image to the input image
                    val inputImage = mediaCodec?.getInputImage(index)
                    inputImage?.let {
                        val cameraImageType = YuvUtils.determineYuvFormat(cameraImage)
                        val inputImageType = YuvUtils.determineYuvFormat(it)
                        Log.d(TAG, "handleHqInputBuffers: camera image format = ${cameraImage.format}")
                        Log.d(TAG, "handleHqInputBuffers: input image format = ${it.format}")
                        Log.d(TAG, "handleHqInputBuffers: camera image type = $cameraImageType")
                        Log.d(TAG, "handleHqInputBuffers: codec image type = $inputImageType")
//                        YuvUtils.copyYUV(cameraImage, it)
                        val timeToCopy = measureTimeMillis {
//                                YuvUtils.copyToImage(cameraImage, it)
//                            YuvUtils.copyToImageV3(it, false)
                            copyByType(cameraImageType, inputImageType, cameraImage, it, false)
                        }

                        Log.d(TAG, "handleHqInputBuffers: time to copy $timeToCopy ms")
                        hqDone.set(true)
                        mediaCodec?.queueInputBuffer(/* index = */ index,/* offset = */
                            0,/* size = */
                            it.planes[0].buffer.remaining(),/* presentationTimeUs = *//*cameraImage.timestampUs / 1000*/
                            cameraImage.timestamp / 1000,/* flags = */
                            if (isRecording) {
                                if (hqFrameCount == 300) {
                                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                                } else {
                                    0
                                }
                            } else MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }
                } else {
                    mediaCodec?.queueInputBuffer(index, 0, 0, 0, if (isRecording) 0 else MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }

            hqFrameCount++
            semaphore.release()
        }
    }

    /*private fun copyByType(cameraImageType: String, inputImageType: String, cameraImage: Image, it: Image) {
        if (cameraImageType != "I420" && inputImageType != "I420") {
            Yuv.planerNV12Copy(
                srcY = cameraImage.planes[0].buffer,
                srcStrideY = RowStride(cameraImage.planes[0].rowStride),
                srcOffsetY = 0,
                srcUV = cameraImage.planes[1].buffer,
                srcStrideUV = RowStride(cameraImage.planes[1].rowStride),
                srcOffsetUV = 0,
                dstY = it.planes[0].buffer,
                dstStrideY = RowStride(it.planes[0].rowStride),
                dstOffsetY = 0,
                dstUV = it.planes[1].buffer,
                dstStrideUV = RowStride(it.planes[1].rowStride),
                dstOffsetUV = 0,
                width = cameraImage.width,
                height = cameraImage.height
            )
        } else if (cameraImageType == "I420" && inputImageType == "I420") {
            Yuv.convertI420Copy(
                srcY = cameraImage.planes[0].buffer,
                srcStrideY = RowStride(cameraImage.planes[0].rowStride),
                srcOffsetY = 0,
                srcU = cameraImage.planes[1].buffer,
                srcStrideU = RowStride(cameraImage.planes[1].rowStride),
                srcOffsetU = 0,
                srcV = cameraImage.planes[2].buffer,
                srcStrideV = RowStride(cameraImage.planes[2].rowStride),
                srcOffsetV = 0,
                dstY = it.planes[0].buffer,
                dstStrideY = RowStride(it.planes[0].rowStride),
                dstOffsetY = 0,
                dstU = it.planes[1].buffer,
                dstStrideU = RowStride(it.planes[1].rowStride),
                dstOffsetU = 0,
                dstV = it.planes[2].buffer,
                dstStrideV = RowStride(it.planes[2].rowStride),
                dstOffsetV = 0,
                width = cameraImage.width,
                height = cameraImage.height
            )
        } else if (cameraImageType == "I420" && (inputImageType == "NV12" || inputImageType == "NV21")) {
            Yuv.convertI420ToNV12(
                srcY = cameraImage.planes[0].buffer,
                srcStrideY = RowStride(cameraImage.planes[0].rowStride),
                srcOffsetY = 0,
                srcU = cameraImage.planes[1].buffer,
                srcStrideU = RowStride(cameraImage.planes[1].rowStride),
                srcOffsetU = 0,
                srcV = cameraImage.planes[2].buffer,
                srcStrideV = RowStride(cameraImage.planes[2].rowStride),
                srcOffsetV = 0,
                dstY = it.planes[0].buffer,
                dstStrideY = RowStride(it.planes[0].rowStride),
                dstOffsetY = 0,
                dstUV = it.planes[1].buffer,
                dstStrideUV = RowStride(it.planes[1].rowStride),
                dstOffsetUV = 0,
                width = cameraImage.width,
                height = cameraImage.height
            )
        } else if ((cameraImageType == "NV12" || cameraImageType == "NV21") && inputImageType == "I420") {
            Yuv.convertNV12ToI420(
                srcY = cameraImage.planes[0].buffer,
                srcStrideY = RowStride(cameraImage.planes[0].rowStride),
                srcOffsetY = 0,
                srcUV = cameraImage.planes[1].buffer,
                srcStrideUV = RowStride(cameraImage.planes[1].rowStride),
                srcOffsetUV = 0,
                dstY = it.planes[0].buffer,
                dstStrideY = RowStride(it.planes[0].rowStride),
                dstOffsetY = 0,
                dstU = it.planes[1].buffer,
                dstStrideU = RowStride(it.planes[1].rowStride),
                dstOffsetU = 0,
                dstV = it.planes[2].buffer,
                dstStrideV = RowStride(it.planes[2].rowStride),
                dstOffsetV = 0,
                width = cameraImage.width,
                height = cameraImage.height
            )
        }
    }*/

    private fun copyByType(cameraImageType: String, inputImageType: String, cameraImage: Image, inputImage: Image, removeFromQueue: Boolean = false) {
        when {
            cameraImageType == "I420" && inputImageType == "I420" -> {
                // Copy I420 to I420
                Yuv.convertI420Copy(
                    srcY = cameraImage.planes[0].buffer,
                    srcStrideY = RowStride(cameraImage.planes[0].rowStride),
                    srcOffsetY = 0,
                    srcU = cameraImage.planes[1].buffer,
                    srcStrideU = RowStride(cameraImage.planes[1].rowStride),
                    srcOffsetU = 0,
                    srcV = cameraImage.planes[2].buffer,
                    srcStrideV = RowStride(cameraImage.planes[2].rowStride),
                    srcOffsetV = 0,
                    dstY = inputImage.planes[0].buffer,
                    dstStrideY = RowStride(inputImage.planes[0].rowStride),
                    dstOffsetY = 0,
                    dstU = inputImage.planes[1].buffer,
                    dstStrideU = RowStride(inputImage.planes[1].rowStride),
                    dstOffsetU = 0,
                    dstV = inputImage.planes[2].buffer,
                    dstStrideV = RowStride(inputImage.planes[2].rowStride),
                    dstOffsetV = 0,
                    width = cameraImage.width,
                    height = cameraImage.height
                )
            }

            (cameraImageType == "NV12" || cameraImageType == "NV21") && inputImageType == "I420" -> {
                // Convert NV12/NV21 to I420
                Yuv.convertNV12ToI420(
                    srcY = cameraImage.planes[0].buffer,
                    srcStrideY = RowStride(cameraImage.planes[0].rowStride),
                    srcOffsetY = 0,
                    srcUV = cameraImage.planes[1].buffer,
                    srcStrideUV = RowStride(cameraImage.planes[1].rowStride),
                    srcOffsetUV = 0,
                    dstY = inputImage.planes[0].buffer,
                    dstStrideY = RowStride(inputImage.planes[0].rowStride),
                    dstOffsetY = 0,
                    dstU = inputImage.planes[1].buffer,
                    dstStrideU = RowStride(inputImage.planes[1].rowStride),
                    dstOffsetU = 0,
                    dstV = inputImage.planes[2].buffer,
                    dstStrideV = RowStride(inputImage.planes[2].rowStride),
                    dstOffsetV = 0,
                    width = cameraImage.width,
                    height = cameraImage.height
                )
            }

            cameraImageType == "I420" && (inputImageType == "NV12" || inputImageType == "NV21") -> {
                // Convert I420 to NV12/NV21
                Yuv.convertI420ToNV12(
                    srcY = cameraImage.planes[0].buffer,
                    srcStrideY = RowStride(cameraImage.planes[0].rowStride),
                    srcOffsetY = 0,
                    srcU = cameraImage.planes[1].buffer,
                    srcStrideU = RowStride(cameraImage.planes[1].rowStride),
                    srcOffsetU = 0,
                    srcV = cameraImage.planes[2].buffer,
                    srcStrideV = RowStride(cameraImage.planes[2].rowStride),
                    srcOffsetV = 0,
                    dstY = inputImage.planes[0].buffer,
                    dstStrideY = RowStride(inputImage.planes[0].rowStride),
                    dstOffsetY = 0,
                    dstUV = inputImage.planes[1].buffer,
                    dstStrideUV = RowStride(inputImage.planes[1].rowStride),
                    dstOffsetUV = 0,
                    width = cameraImage.width,
                    height = cameraImage.height
                )
            }

            (cameraImageType == "NV12" || cameraImageType == "NV21") && (inputImageType == "NV12" || inputImageType == "NV21") -> {
                // Copy NV12 to NV12 or NV21 to NV21
                Yuv.planerNV12Copy(
                    srcY = cameraImage.planes[0].buffer,
                    srcStrideY = RowStride(cameraImage.planes[0].rowStride),
                    srcOffsetY = 0,
                    srcUV = cameraImage.planes[1].buffer,
                    srcStrideUV = RowStride(cameraImage.planes[1].rowStride),
                    srcOffsetUV = 0,
                    dstY = inputImage.planes[0].buffer,
                    dstStrideY = RowStride(inputImage.planes[0].rowStride),
                    dstOffsetY = 0,
                    dstUV = inputImage.planes[1].buffer,
                    dstStrideUV = RowStride(inputImage.planes[1].rowStride),
                    dstOffsetUV = 0,
                    width = cameraImage.width,
                    height = cameraImage.height
                )
            }

            else -> {
                YuvUtils.addToNativeQueue(
                    yData = cameraImage.planes[0].buffer,
                    uData = cameraImage.planes[1].buffer,
                    vData = cameraImage.planes[2].buffer,
                    yRowStride = cameraImage.planes[0].rowStride,
                    uRowStride = cameraImage.planes[1].rowStride,
                    vRowStride = cameraImage.planes[2].rowStride,
                    yPixelStride = cameraImage.planes[0].pixelStride,
                    uPixelStride = cameraImage.planes[1].pixelStride,
                    vPixelStride = cameraImage.planes[2].pixelStride,
                    timestamp = cameraImage.timestamp,
                    width = cameraImage.width,
                    height = cameraImage.height
                )
                YuvUtils.copyToImageV3(inputImage, false)
            }
        }
    }

    private fun handleLqInputBuffers(cameraImage: Image) {
        if (semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
            val index = lqMediaCodec?.dequeueInputBuffer(0)
            if (index != null && index >= 0) {
                val inBuff = lqMediaCodec?.getInputBuffer(index)
                if ((inBuff?.capacity() ?: -1) >= 0) {
                    //  copy the ImageReader image to the input image
                    val inputImage = lqMediaCodec?.getInputImage(index)
                    inputImage?.let {
//                        YuvUtils.copyYUV(cameraImage, it)
                        val cameraImageType = YuvUtils.determineYuvFormat(cameraImage)
                        val inputImageType = YuvUtils.determineYuvFormat(it)
                        Log.d(TAG, "handleHqInputBuffers: camera image type = ${cameraImageType}")
                        Log.d(TAG, "handleHqInputBuffers: codec image type = ${inputImageType}")
//                        YuvUtils.copyYUV(cameraImage, it)
                        val timeToCopy = measureTimeMillis {
//                                YuvUtils.copyToImage(cameraImage, it)
//                            YuvUtils.copyToImageV3(it, false)
                            copyByType(cameraImageType, inputImageType, cameraImage, it, true)
                        }

                        Log.d(TAG, "handleLqInputBuffers: time to copy ${timeToCopy} ms")
                        lqDone.set(true)
                        lqMediaCodec?.queueInputBuffer(/* index = */ index,/* offset = */
                            0,/* size = */
                            it.planes[0].buffer.remaining(),/* presentationTimeUs = *//*cameraImage.timestampUs / 1000*/
                            cameraImage.timestamp / 1000,/* flags = */
                            if (isRecording) {
                                if (lqFrameCount == 300) {
                                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                                } else {
                                    0
                                }
                            } else {
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            }
                        )
                    }
                } else {
                    lqMediaCodec?.queueInputBuffer(index, 0, 0, 0, if (isRecording) 0 else MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }

            lqFrameCount++
            semaphore.release()
        }
    }

    private fun handleHqCodecOutputBuffer() {
        val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(imReaderBufferInfo, 500)
        if (outputBufferIndex != null && outputBufferIndex >= 0) {
            val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferIndex)

            if (imReaderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                imReaderBufferInfo.size = 0
                startChronometerUI()
            }

            if (imReaderBufferInfo.size != 0) {
                // start the muxer if not stated
                if (!isHighQualityMuxerStarted && isRecording) {
                    highQualityVideoTrackIndex = hqMuxer?.addTrack(mediaCodec!!.outputFormat) ?: -1
                    hqMuxer?.start()?.also { isHighQualityMuxerStarted = true }
                }

                //  write the data to the muxer
                outputBuffer?.apply {
                    position(imReaderBufferInfo.offset)
                    limit(imReaderBufferInfo.offset + imReaderBufferInfo.size)
                    if (isHighQualityMuxerStarted) {
                        try {
                            hqMuxer!!.writeSampleData(highQualityVideoTrackIndex, this, imReaderBufferInfo)
                        } catch (e: IllegalStateException) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)

            if (hqFrameCount >= 300) {
                hqFrameCount = 0
                try {
                    isHighQualityMuxerStarted = false
                    hqMuxer?.stop()
                    Log.d(TAG, "handleHqCodecOutputBuffer: stopping muxer for segmentation")
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                } finally {
                    hqMuxer?.release()
                    hqMuxer = null
                }
                hqFileCount++
                val hqFile =
                    File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        "${Utils.getDeviceName()}_high_quality_$hqFileCount.mp4"
                    ).absolutePath
                hqMuxer = MediaMuxer(hqFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                CoroutineScope(IO).launch {
                    MediaScannerConnection.scanFile(this@MainActivity, arrayOf(hqFile), null, null)
                }
            }
        }
    }

    private fun handleLqCodecOutputBuffer() {
        val outputBufferIndex = lqMediaCodec?.dequeueOutputBuffer(imReaderBufferInfo, 500)
        if (outputBufferIndex != null && outputBufferIndex >= 0) {
            val outputBuffer = lqMediaCodec?.getOutputBuffer(outputBufferIndex)

            if (imReaderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                imReaderBufferInfo.size = 0
            }

            if (imReaderBufferInfo.size != 0) {
                // start the muxer if not stated
                if (!isLowQualityMuxerStarted && isRecording) {
                    lowQualityVideoTrackIndex = lqMuxer?.addTrack(lqMediaCodec!!.outputFormat) ?: -1
                    lqMuxer?.start()?.also { isLowQualityMuxerStarted = true }
                }

                //  write the data to the muxer
                outputBuffer?.apply {
                    position(imReaderBufferInfo.offset)
                    limit(imReaderBufferInfo.offset + imReaderBufferInfo.size)
                    if (isLowQualityMuxerStarted) {
                        try {
                            lqMuxer!!.writeSampleData(lowQualityVideoTrackIndex, this, imReaderBufferInfo)
                        } catch (e: IllegalStateException) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            lqMediaCodec?.releaseOutputBuffer(outputBufferIndex, false)

            if (lqFrameCount >= 300) {
                lqFrameCount = 0
                try {
                    isLowQualityMuxerStarted = false
                    lqMuxer?.stop()
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                } finally {
                    lqMuxer?.release()
                    lqMuxer = null
                }
                lqFileCount++
                val lqFile =
                    File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        "${Utils.getDeviceName()}_low_quality_$lqFileCount.mp4"
                    ).absolutePath
                lqMuxer = MediaMuxer(lqFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                CoroutineScope(IO).launch {
                    MediaScannerConnection.scanFile(this@MainActivity, arrayOf(lqFile), null, null)
                }
            }
        }
    }

    private fun copyYUVImage(srcImage: Image, dstImage: Image) {
        // Get the image planes for both source and destination images
        val srcPlanes = srcImage.planes
        val dstPlanes = dstImage.planes

        for (i in srcPlanes.indices) {
            val srcBuffer = srcPlanes[i].buffer
            val dstBuffer = dstPlanes[i].buffer

            val srcRowStride = srcPlanes[i].rowStride
            val dstRowStride = dstPlanes[i].rowStride
            val srcPixelStride = srcPlanes[i].pixelStride
            val dstPixelStride = dstPlanes[i].pixelStride

            val width = srcImage.width shr (if (i == 0) 0 else 1)
            val height = srcImage.height shr (if (i == 0) 0 else 1)

            if (srcRowStride == dstRowStride && srcPixelStride == dstPixelStride) {
                // Fast path: if row stride and pixel stride match, we can copy the whole buffer at once
                dstBuffer.put(srcBuffer)
            } else {
                // Slow path: copy row by row
                for (row in 0 until height) {
                    val srcPos = row * srcRowStride
                    val dstPos = row * dstRowStride
                    for (col in 0 until width) {
                        dstBuffer.put(dstPos + col * dstPixelStride, srcBuffer[srcPos + col * srcPixelStride])
                    }
                }
            }
        }
    }

    private val manager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    private val cameraPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            launchCamera()
        } else {
            Log.d(TAG, "Some permissions denied")
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            // Ignored, Camera does all the work for us
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "Camera opened")
            cameraDevice = camera
            startPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.d(TAG, "Camera disconnected")
            cameraDevice?.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            cameraDevice?.close()
            cameraDevice = null
        }
    }

    //  start camera for preview
    private fun startPreview() {
        if (cameraDevice == null || !binding.texture.isAvailable) {
            return
        }

        backgroundHandler?.post {
            try {
                val texture = binding.texture.surfaceTexture?.apply { setDefaultBufferSize(binding.texture.width, binding.texture.height) }
                val surface = texture?.let { Surface(it) }
                if (surface == null) {
                    Log.e(TAG, "Error creating camera preview surface")
                    return@post
                }

                val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequestBuilder.addTarget(surface)

                val outputConfig = ArrayList<OutputConfiguration>()
                outputConfig.add(OutputConfiguration(surface))

                val sessionConfig = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    outputConfig,
                    Executors.newCachedThreadPool(),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            previewSession = session
                            try {
                                session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error starting camera preview", e)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Error configuring camera preview")
                        }
                    })

                cameraDevice!!.createCaptureSession(sessionConfig)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting camera preview", e)
            }

        }
    }

    private fun startRecording() {
        if (cameraDevice == null || !binding.texture.isAvailable) {
            return
        }
        try {
            setupSingleSurface()
            setupMuxers()
            //  preview
            val texture = binding.texture.surfaceTexture
            texture?.setDefaultBufferSize(1920, 1080)
            val previewSurface = Surface(texture)

//            val highQualitySurface: Surface = mediaCodec!!.createInputSurface()

            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            captureRequestBuilder.addTarget(previewSurface)
            captureRequestBuilder.addTarget(imageReader!!.surface)
//            captureRequestBuilder.addTarget(highQualitySurface)

            val surfaces: MutableList<Surface> = ArrayList()
            surfaces.add(previewSurface)
            surfaces.add(imageReader!!.surface)
//            surfaces.add(highQualitySurface)

            cameraDevice!!.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    try {
                        captureSession!!.setRepeatingRequest(captureRequestBuilder.build(), null, null)

                        mediaCodec?.start()
                        hqCodecStarted = true

                        lqMediaCodec?.start()
                        lqCodecStarted = true
                        Log.d(TAG, "onConfigured: starting low quality  codec")

                        Log.d(
                            TAG,
                            "onConfigured: max supported instances ${mediaCodec?.codecInfo?.getCapabilitiesForType("video/avc")?.maxSupportedInstances}"
                        )
//                        hqCodecStarted = true
//                        encodeFrames()
                        /*processHandler?.post {
                            processQueue()
                        }*/
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    // Handle configuration failure
                }
            }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun setupMuxers() {
        try {
            val hqFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "${Utils.getDeviceName()}_high_quality.mp4"
            ).absolutePath
            val lqFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "${Utils.getDeviceName()}_low_quality.mp4"
            ).absolutePath
            Log.d(TAG, "setupMuxers: outputting to $hqFile")
            hqMuxer = MediaMuxer(hqFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            lqMuxer = MediaMuxer(lqFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            CoroutineScope(IO).launch {
                MediaScannerConnection.scanFile(this@MainActivity, arrayOf(hqFile, lqFile), null, null)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun stopRecording() {
        isHighQualityMuxerStarted = false
        try {
            hqMuxer?.stop()
            Log.d(TAG, "stopRecording: stopping high quality muxer")
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } finally {
            hqMuxer?.release()
        }
        isLowQualityMuxerStarted = false
        try {
            lqMuxer?.stop()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } finally {
            lqMuxer?.release()
        }

        encodeThread?.quitSafely()
        encodeThread = null
        encodeHandler = null

        encodeLqThread?.quitSafely()
        encodeLqThread = null
        encodeLqHandler = null

        captureSession?.apply {
            stopRepeating()
            abortCaptures()
            close()
        }

        YuvUtils.cleanupQueue()
        stopChronometerUI()
    }

    private fun setupSingleSurface() {

        if (encodeThread == null || encodeHandler == null) {
            encodeThread = HandlerThread("EncodeThread")
            encodeThread!!.start()
            encodeHandler = Handler(encodeThread!!.looper)
        }

        if (encodeLqThread == null || encodeLqHandler == null) {
            encodeLqThread = HandlerThread("EncodeLqThread")
            encodeLqThread!!.start()
            encodeLqHandler = Handler(encodeLqThread!!.looper)
        }

        if (processThread == null || processHandler == null) {
            processThread = HandlerThread("ProcessThread")
            processThread!!.start()
            processHandler = Handler(processThread!!.looper)
        }

        val chosenSize = if (selectedResolution != -1) {
            Size(supportedResolutions[selectedResolution].videoFrameWidth, supportedResolutions[selectedResolution].videoFrameHeight)
        } else {
            Size(1920, 1080)
        }

        YuvUtils.setupQueue(5, chosenSize.width, chosenSize.height)

        try {
            mediaCodec = MediaCodec.createEncoderByType("video/avc")

            val format = MediaFormat.createVideoFormat("video/avc", chosenSize.width, chosenSize.height/*1920, 1080*/)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 6 * 1000 * 1000) // 10 Mbps
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second between I-frames

            mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        try {
            lqMediaCodec = MediaCodec.createEncoderByType("video/avc")

            val format = MediaFormat.createVideoFormat("video/avc", chosenSize.width, chosenSize.height/*1920, 1080*/)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 500 * 1000) // 10 Mbps
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5) // 1 second between I-frames

            lqMediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

        imageReader?.close()
        imageReader = ImageReader.newInstance(chosenSize.width, chosenSize.height/*1920, 1080*/, android.graphics.ImageFormat.YUV_420_888, 2)
        imageReader?.setOnImageAvailableListener(imageListener, backgroundHandler)
    }

    private fun encodeFrames() {
        encodeThread = HandlerThread("EncodeThread")
        encodeThread!!.start()
        encodeHandler = Handler(encodeThread!!.looper)

        val outputSurface = OutputSurface(1920, 1080)
        encodeHandler?.post {
            try {
                val hqToLqDecoder = MediaCodec.createDecoderByType("video/avc")/*hqToLqDecoder.start()
                decoderStarted = true*/

                val bufferInfo = MediaCodec.BufferInfo()
                val decoderBufferInfo = MediaCodec.BufferInfo()
                val lqBufferInfo = MediaCodec.BufferInfo()
                var decoderInputBuffers: Array<ByteBuffer>? = null
                var decoderOutputBuffers: Array<ByteBuffer>? = null
                var hqToLqFormat: MediaFormat? = null
                var decoderConfigured: Boolean = false

                val lowQualityCodec = MediaCodec.createEncoderByType("video/avc")
                val lowQualityFormat = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
                lowQualityFormat.apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, 1 * 1000 * 1000) // 1 Mbps
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second between I-frames
                }
                var inputSurface: InputSurface? = null

                lqCodecStarted = true

                while (true) {
                    if (hqCodecStarted) {
                        //  getting the output from
                        val hqOutputBufferIndex = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 0)
                        if (hqOutputBufferIndex >= 0) {
                            val hqOutputBuffer = mediaCodec!!.getOutputBuffer(hqOutputBufferIndex)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }

                            if (bufferInfo.size != 0) {
                                // start the muxer if not stated
                                if (!isHighQualityMuxerStarted) {
                                    highQualityVideoTrackIndex = hqMuxer!!.addTrack(mediaCodec!!.outputFormat)
                                    hqMuxer!!.start()
                                    isHighQualityMuxerStarted = true
                                }

                                //  write the data to the muxer
                                hqOutputBuffer?.apply {
                                    position(bufferInfo.offset)
                                    limit(bufferInfo.offset + bufferInfo.size)
                                    if (isHighQualityMuxerStarted) {
                                        hqMuxer!!.writeSampleData(highQualityVideoTrackIndex, this, bufferInfo)
                                    }
                                }
                            }
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                // Codec config info.  Only expected on first packet.  One way to
                                // handle this is to manually stuff the data into the MediaFormat
                                // and pass that to configure().  We do that here to exercise the API.
                                hqToLqFormat = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
                                hqToLqFormat.setByteBuffer("csd-0", hqOutputBuffer)

                                lowQualityCodec.setCallback(object : MediaCodec.Callback() {
                                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

                                    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                                        Log.d(TAG, "onOutputBufferAvailable: lq outputBuffer available")
                                        if (bufferInfo.size != 0) {
                                            // start the muxer if not stated
                                            if (!isLowQualityMuxerStarted) {
                                                lowQualityVideoTrackIndex = lqMuxer!!.addTrack(codec.outputFormat)
                                                lqMuxer!!.start()
                                                isLowQualityMuxerStarted = true
                                            }

                                            //  write the data to the muxer
                                            hqOutputBuffer?.apply {
                                                position(bufferInfo.offset)
                                                limit(bufferInfo.offset + bufferInfo.size)
                                                if (isLowQualityMuxerStarted) {
                                                    lqMuxer!!.writeSampleData(lowQualityVideoTrackIndex, this, bufferInfo)
                                                }
                                            }
                                        }
                                    }

                                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = Unit

                                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
                                })

                                hqToLqDecoder.configure(
                                    hqToLqFormat,/*decoderOutputSurface.surface!!*/
                                    outputSurface.surface, null, 0
                                )

                                Log.d(
                                    TAG,
                                    "encodeFrames: max supported decoders ${hqToLqDecoder.codecInfo.getCapabilitiesForType("video/avc").maxSupportedInstances}"
                                )
                                hqToLqDecoder.start()
                                decoderStarted = true
                                decoderInputBuffers = hqToLqDecoder.inputBuffers
                                decoderOutputBuffers = hqToLqDecoder.outputBuffers
                                decoderConfigured = true

                                lowQualityCodec.configure(lowQualityFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                                inputSurface = InputSurface(lowQualityCodec.createInputSurface())
                                inputSurface.makeCurrent()
                            } else {
                                // Get a decoder input buffer, blocking until it's available.
                                val inputBufIndex = hqToLqDecoder.dequeueInputBuffer(-1)
                                val inputBuf: ByteBuffer? = decoderInputBuffers?.get(inputBufIndex)
                                inputBuf?.clear()
                                inputBuf?.put(hqOutputBuffer!!)

                                hqToLqDecoder.queueInputBuffer(
                                    inputBufIndex, 0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags
                                )
                            }
                            mediaCodec!!.releaseOutputBuffer(hqOutputBufferIndex, false)

                            if (decoderConfigured) {
                                val decoderStatus = hqToLqDecoder.dequeueOutputBuffer(decoderBufferInfo, 0)
                                if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                                    //  no output available yet
                                    Log.d(TAG, "encodeFrames: no output available yet")
                                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
//                                    decoderOutputBuffers = hqToLqDecoder.outputBuffers
                                    Log.d(TAG, "encodeFrames: output buffers changed")
                                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                    hqToLqFormat = hqToLqDecoder.outputFormat
                                    Log.d(TAG, "encodeFrames: new format $hqToLqFormat")
                                } else if (decoderStatus < 0) {
                                    Log.e(TAG, "decodeFrame: unexpected result from decoder.dequeueOutputBuffer: $decoderStatus")
                                } else {
                                    val doRender = decoderBufferInfo.size != 0
                                    if (decoderBufferInfo.size > 0) {
                                        hqToLqDecoder.releaseOutputBuffer(decoderStatus, doRender)
                                        Log.d(TAG, "encodeFrames: render = $doRender")
                                        if (doRender) {
                                            if (!lqCodecStarted) {
//                                                lowQualityCodec.setInputSurface(inputSurface.surface)
                                                //  create input surface to be called only after config and before start.

                                                lowQualityCodec.start()
                                                lqCodecStarted = true
                                            }
                                            runOnUiThread {
                                                outputSurface.awaitNewImage()
                                                outputSurface.drawImage()
                                                Log.d(TAG, "encodeFrames: drawing image")
                                                inputSurface?.setPresentationTime(decoderBufferInfo.presentationTimeUs * 1000)
                                                inputSurface?.swapBuffers()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }

                mediaCodec!!.stop()
                mediaCodec!!.release()
                hqToLqDecoder.stop()
                hqToLqDecoder.release()
                lowQualityCodec.stop()
                lowQualityCodec.release()
                hqCodecStarted = false
                decoderStarted = false
                lqCodecStarted = false
//                decoderOutputSurface?.release()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }


    private fun startThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error on stopping background thread", e)
        }
    }

    private fun launchCamera() {
        if (backgroundThread == null) {
            startThread()
        }

        if (binding.texture.isAvailable) {
            openCamera(binding.texture.width, binding.texture.height)
        } else {
            binding.texture.surfaceTextureListener = surfaceListener
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bindListeners()
    }

    private fun bindListeners() {
        with(binding) {
            btnCapture.setOnClickListener {
                if (!isRecording) {
                    startRecording()
                    isRecording = true
                    btnCapture.text = "Stop"
                } else {
                    isRecording = false
                    stopRecording()
                    btnCapture.text = "Start"
                    startPreview()
                }
            }
            videoResolutions.setOnItemClickListener { _, _, position, _ ->
                setSelectedResolutionText(position)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (!hasPermissions()) {
            cameraPermissionRequest.launch(permissions)
        } else {
            launchCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        stopThread()
    }

    //  opens the back facing camera
    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        val cameraId = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        if (cameraId == null) {
            Log.e(TAG, "No back facing camera found")
            return
        }
        binding.texture.post {
            configureTransform(width, height)
            manager.openCamera(cameraId, cameraStateCallback, backgroundHandler)

            setupRecorderResolutionsList()
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = windowManager?.defaultDisplay?.rotation
        val matrix = Matrix()
        val viewRect = RectF(0F, 0F, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0F, 0F, binding.texture.height.toFloat(), binding.texture.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_0 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.CENTER)
            val scale = max(
                viewHeight.toFloat() / binding.texture.width, viewWidth.toFloat() / binding.texture.height
            )
            matrix.postScale(scale, scale, centerX, centerY)
        } else if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
//            Log.d(TAG, "configureTransform: preview sizes ${mPreviewSize!!.width} ${mPreviewSize!!.height}")
//            Log.d(TAG, "configureTransform: view sizes $viewWidth $viewHeight")
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(
                viewHeight.toFloat() / binding.texture.height, viewWidth.toFloat() / binding.texture.width
            )
            Log.d(TAG, "configureTransform: scale $scale")
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90 * (rotation - 2).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }

        binding.texture.setTransform(matrix)
    }

    private fun setupRecorderResolutionsList() {
        binding.videoResolutions.apply {
            inputType = InputType.TYPE_NULL
            setText("Resolutions", false)
            val resolutionsList = supportedResolutions

            val resolutionsSpinnerAdapter = ResolutionsSpinnerAdapter(this@MainActivity, resolutionsList)
            setDropDownBackgroundDrawable(ResourcesCompat.getDrawable(resources, R.drawable.resolution_spinner_background, null))
            setAdapter(resolutionsSpinnerAdapter)
            resolutionsSpinnerAdapter.setNotifyOnChange(true)
            resolutionsSpinnerAdapter.notifyDataSetChanged()
        }
    }

    private fun setSelectedResolutionText(position: Int) {
        if (supportedResolutions.isEmpty()) return

        selectedResolution = position
        with(supportedResolutions[position]) {
            binding.videoResolutions.setText(getResTextFromSelection(), false)
        }
    }

    private fun hasPermissions(): Boolean {
        permissions.forEach {
            if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun getSupportedResolutionsList(): List<CamcorderProfile> {
        val completeProfiles = arrayListOf<Int>()
        completeProfiles.apply {
            add(CamcorderProfile.QUALITY_4KDCI)
            add(CamcorderProfile.QUALITY_2K)
            add(CamcorderProfile.QUALITY_2160P)
            add(CamcorderProfile.QUALITY_1080P)
            add(CamcorderProfile.QUALITY_720P)
            add(CamcorderProfile.QUALITY_480P)
            add(CamcorderProfile.QUALITY_CIF)

            //  slow mo videos
            add(CamcorderProfile.QUALITY_HIGH_SPEED_4KDCI)  //  API level 30
            add(CamcorderProfile.QUALITY_HIGH_SPEED_2160P)
            add(CamcorderProfile.QUALITY_HIGH_SPEED_1080P)
            add(CamcorderProfile.QUALITY_HIGH_SPEED_720P)
            add(CamcorderProfile.QUALITY_HIGH_SPEED_480P)
            //  API level 30
            add(CamcorderProfile.QUALITY_HIGH_SPEED_VGA)
            add(CamcorderProfile.QUALITY_HIGH_SPEED_CIF)
        }

        val availableProfiles = arrayListOf<CamcorderProfile>()
        completeProfiles.forEach {
            if (CamcorderProfile.hasProfile(it)) {
                availableProfiles.add(CamcorderProfile.get(it))
            }
        }

        return availableProfiles.distinctBy { it.videoFrameWidth }
    }

    fun CamcorderProfile.getResTextFromSelection(): String = when {
        videoFrameWidth < 720 || videoFrameHeight < 720 -> {
            "vga${videoFrameWidth}x${videoFrameHeight}"
        }

        videoFrameWidth in 720..1080 || videoFrameHeight in 720..1080 -> {
            "hd${videoFrameWidth}x${videoFrameHeight}"
        }

        videoFrameWidth in 1081..2048 || videoFrameHeight in 1081..2048 -> {
            "hd2k${videoFrameWidth}x${videoFrameHeight}"
        }

        videoFrameWidth in 2160..4096 || videoFrameHeight in 2160..4096 -> {
            "hd4k${videoFrameWidth}x${videoFrameHeight}"
        }

        else -> "${videoFrameWidth}x${videoFrameHeight}"
    }

    fun startChronometerUI() {
        CoroutineScope(Main).launch {
            binding.chronometer.base = SystemClock.elapsedRealtime()
            binding.chronometer.start()
            binding.chronometer.background = ResourcesCompat.getDrawable(resources, R.drawable.background_ai_red_button, theme)
        }
    }

    fun stopChronometerUI() {
        CoroutineScope(Main).launch {
            binding.chronometer.stop()
            binding.chronometer.background = null
        }
    }


}

class YUV420(
    var width: Int,
    var height: Int,
    var y: ByteBuffer,
    var u: ByteBuffer,
    var v: ByteBuffer,
    var yRowStride: Int,
    var uRowStride: Int,
    var vRowStride: Int,
    var yPixelStride: Int,
    var uPixelStride: Int,
    var vPixelStride: Int,
    var timestampUs: Long
) {
    var yBuffer: ByteBuffer = y.makeCopy()
    var uBuffer: ByteBuffer = u.makeCopy()
    var vBuffer: ByteBuffer = v.makeCopy()

}

//  extension function to copy the ByteBuffer
private fun ByteBuffer.makeCopy(): ByteBuffer {
    val buffer = if (isDirect) ByteBuffer.allocateDirect(capacity()) else ByteBuffer.allocate(capacity())

    val position = position()
    val limit = limit()
    rewind()

    buffer.put(this)
    buffer.rewind()

    position(position)
    limit(limit)

    buffer.position(position)
    buffer.limit(limit)

    return buffer
}