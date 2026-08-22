package jp.hyperequalizer.app.util

import android.content.Context
import android.media.AudioManager

/**
 * 動画右側の縦スライドで使用する端末メディア音量コントローラ。
 *
 * 端末のメディア音量(STREAM_MUSIC)は多くの機種で15段階前後しかない整数値のため、
 * 以前は毎回 [adjustBy] のたびに実際のシステム音量(整数)を読み直してから差分を
 * 足していた。指でのドラッグは1回のジェスチャーで何十回も小さな差分(例: 0.005など)
 * を送ってくるが、それぞれ整数ステップに満たない変化は四捨五入で消えてしまい、
 * 「ドラッグしてもなかなか音量が変わらない」という状態になっていた。
 * これを避けるため、内部では連続値(0.0〜1.0)の比率を保持し続け、そちらに
 * 差分を積算してから実際のシステム音量へ反映するようにしてある。
 */
class VolumeController(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    private var internalRatio: Float = readSystemRatio()

    private fun readSystemRatio(): Float {
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return cur.toFloat() / maxVolume.toFloat()
    }

    fun currentRatio(): Float = internalRatio

    fun setRatio(ratio: Float) {
        val clamped = ratio.coerceIn(0f, 1f)
        internalRatio = clamped
        val target = (clamped * maxVolume).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    fun adjustBy(deltaRatio: Float): Float {
        val newRatio = (internalRatio + deltaRatio).coerceIn(0f, 1f)
        setRatio(newRatio)
        return newRatio
    }
}
