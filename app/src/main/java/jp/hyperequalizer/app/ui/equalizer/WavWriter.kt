package jp.hyperequalizer.app.ui.equalizer

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16bit PCM の ShortArray を標準的な WAVファイルとして書き出すユーティリティ。
 */
object WavWriter {

    fun write(outFile: File, pcm: ShortArray, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2
        val dataSize = pcm.size * 2
        RandomAccessFile(outFile, "rw").use { raf ->
            raf.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataSize)
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
            header.putInt(dataSize)
            raf.write(header.array())

            val buffer = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcm) buffer.putShort(sample)
            raf.write(buffer.array())
        }
    }
}
