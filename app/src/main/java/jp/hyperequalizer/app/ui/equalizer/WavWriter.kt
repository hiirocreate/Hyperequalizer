package jp.hyperequalizer.app.ui.equalizer

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16bit PCM の ShortArray を標準的な WAVファイルとして書き出すユーティリティ。
 */
object WavWriter {

    /** 一括版: 既にメモリ上にある全データを一度に書き出す(AIモデルによる分離処理など、少量データ向け)。 */
    fun write(outFile: File, pcm: ShortArray, sampleRate: Int, channels: Int) {
        StreamWriter(outFile, sampleRate, channels).use { it.append(pcm) }
    }

    /**
     * WAVファイルへチャンク単位で追記していくためのストリーミング版ライター。
     *
     * ボーカル/伴奏分離処理では、以前は分離後のPCM全体を一度にこの[write]へ渡して
     * いたため、長い音声ファイルでは巨大な配列をメモリ上に保持する必要があった。
     * このクラスを使うと「デコード→分離→書き込み」をチャンク単位で流しながら
     * 処理できるため、ファイル全体をメモリに載せずに済む(メモリ使用量削減対策)。
     *
     * 先頭に仮の44バイトヘッダーを書いてからPCMチャンクを順次追記し、
     * [close](または`use{}`)のタイミングで実際のサイズに基づきヘッダーを書き直す。
     */
    class StreamWriter(outFile: File, private val sampleRate: Int, private val channels: Int) : AutoCloseable {
        private val raf = RandomAccessFile(outFile, "rw").apply {
            setLength(0)
            seek(44) // ヘッダーは最後(close時)に書き直すので、まず44バイト分space確保しておく
        }
        private var dataSize = 0L
        private var closed = false

        fun append(pcm: ShortArray, length: Int = pcm.size) {
            if (length <= 0) return
            val buffer = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until length) buffer.putShort(pcm[i])
            raf.write(buffer.array())
            dataSize += length * 2L
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                val byteRate = sampleRate * channels * 2
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray())
                header.putInt((36 + dataSize).toInt())
                header.put("WAVE".toByteArray())
                header.put("fmt ".toByteArray())
                header.putInt(16) // PCM chunk size
                header.putShort(1) // PCM format
                header.putShort(channels.toShort())
                header.putInt(sampleRate)
                header.putInt(byteRate)
                header.putShort((channels * 2).toShort()) // block align
                header.putShort(16) // bits per sample
                header.put("data".toByteArray())
                header.putInt(dataSize.toInt())
                raf.seek(0)
                raf.write(header.array())
            } finally {
                // ヘッダー書き込みが失敗しても、開いたファイルハンドルは必ず閉じる
                // (ここで閉じ漏れると、繰り返し試したときにファイルディスクリプタを
                // 使い果たしてクラッシュする原因になり得る)
                raf.close()
            }
        }
    }
}
