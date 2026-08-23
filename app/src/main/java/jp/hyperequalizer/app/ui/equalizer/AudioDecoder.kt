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

/** ストリーミングデコード時に判明する音声フォーマット情報 */
data class AudioFormatInfo(val sampleRate: Int, val channels: Int)

/**
 * MediaExtractor + MediaCodec を用いて、動画/音楽ファイルの音声トラックを
 * 16bit PCM (リトルエンディアン, インターリーブ) にデコードする。
 * ボーカル分離処理の前段として使用する。
 *
 * [decode] は従来通りの一括版(結果を丸ごとメモリに載せる)。AIモデルによる
 * 分離処理のように、推論の都合上どうしても全体が必要な場合にのみ使う。
 * [decodeStreaming] はチャンク単位で結果を都度コールバックへ渡す版で、
 * 通常のセンターチャンネル抽出フォールバック処理はこちらを使うことで、
 * ファイル全体を一度にメモリへ載せずに済む(メモリ使用量削減対策)。
 */
object AudioDecoder {

    /** [decodeStreaming]の進捗コールバック。0.0〜1.0のおおよその処理割合を渡す。 */
    fun interface ProgressListener {
        fun onProgress(fraction: Float)
    }

    /**
     * 音声トラックを一括デコードして結果をメモリ上にまとめて返す。
     * メモリ使用量を抑えるため、先頭 [maxDurationMs] ミリ秒までのみをデコードする
     * (デフォルト8分)。
     *
     * @throws IllegalStateException 読み込み/デコードできなかった理由付き(失敗画面に表示するため)
     */
    fun decode(context: Context, uri: Uri, maxDurationMs: Long = 8 * 60 * 1000L): DecodedAudio {
        var sampleRate = 44100
        var channels = 2
        val output = ByteArrayOutputStream()
        decodeStreaming(context, uri, maxDurationMs) { chunk, length, format ->
            sampleRate = format.sampleRate
            channels = format.channels
            val buffer = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until length) buffer.putShort(chunk[i])
            output.write(buffer.array())
        }
        val bytes = output.toByteArray()
        val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcm = ShortArray(shortBuffer.remaining())
        shortBuffer.get(pcm)
        return DecodedAudio(pcm, sampleRate, channels)
    }

    /**
     * 音声トラックをチャンク単位でデコードしながら [onChunk] へ順次渡す
     * (呼び出し元と同じスレッドで同期的に呼ばれる)。デコード結果をまとめて
     * メモリに保持しないため、長い音声ファイルでもピーク時のメモリ使用量を
     * チャンク1つ分(数十〜数百KB程度)に抑えられる。
     *
     * 以前は全PCMデータを一度にメモリへ載せていたため、数分の音声でも数百MB規模の
     * メモリを消費し、低メモリ端末で極端に遅くなったり(体感で「処理が全然進まない」
     * ように見える)、メモリ不足の一因になっていた。
     *
     * @throws IllegalStateException 読み込み/デコードできなかった理由付き(失敗画面に表示するため)
     */
    fun decodeStreaming(
        context: Context,
        uri: Uri,
        maxDurationMs: Long = 30 * 60 * 1000L,
        onProgress: ProgressListener? = null,
        onChunk: (pcm: ShortArray, length: Int, format: AudioFormatInfo) -> Unit
    ) {
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
        val audioFormat = AudioFormatInfo(sampleRate, channels)

        val codec = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            extractor.release()
            throw IllegalStateException("この音声形式(${mime})はデコードできませんでした", e)
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        val maxBytes = (maxDurationMs * sampleRate / 1000L * channels * 2L)
        var processedBytes = 0L

        // MediaCodecが入力も出力もまったく進まない(壊れた/対応していないストリームなど)
        // 状態に陥った場合に無限ループしてしまわないよう、進捗の無いまま繰り返した回数を
        // 数えておき、一定回数を超えたら諦めて例外を投げる(呼び出し側のタイムアウトより
        // 手前で、原因を明示した形で早めに失敗させるための保険)。
        var stalledIterations = 0

        try {
            while (!sawOutputEOS && processedBytes < maxBytes) {
                var progressedThisIteration = false

                if (!sawInputEOS) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        progressedThisIteration = true
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
                    progressedThisIteration = true
                    if (bufferInfo.size > 0) {
                        val outBuffer = codec.getOutputBuffer(outputIndex)
                        if (outBuffer != null) {
                            outBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val shortBuffer = outBuffer.asShortBuffer()
                            val chunk = ShortArray(shortBuffer.remaining())
                            shortBuffer.get(chunk)
                            if (chunk.isNotEmpty()) {
                                onChunk(chunk, chunk.size, audioFormat)
                                processedBytes += chunk.size * 2L
                                onProgress?.onProgress((processedBytes.toFloat() / maxBytes.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                        break
                    }
                    if (processedBytes >= maxBytes) break
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                }

                if (progressedThisIteration) {
                    stalledIterations = 0
                } else {
                    stalledIterations++
                    if (stalledIterations > MAX_STALLED_ITERATIONS) {
                        throw IllegalStateException("音声のデコードが進行しませんでした(非対応の形式の可能性があります)")
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
    }

    // dequeueInputBuffer/dequeueOutputBufferのタイムアウトが10msなので、
    // 6000回(=約60秒間、入力・出力どちらも一切進まない)で見切りをつける。
    private const val MAX_STALLED_ITERATIONS = 6000
}
