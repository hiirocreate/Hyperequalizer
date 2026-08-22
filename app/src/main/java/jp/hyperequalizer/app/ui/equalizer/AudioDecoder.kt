package jp.hyperequalizer.app.ui.equalizer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedAudio(val pcm: ShortArray, val sampleRate: Int, val channels: Int)

/**
 * MediaExtractor + MediaCodec を用いて、動画/音楽ファイルの音声トラックを
 * 16bit PCM (リトルエンディアン, インターリーブ) にデコードする。
 * ボーカル分離処理の前段として使用する。
 *
 * メモリ使用量を抑えるため、先頭 [maxDurationMs] ミリ秒までのみをデコードする
 * (デフォルト8分)。
 */
object AudioDecoder {

    /** @throws IllegalStateException 読み込み/デコードできなかった理由付き(失敗画面に表示するため) */
    fun decode(context: Context, uri: Uri, maxDurationMs: Long = 8 * 60 * 1000L): DecodedAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            extractor.release()
            throw IllegalStateException("音声データを開けませんでした", e)
        }

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex == -1 || format == null) {
            extractor.release()
            throw IllegalStateException("音声トラックが見つかりませんでした")
        }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

        val codec = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            extractor.release()
            throw IllegalStateException("この音声形式(${mime})はデコードできませんでした", e)
        }

        val output = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        val maxBytes = (maxDurationMs * sampleRate / 1000L * channels * 2L)

        try {
            while (!sawOutputEOS && output.size() < maxBytes) {
                if (!sawInputEOS) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = if (inputBuffer != null) extractor.readSampleData(inputBuffer, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val presentationTime = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outBuffer = codec.getOutputBuffer(outputIndex)
                        if (outBuffer != null) {
                            val chunk = ByteArray(bufferInfo.size)
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outBuffer.get(chunk)
                            output.write(chunk)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                        break
                    }
                    if (output.size() >= maxBytes) break
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        val bytes = output.toByteArray()
        val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcm = ShortArray(shortBuffer.remaining())
        shortBuffer.get(pcm)
        return DecodedAudio(pcm, sampleRate, channels)
    }
}
