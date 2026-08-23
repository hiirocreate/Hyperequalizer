package jp.hyperequalizer.app.ui.common

import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.data.MediaType
import jp.hyperequalizer.app.playback.NowPlayingState
import jp.hyperequalizer.app.playback.PlaybackServiceConnector
import jp.hyperequalizer.app.ui.player.PlayerActivity
import kotlinx.coroutines.launch

/**
 * 再生画面(PlayerActivity)以外の画面に、通知欄の簡易操作バーのような
 * 「今何を再生しているか一目で分かり、タップで再生画面へ戻れる」ミニバーを
 * 設置するための共通コントローラー。MainActivity・FolderContentsActivityから使う。
 *
 * 何も再生されていない間はバーを非表示にし、[PlaybackService] への接続もあえて行わない。
 * これは、この画面を開いただけで(何も再生していないのに)バックグラウンド再生用の
 * サービス・ExoPlayerインスタンスが起動してしまう(=メモリを無駄に使う)ことを
 * 避けるため。[NowPlayingState] が非nullになった時点 = 既にどこかで再生が
 * 始まっていてサービスも起動済みのはずのタイミングで、初めて再生/一時停止操作用の
 * 接続を行う。
 */
@UnstableApi
class NowPlayingBarController(
    private val activity: AppCompatActivity,
    private val barRoot: View,
    private val icon: ImageView,
    private val title: TextView,
    private val playPauseButton: ImageButton
) {
    private var connector: PlaybackServiceConnector? = null
    private var player: ExoPlayer? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon(isPlaying)
        }
    }

    fun start() {
        barRoot.setOnClickListener {
            activity.startActivity(PlayerActivity.newIntentReopenCurrent(activity))
        }
        playPauseButton.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else p.play()
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NowPlayingState.current.collect { info ->
                    if (info == null) {
                        barRoot.visibility = View.GONE
                    } else {
                        barRoot.visibility = View.VISIBLE
                        title.text = info.displayName
                        icon.setImageResource(
                            if (info.mediaType == MediaType.VIDEO) R.drawable.ic_video else R.drawable.ic_music
                        )
                        updatePlayPauseIcon(info.isPlaying)
                        ensureConnected()
                    }
                }
            }
        }
    }

    /**
     * ミニバーの再生/一時停止ボタンを実際に操作できるようにするための接続。
     * [NowPlayingState] が非nullになった時点で呼ぶため、PlaybackServiceは
     * その情報を発行した本人であり、既に起動済みのはずである
     * (=このタイミングで接続してもサービスを新たに起動させることはない)。
     */
    private fun ensureConnected() {
        if (connector != null) return
        connector = PlaybackServiceConnector(activity.applicationContext).also {
            it.connect { exoPlayer ->
                player = exoPlayer
                exoPlayer.addListener(playerListener)
                updatePlayPauseIcon(exoPlayer.isPlaying)
            }
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        playPauseButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    fun stop() {
        player?.removeListener(playerListener)
        connector?.disconnect()
        connector = null
        player = null
    }
}
