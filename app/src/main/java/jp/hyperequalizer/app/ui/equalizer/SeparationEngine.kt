package jp.hyperequalizer.app.ui.equalizer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

data class SeparationResult(val vocalPath: String, val instrumentalPath: String, val usedAiModel: Boolean)

/**
 * 音声を「ボーカル」と「伴奏(楽器)」に分離する処理エンジン。
 *
 * 1. assets/models/vocal_separator.tflite が存在する場合は、その学習済み
 *    TensorFlow Liteモデルで分離を試みる(モデルの入出力テンソル形状は実行時に
 *    読み取り、想定と異なる/失敗した場合は自動的に(2)にフォールバックする)。
 * 2. モデルが無い、または処理に失敗した場合は「簡易センターチャンネル抽出」
 *    (ミッドサイド処理によるカラオケ的手法)でオフライン分離する。
 *    - center = (L+R)/2 … ボーカルや中央定位の音を多く含む
 *    - side   = (L-R)/2 … 左右に広がる伴奏(楽器)を多く含む
 *    完璧な分離ではないが、追加ファイル無しでその場で動作する。
 *
 * (2)のフォールバック処理が実際にはほぼ常に使われるルートであるため、
 * こちらは「デコード→分離→WAV書き込み」をパイプライン化して高速化してある。
 * - チャンク単位で処理するため、ファイル全体を一度にメモリへ載せない
 *   (以前は数分の音声でも数百MB規模のメモリを一時的に確保していた)。
 * - デコード/ミッドサイド分離(CPU側の処理)とディスクへのWAV書き込み(I/O待ちが
 *   発生し得る処理)を別スレッドで並行して行う(生産者/消費者パターン)。
 *   以前は「1チャンクをデコード→分離→書き込みが終わるまで次のチャンクを
 *   デコードしない」という完全な直列処理だったため、ディスクI/Oの待ち時間が
 *   そのまま分離処理全体の所要時間に上乗せされていた。書き込み専用スレッドを
 *   分離することで、書き込み待ちの間にも次のチャンクのデコード・分離を
 *   進められるようになり、体感速度が大きく改善する。
 *
 * @throws IllegalStateException 読み込み/デコードできなかった理由付き(失敗画面に表示するため)
 */
class SeparationEngine(private val context: Context) {

    // 進捗コールバックはDispatchers.Defaultのスレッドから呼ばれるが、呼び出し元は
    // 多くの場合UIを直接更新するため、必ずメインスレッドへ乗せ替えてから呼び出す。
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun separate(
        uri: Uri,
        onProgress: (Int) -> Unit
    ): SeparationResult = withContext(Dispatchers.Default) {
        reportProgress(onProgress, 2)
        val modelFile = copyAssetModelToCacheIfPresent()
        if (modelFile != null) {
            val aiResult = tryAiSeparationFull(uri, modelFile, onProgress)
            if (aiResult != null) return@withContext aiResult
            // モデルはあるが推論に失敗した場合は、下のストリーミング版フォールバックへ続行する
        }
        separateCenterChannelStreaming(uri, onProgress)
    }

    private fun reportProgress(onProgress: (Int) -> Unit, percent: Int) {
        val p = percent.coerceIn(0, 100)
        mainHandler.post { onProgress(p) }
    }

    /** デコード/分離スレッドから書き込みスレッドへ渡す1チャンク分のデータ */
    private class WriteJob(val vocal: ShortArray, val vocalLen: Int, val instrumental: ShortArray, val instLen: Int)

