package jp.hyperequalizer.app.util

import android.content.Context
import android.media.AudioManager

/**
 * 動画右側の縦スライドで使用する端末メディア音量コントローラ。
 */
class VolumeController(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    fun currentRatio(): Float {
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return cur.toFloat() / maxVolume.toFloat()
    }

    fun setRatio(ratio: Float) {
        val clamped = ratio.coerceIn(0f, 1f)
        val target = (clamped * maxVolume).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    fun adjustBy(deltaRatio: Float): Float {
        val newRatio = (currentRatio() + deltaRatio).coerceIn(0f, 1f)
        setRatio(newRatio)
        return newRatio
    }
}
