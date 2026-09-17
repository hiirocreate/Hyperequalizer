package jp.hyperequalizer.app.ui.common

import android.content.Context
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.databinding.ViewNowPlayingBarBinding
import jp.hyperequalizer.app.playback.PlaybackService
import jp.hyperequalizer.app.playback.PlaybackServiceConnector
import jp.hyperequalizer.app.ui.player.PlayerActivity
import kotlin.math.abs

/**
 * 画面下部に常駐する「再生中」ミニバー(再生画面へのショートカット)を制御するクラス。
 *
 * MainActivity/FolderContentsActivityなど、ミニバーを表示したい各画面から
 * それぞれの画面のライフサイクル([android.app.Activity.onStart]/[android.app.Activity.onStop]目安)に
 * 合わせて[start]/[stop]を呼び出して使う。既存のPlaybackServiceへ
 * [PlaybackServiceConnector] で直接バインドし、共有ExoPlayerインスタンスを
 * 取得する自己完結型の実装になっている(他のNowPlayingState的な仕組みには依存しない)。
 *
 * バーをタップするとその画面の再生画面(PlayerActivity)を開く。
 * 前へ/再生・一時停止/次への簡易操作ボタンに加えて、バーを左右にスワイプする
 * (または×ボタンを押す)と、単にバーが隠れるだけでなく再生そのものを完全に終了し、
 * 画面下部からバーが完全に消える。
 */
@UnstableApi
class NowPlayingBarController(
    private val context: Context,
    private val binding: ViewNowPlayingBarBinding
) {
    private val connector = PlaybackServiceConnector(context.applicationContext)
    private var player: ExoPlayer? = null
    private var dismissed = false

    private var downX = 0f
    private var dragging = false

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refresh()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            refresh()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            refresh()
        }
    }

    fun start() {
        dismissed = false
        connector.connect { exoPlayer ->
            player = exoPlayer
            exoPlayer.addListener(playerListener)
            setupControls(exoPlayer)
            setupSwipeToDismiss()
            refresh()
        }
    }

    fun stop() {
        try {
            player?.removeListener(playerListener)
        } catch (e: Exception) {
            // ignore: バインド解除タイミングによっては既に無効な場合がある
        }
        player = null
        connector.disconnect()
    }

    private fun setupControls(exoPlayer: ExoPlayer) {
        binding.root.setOnClickListener {
            context.startActivity(
                PlayerActivity.newIntentReopenCurrent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        binding.btnNowPlayingPlayPause.setOnClickListener {
            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
        }
        binding.btnNowPlayingPrev.setOnClickListener {
            if (exoPlayer.hasPreviousMediaItem()) exoPlayer.seekToPrevious()
        }
        binding.btnNowPlayingNext.setOnClickListener {
            if (exoPlayer.hasNextMediaItem()) exoPlayer.seekToNext()
        }
        binding.btnNowPlayingClose.setOnClickListener {
            dismissAndStop()
        }
    }

    /**
     * バー全体を左右にスワイプすることで「完全に再生終了」できるようにする。
     * ドラッグと判定するまではイベントを消費しない([false]を返す)ことで、
     * 単純なタップ(バーをタップして再生画面を開く操作)を妨げないようにしている。
     */
    private fun setupSwipeToDismiss() {
        binding.root.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    if (!dragging && abs(dx) > TOUCH_SLOP_PX) {
                        dragging = true
                    }
                    if (dragging) {
                        view.translationX = dx
                        val widthForAlpha = view.width.takeIf { it > 0 } ?: 1
                        view.alpha = (1f - abs(dx) / widthForAlpha).coerceIn(0.2f, 1f)
                    }
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val dx = event.rawX - downX
                        dragging = false
                        if (view.width > 0 && abs(dx) > view.width * DISMISS_FRACTION) {
                            animateOffAndDismiss(view, dx)
                        } else {
                            view.animate().translationX(0f).alpha(1f).setDuration(150).start()
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun animateOffAndDismiss(view: View, dx: Float) {
        val target = if (dx > 0) view.width.toFloat() else -view.width.toFloat()
        view.animate()
            .translationX(target)
            .alpha(0f)
            .setDuration(180)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { dismissAndStop() }
            .start()
    }

    /**
     * バーを閉じる = 再生そのものを完全に終了する。
     * UIだけ隠して裏で再生が続くような中途半端な状態にはしない。
     */
    private fun dismissAndStop() {
        if (dismissed) return
        dismissed = true
        try {
            player?.pause()
            player?.clearMediaItems()
        } catch (e: Exception) {
            // ignore: 停止処理中の例外はUI更新を妨げないようにする
        }
        try {
            context.applicationContext.stopService(Intent(context.applicationContext, PlaybackService::class.java))
        } catch (e: Exception) {
            // ignore
        }
        binding.root.translationX = 0f
        binding.root.alpha = 1f
        binding.root.visibility = View.GONE
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        binding.btnNowPlayingPlayPause.setImageResource(
            if (isPlaying) jp.hyperequalizer.app.R.drawable.ic_pause else jp.hyperequalizer.app.R.drawable.ic_play
        )
    }

    private fun refresh() {
        val p = player
        if (dismissed || p == null || p.mediaItemCount == 0) {
            binding.root.visibility = View.GONE
            return
        }
        binding.root.visibility = View.VISIBLE
        binding.root.translationX = 0f
        binding.root.alpha = 1f
        updatePlayPauseIcon(p.isPlaying)

        val mediaItem = p.currentMediaItem
        val title = mediaItem?.mediaMetadata?.title?.toString()
        binding.nowPlayingTitle.text = if (!title.isNullOrBlank()) title else context.getString(R.string.app_name)

        val hasVideoTrack = try {
            p.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        } catch (e: Exception) {
            false
        }
        binding.nowPlayingIcon.setImageResource(
            if (hasVideoTrack) jp.hyperequalizer.app.R.drawable.ic_video else jp.hyperequalizer.app.R.drawable.ic_music
        )
    }

    companion object {
        private const val TOUCH_SLOP_PX = 16
        private const val DISMISS_FRACTION = 0.35f
    }
}