    /**
     * 通常ケース(AIモデル未配置、またはAI推論失敗時)のフォールバック処理。
     * [AudioDecoder.decodeStreaming] でチャンクを受け取るたびにその場で
     * ミッドサイド分離し、専用の書き込みスレッドへ渡して [WavWriter.StreamWriter] で
     * 都度ファイルへ追記する(デコード/分離とディスク書き込みを並行実行するため)。
     */
    private fun separateCenterChannelStreaming(uri: Uri, onProgress: (Int) -> Unit): SeparationResult {
        val outDir = File(context.cacheDir, "separated/${uri.toString().hashCode()}")
        outDir.mkdirs()
        val vocalFile = File(outDir, "vocal.wav")
        val instrumentalFile = File(outDir, "instrumental.wav")

        // 書き込みスレッドがディスクI/O待ちで詰まった場合に、デコード側が無制限に
        // メモリを積み上げてしまわないよう、キューの容量には上限を設けてある
        // (満杯になるとput()側がブロックし、自然にデコード側の速度も抑えられる)。
        val queue = ArrayBlockingQueue<WriteJob>(WRITE_QUEUE_CAPACITY)
        val poisonPill = WriteJob(ShortArray(0), 0, ShortArray(0), 0)
        val writerError = AtomicReference<Throwable?>(null)
        var writers: Pair<WavWriter.StreamWriter, WavWriter.StreamWriter>? = null
        var didStartWriterThread = false

        val writerThread = Thread({
            try {
                while (true) {
                    val job = queue.take()
                    if (job === poisonPill) break
                    // writerThread.start()は必ずwriters代入後に呼ばれ、JMM上
                    // Thread.start()より前の書き込みは開始したスレッドから見えることが
                    // 保証されるため、ここで非nullであることは保証されている。
                    val (vocalWriter, instrumentalWriter) = writers!!
                    vocalWriter.append(job.vocal, job.vocalLen)
                    instrumentalWriter.append(job.instrumental, job.instLen)
                }
            } catch (e: InterruptedException) {
                // close()側からの後始末目的の割り込みは正常系として無視する
            } catch (e: Throwable) {
                writerError.set(e)
            }
        }, "SeparationWriter")

        var lastReported = -1

        try {
            AudioDecoder.decodeStreaming(
                context = context,
                uri = uri,
                onProgress = AudioDecoder.ProgressListener { fraction ->
                    // デコード〜書き込みまでを合わせて2%〜95%の範囲で進捗表示する
                    val pct = (2 + fraction * 93).toInt().coerceIn(2, 95)
                    if (pct != lastReported) {
                        lastReported = pct
                        reportProgress(onProgress, pct)
                    }
                }
            ) { chunk, length, format ->
                if (writers == null) {
                    writers = WavWriter.StreamWriter(vocalFile, format.sampleRate, format.channels) to
                        WavWriter.StreamWriter(instrumentalFile, format.sampleRate, format.channels)
                    writerThread.start()
                    didStartWriterThread = true
                }
                writerError.get()?.let { throw it }
                val (vocalChunk, instrumentalChunk) = centerChannelSeparateChunk(chunk, length, format.channels)
                queue.put(WriteJob(vocalChunk, length, instrumentalChunk, length))
            }
        } finally {
            // ここでの後始末そのものが例外を投げてしまうと、デコード側で発生した
            // 本来の(原因が分かる)例外を握りつぶして上書きしてしまうため、
            // 後始末中の例外はすべて無視して元の例外を優先させる。
            try {
                if (didStartWriterThread) {
                    queue.put(poisonPill)
                    writerThread.join()
                }
            } catch (e: Throwable) {
                // 無視(元の例外を優先)
            }
            try {
                writers?.let { (v, i) -> v.close(); i.close() }
            } catch (e: Throwable) {
                // 無視(元の例外を優先)
            }
        }

        writerError.get()?.let { throw it }

        if (writers == null) {
            // 音声データが1バイトも得られなかった(空ファイル/極端に短いファイルなど)
            throw IllegalStateException("音声データを取得できませんでした")
        }
        reportProgress(onProgress, 100)
        return SeparationResult(vocalFile.absolutePath, instrumentalFile.absolutePath, usedAiModel = false)
    }

    /**
     * 1チャンク分のミッドサイド処理による簡易分離。
     * モノラルの場合はセンター抽出ができないため、両トラックとも原音をそのまま複製して返す。
     */
    private fun centerChannelSeparateChunk(pcm: ShortArray, length: Int, channels: Int): Pair<ShortArray, ShortArray> {
        if (channels != 2) {
            val copy = if (length == pcm.size) pcm.copyOf() else pcm.copyOf(length)
            return Pair(copy, copy.copyOf())
        }
        val n = length / 2
        val vocal = ShortArray(n * 2)
        val instrumental = ShortArray(n * 2)
        for (i in 0 until n) {
            val l = pcm[i * 2].toInt()
            val r = pcm[i * 2 + 1].toInt()
            val center = (l + r) / 2
            val side = (l - r) / 2
            // ボーカル(推定): 中央定位成分(center)を両chに複製
            vocal[i * 2] = clampShort(center)
            vocal[i * 2 + 1] = clampShort(center)
            // 伴奏(推定): 中央成分を打ち消したサイド成分(l=center+side, r=center-side より
            // l-center=side, r-center=-side)
            instrumental[i * 2] = clampShort(side)
            instrumental[i * 2 + 1] = clampShort(-side)
        }
        return Pair(vocal, instrumental)
    }

    private fun clampShort(v: Int): Short = max(-32768, min(32767, v)).toShort()

