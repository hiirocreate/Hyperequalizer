package jp.hyperequalizer.app.ui.equalizer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
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
 */
class SeparationEngine(private val context: Context) {

    suspend fun separate(
        uri: Uri,
        onProgress: (Int) -> Unit
    ): SeparationResult? = withContext(Dispatchers.Default) {
        onProgress(5)
        val decoded = AudioDecoder.decode(context, uri) ?: return@withContext null
        onProgress(35)

        val aiResult = tryAiSeparation(decoded)
        onProgress(70)

        val (vocalPcm, instrumentalPcm, usedAi) = if (aiResult != null) {
            Triple(aiResult.first, aiResult.second, true)
        } else {
            val fallback = centerChannelSeparate(decoded)
            Triple(fallback.first, fallback.second, false)
        }
        onProgress(90)

        val outDir = File(context.cacheDir, "separated/${uri.toString().hashCode()}")
        outDir.mkdirs()
        val vocalFile = File(outDir, "vocal.wav")
        val instrumentalFile = File(outDir, "instrumental.wav")
        WavWriter.write(vocalFile, vocalPcm, decoded.sampleRate, decoded.channels)
        WavWriter.write(instrumentalFile, instrumentalPcm, decoded.sampleRate, decoded.channels)
        onProgress(100)

        SeparationResult(vocalFile.absolutePath, instrumentalFile.absolutePath, usedAi)
    }

    /**
     * ミッドサイド処理による簡易分離(フォールバック)。
     * モノラル音源の場合はセンター抽出ができないため、両トラックとも原音をそのまま返す。
     */
    private fun centerChannelSeparate(decoded: DecodedAudio): Pair<ShortArray, ShortArray> {
        if (decoded.channels != 2) {
            return Pair(decoded.pcm.copyOf(), decoded.pcm.copyOf())
        }
        val n = decoded.pcm.size / 2
        val vocal = ShortArray(decoded.pcm.size)
        val instrumental = ShortArray(decoded.pcm.size)
        for (i in 0 until n) {
            val l = decoded.pcm[i * 2].toInt()
            val r = decoded.pcm[i * 2 + 1].toInt()
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
     * assets/models/vocal_separator.tflite が存在すれば読み込み、分離を試みる。
     * モデルの入出力テンソル形状は実行時に検査し、扱えない場合や例外発生時は null を返す
     * (呼び出し側でフォールバック処理される)。
     */
    private fun tryAiSeparation(decoded: DecodedAudio): Pair<ShortArray, ShortArray>? {
        val modelFile = copyAssetModelToCacheIfPresent() ?: return null
        return try {
            val interpreter = Interpreter(loadModelBuffer(modelFile))
            val result = runInterpreter(interpreter, decoded)
            interpreter.close()
            result
        } catch (e: Exception) {
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
    }
}
