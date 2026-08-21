package jp.hyperequalizer.app.ui.editor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Media3 Transformer を用いて、トリミング/結合/音声無効化を反映した
 * 1本の動画ファイルに書き出す。
 *
 * 注意: Composition / EditedMediaItemSequence まわりのAPIはMedia3の中でも
 * 特に変更が入りやすい実験的(@UnstableApi)領域のため、依存しているMedia3の
 * バージョンによっては細部のメソッド名/シグネチャの調整が必要になる場合がある。
 * その場合は本ファイルのみを修正すれば良いよう、書き出しロジックをこのクラスに
 * 集約している。
 */
@UnstableApi
class VideoExporter(private val context: Context) {

    interface Callback {
        fun onProgress(percent: Int)
        fun onSuccess(outputUri: Uri)
        fun onError(message: String)
    }

    private var transformer: Transformer? = null
    private var progressPollRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    fun export(clips: List<EditorClip>, callback: Callback) {
        if (clips.isEmpty()) {
            callback.onError("クリップがありません")
            return
        }

        try {
            val outputDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "exported")
            outputDir.mkdirs()
            val outputFile = File(outputDir, "edited_${System.currentTimeMillis()}.mp4")

            val editedItems = clips.map { clip ->
                val builder = MediaItem.Builder().setUri(clip.uri)
                if (clip.trimStartMs > 0 || clip.trimEndMs > 0) {
                    val clipConfig = MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimStartMs.coerceAtLeast(0))
                    if (clip.trimEndMs > clip.trimStartMs) {
                        clipConfig.setEndPositionMs(clip.trimEndMs)
                    }
                    builder.setClippingConfiguration(clipConfig.build())
                }
                EditedMediaItem.Builder(builder.build())
                    .setRemoveAudio(clip.muted)
                    .build()
            }

            val sequence = EditedMediaItemSequence(editedItems)
            val composition = Composition.Builder(listOf(sequence)).build()

            val t = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        stopProgressPolling()
                        val uri = saveToMediaStore(outputFile)
                        if (uri != null) callback.onSuccess(uri) else callback.onError("保存に失敗しました")
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        stopProgressPolling()
                        callback.onError(exportException.message ?: "書き出し中にエラーが発生しました")
                    }
                })
                .build()
            transformer = t
            t.start(composition, outputFile.absolutePath)
            startProgressPolling(callback)
        } catch (e: Exception) {
            callback.onError(e.message ?: "書き出しの初期化に失敗しました")
        }
    }

    private fun startProgressPolling(callback: Callback) {
        val holder = ProgressHolder()
        val runnable = object : Runnable {
            override fun run() {
                val state = transformer?.getProgress(holder)
                if (state != null && state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    callback.onProgress(holder.progress)
                }
                if (transformer != null) {
                    handler.postDelayed(this, 300)
                }
            }
        }
        progressPollRunnable = runnable
        handler.post(runnable)
    }

    private fun stopProgressPolling() {
        progressPollRunnable?.let { handler.removeCallbacks(it) }
        progressPollRunnable = null
        transformer = null
    }

    fun cancel() {
        stopProgressPolling()
        transformer?.cancel()
    }

    private fun saveToMediaStore(file: File): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HyperEqualizer")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            uri
        } catch (e: Exception) {
            null
        }
    }
}