    /**
     * assets/models/vocal_separator.tflite が存在する場合の処理。TFLiteモデルは
     * 固定長ウィンドウでの推論が前提のため、こちらは従来通り音声全体を一度に
     * メモリへ載せてから処理する(ストリーミング化していない)。モデル未配置が
     * 通常ケースであり、この関数が実際に呼ばれることは現状ない。
     * 推論に失敗した場合はnullを返し、呼び出し元がフォールバックへ切り替える。
     */
    private fun tryAiSeparationFull(uri: Uri, modelFile: File, onProgress: (Int) -> Unit): SeparationResult? {
        return try {
            val decoded = AudioDecoder.decode(context, uri)
            reportProgress(onProgress, 35)
            val interpreter = Interpreter(loadModelBuffer(modelFile))
            val result = runInterpreter(interpreter, decoded)
            interpreter.close()
            if (result == null) return null
            reportProgress(onProgress, 70)
            val outDir = File(context.cacheDir, "separated/${uri.toString().hashCode()}")
            outDir.mkdirs()
            val vocalFile = File(outDir, "vocal.wav")
            val instrumentalFile = File(outDir, "instrumental.wav")
            WavWriter.write(vocalFile, result.first, decoded.sampleRate, decoded.channels)
            WavWriter.write(instrumentalFile, result.second, decoded.sampleRate, decoded.channels)
            reportProgress(onProgress, 100)
            SeparationResult(vocalFile.absolutePath, instrumentalFile.absolutePath, usedAiModel = true)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            // AIモデル経路は音声全体を一括でメモリに載せるため、大きなファイルでは
            // 発生し得る。ここで確実に拾ってフォールバック経路へ切り替えさせる。
            null
        }
    }

    private fun runInterpreter(interpreter: Interpreter, decoded: DecodedAudio): Pair<ShortArray, ShortArray>? {
        // モデルは [1, N] のfloat32モノラル波形を入力し、
        // 出力0=ボーカル、出力1=伴奏 (いずれも [1, N]) を返すことを想定する。
        // 想定外の形状の場合は null を返しフォールバックさせる。
        if (interpreter.outputTensorCount < 2) return null
        val inputShape = interpreter.getInputTensor(0).shape()
        val chunkSamples = inputShape.lastOrNull { it > 1 } ?: return null

        // ステレオ→モノラルに変換して処理(モデルはモノラル入力を想定)
        val mono = toMono(decoded)
        val totalVocal = FloatArray(mono.size)
        val totalInstrumental = FloatArray(mono.size)

        var offset = 0
        while (offset < mono.size) {
            val end = min(offset + chunkSamples, mono.size)
            val inputArray = Array(1) { FloatArray(chunkSamples) }
            for (i in offset until end) {
                inputArray[0][i - offset] = mono[i]
            }
            val outputs = HashMap<Int, Any>()
            val vocalOut = Array(1) { FloatArray(chunkSamples) }
            val instOut = Array(1) { FloatArray(chunkSamples) }
            outputs[0] = vocalOut
            outputs[1] = instOut
            interpreter.runForMultipleInputsOutputs(arrayOf(inputArray), outputs)
            for (i in offset until end) {
                totalVocal[i] = vocalOut[0][i - offset]
                totalInstrumental[i] = instOut[0][i - offset]
            }
            offset = end
        }

        val vocalPcm = fromMonoToOutputChannels(totalVocal, decoded.channels)
        val instPcm = fromMonoToOutputChannels(totalInstrumental, decoded.channels)
        return Pair(vocalPcm, instPcm)
    }

    private fun toMono(decoded: DecodedAudio): FloatArray {
        return if (decoded.channels == 2) {
            val n = decoded.pcm.size / 2
            FloatArray(n) { i ->
                ((decoded.pcm[i * 2].toInt() + decoded.pcm[i * 2 + 1].toInt()) / 2f) / 32768f
            }
        } else {
            FloatArray(decoded.pcm.size) { i -> decoded.pcm[i] / 32768f }
        }
    }

    private fun fromMonoToOutputChannels(mono: FloatArray, channels: Int): ShortArray {
        return if (channels == 2) {
            ShortArray(mono.size * 2).also { out ->
                for (i in mono.indices) {
                    val v = clampShort((mono[i] * 32768f).toInt())
                    out[i * 2] = v
                    out[i * 2 + 1] = v
                }
            }
        } else {
            ShortArray(mono.size) { i -> clampShort((mono[i] * 32768f).toInt()) }
        }
    }

    private fun copyAssetModelToCacheIfPresent(): File? {
        return try {
            val assetPath = "models/$MODEL_ASSET_NAME"
            val outFile = File(context.cacheDir, MODEL_ASSET_NAME)
            if (!outFile.exists()) {
                context.assets.open(assetPath).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            outFile
        } catch (e: Exception) {
            null // モデル未配置(通常ケース) or 読み込み失敗
        }
    }

    private fun loadModelBuffer(file: File): MappedByteBuffer {
        FileInputStream(file).use { stream ->
            val channel = stream.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
    }

    companion object {
        private const val MODEL_ASSET_NAME = "vocal_separator.tflite"

        /** デコード/分離スレッドが書き込みスレッドをどれだけ先行できるか(チャンク数の上限) */
        private const val WRITE_QUEUE_CAPACITY = 32
    }
}
