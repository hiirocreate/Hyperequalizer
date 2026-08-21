package jp.hyperequalizer.app.extract

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import jp.hyperequalizer.app.ui.equalizer.AudioDecoder
import jp.hyperequalizer.app.ui.equalizer.WavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

enum class ExtractFormat { M4A, WAV }

data class ExtractResult(val uri: Uri, val format: ExtractFormat)

/**
 * 動画ファイルから音声だけを取り出し、単体の音声ファイルとして保存する機能。
 *
 * - M4A(推奨): 動画内の音声トラックを再エンコードせずにそのままコピーする
 *   (MediaExtractor→MediaMuxerでのトラックコピー)。処理が高速で、音質の劣化も
 *   一切ない。ほとんどのmp4動画(AAC音声)で利用できる。
 * - WAV: 一度PCMにデコードしてから書き出す方式。どんな動画からでも確実に
 *   書き出せる代わりに、ファイルサイズが大きくなる(無圧縮)。
 *
 * 注意: Android標準のAPIだけでは「MP3」形式そのもののエンコードはできません
 * (MediaCodecは一般的にMP3の"デコード"には対応していますが"エンコード"には
 * 対応していない端末がほとんどです)。そのため本機能はMP3の代わりに、
 * 上記2形式(M4A / WAV)で書き出します。WAVファイルは他の変換アプリ/PC上の
 * ツール(iTunesやffmpeg、オンライン変換サービスなど)で簡単にMP3へ変換できます。
 */
object AudioExtractor {

    suspend fun extract(context: Context, sourceUri: Uri, displayName: String, format: ExtractFormat): ExtractResult? =
        withContext(Dispatchers.IO) {
            val baseName = displayName.substringBeforeLast('.').ifBlank { "audio_${System.currentTimeMillis()}" }
            when (format) {
                ExtractFormat.M4A -> extractCopyToM4a(context, sourceUri, baseName)
                ExtractFormat.WAV -> extractDecodeToWav(context, sourceUri, baseName)
            }
        }

    /** 音声トラックを再エンコードせずにそのままM4A(AAC)コンテナへコピーする */
    private fun extractCopyToM4a(context: Context, sourceUri: Uri, baseName: String): ExtractResult? {
        val tempFile = File(context.cacheDir, "extract_${System.currentTimeMillis()}.m4a")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, sourceUri, null)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = f
                    break
                }
            }
            if (audioTrackIndex == -1 || audioFormat == null) return null
            extractor.selectTrack(audioTrackIndex)

            muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(1 shl 20) // 1MB
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }
            muxer.stop()
        } catch (e: Exception) {
            tempFile.delete()
            return null
        } finally {
            try { muxer?.release() } catch (e: Exception) { /* no-op */ }
            extractor.release()
        }

        val uri = saveToMediaStore(context, tempFile, "$baseName.m4a", "audio/mp4")
        tempFile.delete()
        return uri?.let { ExtractResult(it, ExtractFormat.M4A) }
    }

    /** 一度PCMへデコードしてからWAVとして書き出す(どんな動画/音声形式でも確実に動作する) */
    private fun extractDecodeToWav(context: Context, sourceUri: Uri, baseName: String): ExtractResult? {
        // WAVは無圧縮のためメモリを多く使う。長時間動画でのメモリ不足を避けるため
        // 先頭60分までを対象とする。
        val decoded = AudioDecoder.decode(context, sourceUri, maxDurationMs = 60 * 60 * 1000L) ?: return null
        val tempFile = File(context.cacheDir, "extract_${System.currentTimeMillis()}.wav")
        return try {
            WavWriter.write(tempFile, decoded.pcm, decoded.sampleRate, decoded.channels)
            val uri = saveToMediaStore(context, tempFile, "$baseName.wav", "audio/wav")
            tempFile.delete()
            uri?.let { ExtractResult(it, ExtractFormat.WAV) }
        } catch (e: Exception) {
            tempFile.delete()
            null
        }
    }

    private fun saveToMediaStore(context: Context, file: File, outputName: String, mimeType: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, outputName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/HyperEqualizer")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            uri
        } catch (e: Exception) {
            null
        }
    }
}
