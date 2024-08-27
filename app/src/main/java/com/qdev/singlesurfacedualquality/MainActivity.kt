package com.qdev.singlesurfacedualquality

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
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
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore.Audio.Media
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.performance.play.services.PlayServicesDevicePerformance
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.qdev.singlesurfacedualquality.databinding.ActivityMainBinding
import com.qdev.singlesurfacedualquality.utils.InputSurface
import com.qdev.singlesurfacedualquality.utils.OutputSurface
import com.qdev.singlesurfacedualquality.utils.YuvUtils
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.system.measureTimeMillis


class MainActivity : AppCompatActivity() {
    private val TAG = MainActivity::class.java.canonicalName

    private lateinit var binding: ActivityMainBinding
    private lateinit var devicePerformance: PlayServicesDevicePerformance

    private val permissions: Array<String> = arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)

    private var encodeThread: HandlerThread? = null
    private var encodeHandler: Handler? = null
    private var encodeLqThread: HandlerThread? = null
    private var encodeLqHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var previewSession: CameraCaptureSession? = null
    private var captureSession: CameraCaptureSession? = null
    private var isRecording: Boolean = false

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

    private val FRAGMENT_SHADER: String =
        "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTexture, vTextureCoord).rbga;\n" +
                "}\n"

    private val imReaderBufferInfo = MediaCodec.BufferInfo()
    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val cameraImage = reader.acquireLatestImage()?:return@OnImageAvailableListener

        Log.d(TAG, "onImageAvailable: image format ${cameraImage.format}, plane 0 size ${cameraImage.planes[0].buffer.remaining()}")

        handleHqInputBuffers(cameraImage)
        handleLqInputBuffers(cameraImage)

        cameraImage.close()

        encodeHandler?.post {
            handleHqCodecOutputBuffer()
        }

        encodeLqHandler?.post {
            handleLqCodecOutputBuffer()
        }

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
        }
    }

    private fun handleHqInputBuffers(cameraImage: Image) {
        val index = mediaCodec?.dequeueInputBuffer(0)
        if (index != null && index >= 0) {
            val inBuff = mediaCodec?.getInputBuffer(index)
            if ((inBuff?.capacity() ?: -1) >= 0) {
                //  copy the ImageReader image to the input image
                val inputImage = mediaCodec?.getInputImage(index)
                inputImage?.let {
                    YuvUtils.copyYUV(cameraImage, it)

                    mediaCodec?.queueInputBuffer(
                        /* index = */ index,
                        /* offset = */ 0,
                        /* size = */ cameraImage.planes[0].buffer.remaining(),
                        /* presentationTimeUs = */ cameraImage.timestamp / 1000,
                        /* flags = */ if (isRecording) 0 else MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
            } else {
                mediaCodec?.queueInputBuffer(index, 0, 0, 0, if (isRecording) 0 else MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
    }

    private fun handleLqInputBuffers(cameraImage: Image) {
        val index = lqMediaCodec?.dequeueInputBuffer(0)
        if (index != null && index >= 0) {
            val inBuff = lqMediaCodec?.getInputBuffer(index)
            if ((inBuff?.capacity() ?: -1) >= 0) {
                //  copy the ImageReader image to the input image
                val inputImage = lqMediaCodec?.getInputImage(index)
                inputImage?.let {
                    YuvUtils.copyYUV(cameraImage, it)

                    lqMediaCodec?.queueInputBuffer(
                        /* index = */ index,
                        /* offset = */ 0,
                        /* size = */ cameraImage.planes[0].buffer.remaining(),
                        /* presentationTimeUs = */ cameraImage.timestamp / 1000,
                        /* flags = */ if (isRecording) 0 else MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
            } else {
                lqMediaCodec?.queueInputBuffer(index, 0, 0, 0, if (isRecording) 0 else MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
    }

    private fun handleHqCodecOutputBuffer() {
        val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(imReaderBufferInfo, 500)
        if (outputBufferIndex != null && outputBufferIndex >= 0) {
            val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferIndex)

            if (imReaderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                imReaderBufferInfo.size = 0
            }

            if (imReaderBufferInfo.size != 0) {
                // start the muxer if not stated
                if (!isHighQualityMuxerStarted && isRecording && hqMuxer != null) {
                    highQualityVideoTrackIndex = hqMuxer!!.addTrack(mediaCodec!!.outputFormat)
                    hqMuxer!!.start()
                    isHighQualityMuxerStarted = true
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
                if (!isLowQualityMuxerStarted && isRecording && lqMuxer != null) {
                    lowQualityVideoTrackIndex = lqMuxer!!.addTrack(lqMediaCodec!!.outputFormat)
                    lqMuxer!!.start()
                    isLowQualityMuxerStarted = true
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
                val texture = binding.texture.surfaceTexture
                    ?.apply { setDefaultBufferSize(binding.texture.width, binding.texture.height) }
                val surface = texture?.let { Surface(it) }
                if (surface == null) {
                    Log.e(TAG, "Error creating camera preview surface")
                    return@post
                }

                val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequestBuilder.addTarget(surface)

                val outputConfig = ArrayList<OutputConfiguration>()
                outputConfig.add(OutputConfiguration(surface))

                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
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

                        Log.d(TAG, "onConfigured: max supported instances ${mediaCodec?.codecInfo?.getCapabilitiesForType("video/avc")?.maxSupportedInstances}")
//                        hqCodecStarted = true
//                        encodeFrames()
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
            val hqFile = File(filesDir, "high_quality.mp4").absolutePath
            val lqFile = File(filesDir, "low_quality.mp4").absolutePath
            hqMuxer = MediaMuxer(hqFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            lqMuxer = MediaMuxer(lqFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun stopRecording() {
        try {
            hqMuxer?.stop()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } finally {
            isHighQualityMuxerStarted = false
            hqMuxer?.release()
        }
        try {
            lqMuxer?.stop()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } finally {
            isLowQualityMuxerStarted = false
            lqMuxer?.release()
        }

        encodeThread?.quitSafely()
        encodeThread = null
        encodeHandler = null

        captureSession?.apply {
            stopRepeating()
            abortCaptures()
            close()
        }
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

        try {
            mediaCodec = MediaCodec.createEncoderByType("video/avc")

            val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 10 * 1000 * 1000) // 10 Mbps
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

            val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
            format.setInteger(MediaFormat.KEY_BIT_RATE,  500 * 1000) // 10 Mbps
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second between I-frames

            lqMediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

        imageReader?.close()
        imageReader = ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.YUV_420_888, 2)
        imageReader?.setOnImageAvailableListener(imageListener, backgroundHandler)
    }

    private fun encodeFrames() {
        encodeThread = HandlerThread("EncodeThread")
        encodeThread!!.start()
        encodeHandler = Handler(encodeThread!!.looper)

        val outputSurface = OutputSurface(1920, 1080)
        encodeHandler?.post {
            try {
                val hqToLqDecoder = MediaCodec.createDecoderByType("video/avc")
                /*hqToLqDecoder.start()
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
                                    hqToLqFormat,
                                    /*decoderOutputSurface.surface!!*/
                                    outputSurface.surface,
                                    null,
                                    0
                                )

                                Log.d(TAG, "encodeFrames: max supported decoders ${hqToLqDecoder.codecInfo.getCapabilitiesForType("video/avc").maxSupportedInstances}")
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
                                    inputBufIndex,
                                    0,
                                    bufferInfo.size,
                                    bufferInfo.presentationTimeUs,
                                    bufferInfo.flags
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

        devicePerformance = PlayServicesDevicePerformance(this.applicationContext)

        bindListeners()
    }

    private fun bindListeners() {
        with(binding) {
            btnCapture.setOnClickListener {
                if (!isRecording) {
                    startRecording()
                    btnCapture.text = "Stop"
                    isRecording = true
                } else {
                    stopRecording()
                    btnCapture.text = "Start"
                    isRecording = false
                    startPreview()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Media performance class = ${devicePerformance.mediaPerformanceClass}")

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
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = windowManager?.defaultDisplay?.rotation
        val matrix = Matrix()
        val viewRect = RectF(0F, 0F, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect =
            RectF(0F, 0F, binding.texture.height.toFloat(), binding.texture.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_0 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.CENTER)
            val scale = max(
                viewHeight.toFloat() / binding.texture.width,
                viewWidth.toFloat() / binding.texture.height
            )
            matrix.postScale(scale, scale, centerX, centerY)
        } else if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
//            Log.d(TAG, "configureTransform: preview sizes ${mPreviewSize!!.width} ${mPreviewSize!!.height}")
//            Log.d(TAG, "configureTransform: view sizes $viewWidth $viewHeight")
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(
                viewHeight.toFloat() / binding.texture.height,
                viewWidth.toFloat() / binding.texture.width
            )
            Log.d(TAG, "configureTransform: scale $scale")
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90 * (rotation - 2).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }

        binding.texture.setTransform(matrix)
    }

    private fun hasPermissions(): Boolean {
        permissions.forEach {
            if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }
}

class YUV420(
    val width: Int,
    val height: Int,
    val y: ByteBuffer,
    val u: ByteBuffer,
    val v: ByteBuffer,
    val timestampUs: Long
)

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