package com.example.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import com.example.model.ExportResolution
import com.example.model.ExportSettings
import com.example.model.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object VideoRenderer {

    suspend fun renderMotionTransferredVideo(
        context: Context,
        originalVideo: VideoSource,
        motionTimeline: MotionTimeline,
        exportSettings: ExportSettings,
        outputFile: File,
        onProgress: (progress: Float, message: String) -> Unit
    ): File = withContext(Dispatchers.Default) {
        val originalUri = originalVideo.uri
        val durationMs = originalVideo.durationMs.coerceAtLeast(1000L)

        // Determine output dimensions
        val (outWidth, outHeight) = determineOutputDimensions(
            originalVideo.width,
            originalVideo.height,
            exportSettings.resolution
        )

        val bitrate = exportSettings.targetBitrateMbps * 1_000_000

        onProgress(0.02f, "Initializing encoder and video pipeline...")

        val videoExtractor = MediaExtractor().apply {
            setDataSource(context, originalUri, null)
        }
        val audioExtractor = MediaExtractor().apply {
            setDataSource(context, originalUri, null)
        }

        var videoTrackIdx = -1
        var audioTrackIdx = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null

        for (i in 0 until videoExtractor.trackCount) {
            val format = videoExtractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/") && videoTrackIdx == -1) {
                videoTrackIdx = i
                videoFormat = format
            }
        }

        for (i in 0 until audioExtractor.trackCount) {
            val format = audioExtractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/") && audioTrackIdx == -1) {
                audioTrackIdx = i
                audioFormat = format
            }
        }

        if (videoTrackIdx == -1 || videoFormat == null) {
            videoExtractor.release()
            audioExtractor.release()
            throw IllegalStateException("Original video contains no valid video track")
        }

        videoExtractor.selectTrack(videoTrackIdx)
        if (audioTrackIdx != -1) {
            audioExtractor.selectTrack(audioTrackIdx)
        }

        // Configure Encoder
        val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface = encoder.createInputSurface()
        encoder.start()

        // Setup EGL on the encoder input surface
        val eglHelper = EglRendererHelper(encoderInputSurface, outWidth, outHeight)

        // Setup SurfaceTexture for decoder output
        val decoderSurfaceTexture = SurfaceTexture(eglHelper.textureId)
        val decoderSurface = Surface(decoderSurfaceTexture)

        // Configure Decoder
        val decoderMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
        val decoder = MediaCodec.createDecoderByType(decoderMime)
        decoder.configure(videoFormat, decoderSurface, null, 0)
        decoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        val timeoutUs = 2500L
        val maxDurationUs = durationMs * 1000L

        try {
            while (!outputDone) {
                ensureActive()

                // Feed decoder
                if (!inputDone) {
                    val inputBufIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputBufIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufIndex)
                        if (inputBuffer != null) {
                            val sampleSize = videoExtractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val presentationTimeUs = videoExtractor.sampleTime
                                decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, presentationTimeUs, 0)
                                videoExtractor.advance()
                            }
                        }
                    }
                }

                // Drain decoder
                var decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (decoderStatus >= 0) {
                    val doRender = bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0
                    decoder.releaseOutputBuffer(decoderStatus, doRender)

                    if (doRender) {
                        decoderSurfaceTexture.updateTexImage()
                        val frameTimeMs = bufferInfo.presentationTimeUs / 1000L

                        // Interpolate motion keyframe at this exact timestamp
                        val motion = motionTimeline.getTransformForOriginalTime(
                            frameTimeMs,
                            durationMs,
                            exportSettings.mappingMode
                        )

                        // Render frame with transformed matrix in OpenGL
                        eglHelper.drawFrame(decoderSurfaceTexture, motion.scale, motion.positionX, motion.positionY, motion.rotationDeg)
                        eglHelper.setPresentationTime(bufferInfo.presentationTimeUs * 1000L) // in nanoseconds
                        eglHelper.swapBuffers()

                        val progress = ((bufferInfo.presentationTimeUs.toFloat() / maxDurationUs.toFloat()) * 0.85f).coerceIn(0f, 0.85f)
                        val formattedTime = String.format(java.util.Locale.US, "%.1fs", frameTimeMs / 1000f)
                        onProgress(progress, "Rendering frame at $formattedTime (${(progress * 100).toInt()}%)")
                    }

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoder.signalEndOfInputStream()
                    }
                }

                // Drain encoder
                while (true) {
                    val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 0L)
                    if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) {
                            throw RuntimeException("Encoder format changed twice")
                        }
                        val newFormat = encoder.outputFormat
                        muxerVideoTrack = muxer.addTrack(newFormat)

                        // Add audio track to muxer if available
                        if (audioTrackIdx != -1 && audioFormat != null) {
                            muxerAudioTrack = muxer.addTrack(audioFormat)
                        }

                        muxer.start()
                        muxerStarted = true
                    } else if (encoderStatus >= 0) {
                        val encodedData = encoder.getOutputBuffer(encoderStatus)
                        if (encodedData != null && muxerStarted && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                        encoder.releaseOutputBuffer(encoderStatus, false)
                    }
                }
            }

            // Copy audio track to muxer for 100% audio fidelity preservation
            if (audioTrackIdx != -1 && muxerAudioTrack != -1 && muxerStarted) {
                onProgress(0.90f, "Muxing original audio track...")
                val audioBuf = ByteBuffer.allocateDirect(128 * 1024)
                val audioBufferInfo = MediaCodec.BufferInfo()

                while (true) {
                    ensureActive()
                    val sampleSize = audioExtractor.readSampleData(audioBuf, 0)
                    if (sampleSize < 0) break

                    audioBufferInfo.offset = 0
                    audioBufferInfo.size = sampleSize
                    audioBufferInfo.presentationTimeUs = audioExtractor.sampleTime
                    audioBufferInfo.flags = audioExtractor.sampleFlags

                    if (audioBufferInfo.presentationTimeUs <= maxDurationUs + 500_000L) {
                        muxer.writeSampleData(muxerAudioTrack, audioBuf, audioBufferInfo)
                    }
                    audioExtractor.advance()
                }
            }

            onProgress(1.0f, "Export Complete")
        } finally {
            try { decoder.stop(); decoder.release() } catch (_: Exception) {}
            try { decoderSurface.release() } catch (_: Exception) {}
            try { decoderSurfaceTexture.release() } catch (_: Exception) {}
            try { eglHelper.release() } catch (_: Exception) {}
            try { encoder.stop(); encoder.release() } catch (_: Exception) {}
            try { encoderInputSurface.release() } catch (_: Exception) {}
            try {
                if (muxerStarted) muxer.stop()
                muxer.release()
            } catch (_: Exception) {}
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor.release() } catch (_: Exception) {}
        }

        outputFile
    }

    private fun determineOutputDimensions(
        origWidth: Int,
        origHeight: Int,
        resolution: ExportResolution
    ): Pair<Int, Int> {
        if (resolution == ExportResolution.ORIGINAL) {
            // Must be even dimensions for H.264
            val w = if (origWidth % 2 == 0) origWidth else origWidth - 1
            val h = if (origHeight % 2 == 0) origHeight else origHeight - 1
            return Pair(w, h)
        }

        val targetMax = resolution.maxDimension
        val isLandscape = origWidth >= origHeight
        val (w, h) = if (isLandscape) {
            val scale = targetMax.toFloat() / origWidth.toFloat()
            Pair(targetMax, (origHeight * scale).toInt())
        } else {
            val scale = targetMax.toFloat() / origHeight.toFloat()
            Pair((origWidth * scale).toInt(), targetMax)
        }

        val evenW = (w / 2) * 2
        val evenH = (h / 2) * 2
        return Pair(evenW.coerceAtLeast(320), evenH.coerceAtLeast(240))
    }

    // Embedded EGL + OpenGL ES 2.0 Renderer for hardware-accelerated frame transformation
    private class EglRendererHelper(
        private val outputSurface: Surface,
        private val width: Int,
        private val height: Int
    ) {
        companion object {
            private const val EGL_RECORDABLE_ANDROID = 0x3142
        }

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        var textureId: Int = -1
            private set

        private var program: Int = 0
        private var uMvpMatrixLoc: Int = -1
        private var uTexMatrixLoc: Int = -1
        private var aPositionLoc: Int = -1
        private var aTexCoordLoc: Int = -1

        private val vertexBuffer: FloatBuffer
        private val texCoordBuffer: FloatBuffer

        private val mvpMatrix = FloatArray(16)
        private val texMatrix = FloatArray(16)

        init {
            // Full-screen quad vertices
            val quadVertices = floatArrayOf(
                -1.0f, -1.0f, 0.0f,
                 1.0f, -1.0f, 0.0f,
                -1.0f,  1.0f, 0.0f,
                 1.0f,  1.0f, 0.0f
            )
            vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply {
                    put(quadVertices)
                    position(0)
                }

            val texCoords = floatArrayOf(
                0.0f, 0.0f,
                1.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 1.0f
            )
            texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply {
                    put(texCoords)
                    position(0)
                }

            initEGL()
            initGLES()
        }

        private fun initEGL() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], outputSurface, surfaceAttribs, 0)

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        private fun initGLES() {
            val vShaderCode = """
                uniform mat4 uMvpMatrix;
                uniform mat4 uTexMatrix;
                attribute vec4 aPosition;
                attribute vec4 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = uMvpMatrix * aPosition;
                    vTexCoord = (uTexMatrix * aTexCoord).xy;
                }
            """.trimIndent()

            val fShaderCode = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                }
            """.trimIndent()

            val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vShaderCode)
            val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fShaderCode)

            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vShader)
            GLES20.glAttachShader(program, fShader)
            GLES20.glLinkProgram(program)

            uMvpMatrixLoc = GLES20.glGetUniformLocation(program, "uMvpMatrix")
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glViewport(0, 0, width, height)
        }

        private fun loadShader(type: Int, code: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
            return shader
        }

        fun drawFrame(surfaceTexture: SurfaceTexture, scale: Float, posX: Float, posY: Float, rotationDeg: Float) {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)

            surfaceTexture.getTransformMatrix(texMatrix)

            // Compute MVP Matrix applying the transferred motion:
            // Scale around center, Translate (pan offset), and Rotate
            Matrix.setIdentityM(mvpMatrix, 0)

            // Pan offset: posX = 0.5 is center, mapped to OpenGL NDC [-1, 1]
            // Note: Pan movement moves the framing; panning right shifts image to the left
            val transX = (0.5f - posX) * 2.0f * scale
            val transY = (posY - 0.5f) * 2.0f * scale

            Matrix.translateM(mvpMatrix, 0, transX, transY, 0f)
            Matrix.scaleM(mvpMatrix, 0, scale, scale, 1.0f)
            if (rotationDeg != 0f) {
                Matrix.rotateM(mvpMatrix, 0, rotationDeg, 0f, 0f, 1f)
            }

            GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        }

        fun setPresentationTime(nsecs: Long) {
            android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
        }

        fun swapBuffers() {
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        fun release() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }
}
