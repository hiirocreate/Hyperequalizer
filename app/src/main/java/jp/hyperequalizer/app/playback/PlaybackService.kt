package jp.hyperequalizer.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import jp.hyperequalizer.app.ui.main.MainActivity

/**
 * 動画/音楽の再生を「バックグラウンド再生 + 通知からの操作 + ロック画面操作」
 * つきで行うためのサービス。
 *
 * Media3の MediaSessionService を継承しているため、再生中は自動的に
 * (アプリを閉じても消えない)システム通知が表示され、そこから再生/一時停止/
 * 前後スキップが操作できる。一時停止中に通知をスワイプで消すと、Media3の
 * 標準動作としてサービスも自動的に終了する。
 *
 * 通常のMediaSessionService利用パターンでは画面側はMediaControllerを介して
 * 操作するが、本アプリはイコライザー機能で実際のExoPlayerインスタンスの
 * audioSessionIdが必要になるため、[LocalBinder] による直接バインドで
 * ExoPlayer実体そのものを画面側(PlayerActivity)へ渡す方式を取っている。
 * (同一プロセス内で完結するため、この直接バインド方式でも問題ない)
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getPlayer(): ExoPlayer = requirePlayer()
    }

    override fun onCreate() {
        super.onCreate()
        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        mediaSession = buildMediaSession(exoPlayer)
    }

    /** 通知やロック画面をタップした際に開く画面(アプリのメイン画面)を設定したMediaSessionを作る */
    private fun buildMediaSession(exoPlayer: ExoPlayer): MediaSession {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(contentIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == ACTION_LOCAL_BIND) {
            binder
        } else {
            super.onBind(intent)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val p = player
        // 再生中でなければ、アプリをタスク一覧から消したタイミングでサービスも終了する
        if (p == null || !p.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.release()
        player?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }

    private fun requirePlayer(): ExoPlayer =
        player ?: ExoPlayer.Builder(this).build().also {
            player = it
            mediaSession = buildMediaSession(it)
        }

    companion object {
        const val ACTION_LOCAL_BIND = "jp.hyperequalizer.app.action.LOCAL_BIND"
    }
}
