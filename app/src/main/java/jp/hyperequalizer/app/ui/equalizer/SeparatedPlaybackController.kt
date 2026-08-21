package jp.hyperequalizer.app.ui.equalizer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * ボーカル/伴奏に分離済みの2つの音声ファイルを、メイン動画/音楽プレイヤーと
 * 同期させながら独立した音量で再生するコントローラ。
 *
 * メインプレイヤー(動画や元音源)はミュートし、代わりにこのクラスが保持する
 * 2つのExoPlayerインスタンス(ボーカル用/伴奏用)を同じ再生位置・速度で
 * 再生することで、疑似的に「音楽とボーカルの声を個別で音量調整」を実現する。
 */
@UnstableApi
class SeparatedPlaybackController(context: Context) {

    private val vocalPlayer = ExoPlayer.Builder(context).build()
    private val instrumentalPlayer = ExoPlayer.Builder(context).build()
    private val handler = Handler(Looper.getMainLooper())
    private var syncRunnable: Runnable? = null
    var isActive = false
        private set

    fun start(vocalPath: String, instrumentalPath: String, mainPositionMs: Long, playWhenReady: Boolean, speed: Float) {
        vocalPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(java.io.File(vocalPath))))
        instrumentalPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(java.io.File(instrumentalPath))))
        vocalPlayer.prepare()
        instrumentalPlayer.prepare()
        vocalPlayer.seekTo(mainPositionMs)
        instrumentalPlayer.seekTo(mainPositionMs)
        vocalPlayer.playbackParameters = PlaybackParameters(speed)
        instrumentalPlayer.playbackParameters = PlaybackParameters(speed)
        vocalPlayer.playWhenReady = playWhenReady
        instrumentalPlayer.playWhenReady = playWhenReady
        isActive = true
    }

    fun setVolumes(vocalVolume: Float, instrumentalVolume: Float) {
        vocalPlayer.volume = vocalVolume.coerceIn(0f, 2f).coerceAtMost(1f)
        instrumentalPlayer.volume = instrumentalVolume.coerceIn(0f, 2f).coerceAtMost(1f)
    }

    /** メインプレイヤーの状態変化(再生/一時停止/シーク/速度)を反映させる */
    fun mirrorPlayWhenReady(playWhenReady: Boolean) {
        vocalPlayer.playWhenReady = playWhenReady
        instrumentalPlayer.playWhenReady = playWhenReady
    }

    fun mirrorSeek(positionMs: Long) {
        vocalPlayer.seekTo(positionMs)
        instrumentalPlayer.seekTo(positionMs)
    }

    fun mirrorSpeed(speed: Float) {
        vocalPlayer.playbackParameters = PlaybackParameters(speed)
        instrumentalPlayer.playbackParameters = PlaybackParameters(speed)
    }

    /** 3つのプレイヤーの再生位置が徐々にずれてくるため、定期的に補正する */
    fun startDriftCorrection(getMainPositionMs: () -> Long) {
        stopDriftCorrection()
        val runnable = object : Runnable {
            override fun run() {
                if (isActive) {
                    val mainPos = getMainPositionMs()
                    if (kotlin.math.abs(vocalPlayer.currentPosition - mainPos) > 200) {
                        vocalPlayer.seekTo(mainPos)
                    }
                    if (kotlin.math.abs(instrumentalPlayer.currentPosition - mainPos) > 200) {
                        instrumentalPlayer.seekTo(mainPos)
                    }
                }
                handler.postDelayed(this, 2000)
            }
        }
        syncRunnable = runnable
        handler.post(runnable)
    }

    fun stopDriftCorrection() {
        syncRunnable?.let { handler.removeCallbacks(it) }
        syncRunnable = null
    }

    fun stop() {
        isActive = false
        stopDriftCorrection()
        vocalPlayer.playWhenReady = false
        instrumentalPlayer.playWhenReady = false
    }

    fun release() {
        stopDriftCorrection()
        vocalPlayer.release()
        instrumentalPlayer.release()
    }
}
