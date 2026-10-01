package com.example.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.model.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object VideoAnalyzer {

    suspend fun extractMetadata(context: Context, uri: Uri, fallbackTitle: String): VideoSource = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            var width = widthStr?.toIntOrNull() ?: 1920
            var height = heightStr?.toIntOrNull() ?: 1080

            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val rotation = rotationStr?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val temp = width
                width = height
                height = temp
            }

            // Estimate FPS
            val frameCountStr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            } else null
            val frameCount = frameCountStr?.toIntOrNull()
            val fps = if (frameCount != null && frameCount > 0 && durationMs > 0) {
                (frameCount * 1000f) / durationMs
            } else {
                30.0f
            }

            // File size
            var fileSizeBytes = 0L
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    fileSizeBytes = pfd.statSize
                }
            } catch (_: Exception) {}

            val title = uri.lastPathSegment?.substringAfterLast('/') ?: fallbackTitle

            VideoSource(
                uri = uri,
                title = title,
                durationMs = durationMs,
                width = width,
                height = height,
                fps = fps,
                rotation = rotation,
                fileSizeBytes = fileSizeBytes,
                isSample = false
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Uses MediaExtractor to step through all frames of the reference video
     * and extract their presentation timestamps in milliseconds.
     */
    suspend fun extractFrameTimestampsWithExtractor(
        context: Context,
        uri: Uri
    ): List<Long> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        val timestampsMs = mutableListOf<Long>()
        try {
            extractor.setDataSource(context, uri, null)
            var videoTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    break
                }
            }

            if (videoTrackIndex >= 0) {
                extractor.selectTrack(videoTrackIndex)
                while (true) {
                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs < 0) break

                    // Exact frame timestamp converted from microseconds to milliseconds
                    val timeMs = sampleTimeUs / 1000L
                    timestampsMs.add(timeMs)

                    if (!extractor.advance()) break
                }
            }
        } finally {
            extractor.release()
        }
        timestampsMs
    }
}
