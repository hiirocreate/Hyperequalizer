package jp.hyperequalizer.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import jp.hyperequalizer.app.HyperEqApp
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.data.MediaStateRepository
import jp.hyperequalizer.app.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 動画/音楽の再生を「バックグラウンド再生 + 通知からの操作 + ロック画面操作」
 * つきで行うためのサービス。
 *
 * Media3の MediaSessionService を継承しているため、再生中は自動的に
 * (アプリを閉じても消えない)システム通知が表示され、そこから再生/一時停止/
 * 前後スキップに加えて、リピート(単一/リスト)とシャッフルの切り替えが操作できる。
 * 一時停止中に通知をスワイプで消すと、Media3の標準動作としてサービスも自動的に終了する。
 *
 * 通常のMediaSessionService利用パターンでは画面側はMediaControllerを介して
 * 操作するが、本アプリはイコライザー機能で実際のExoPlayerインスタンスの
 * audioSessionIdが必要になるため、[LocalBinder] による直接バインドで
 * ExoPlayer実体そのものを画面側(PlayerActivity)へ渡す方式を取っている。
 * (同一プロセス内で完結するため、この直接バインド方式でも問題ない)
 *
 * 区間(A-B)ループの監視・シークは、以前はPlayerActivity側のHandlerで行っていたが、
 * それだと再生画面を閉じてバックグラウンド再生に切り替わった瞬間にループが
 * 効かなくなってしまっていた。実際の再生を保持しているのはこのServiceなので、
 * ループの監視・シーク処理もこちらへ移し、画面が無い状態でも効き続けるようにしてある。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val binder = LocalBinder()
    private lateinit var mediaStateRepo: MediaStateRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loopObserveJob: Job? = null
    private var cachedLoopStartMs: Long = -1L
    private var cachedLoopEndMs: Long = -1L
    private var cachedLoopEnabled: Boolean = false

    inner class LocalBinder : Binder() {
        fun getPlayer(): ExoPlayer = requirePlayer()
    }

    override fun onCreate() {
        super.onCreate()
        mediaStateRepo = MediaStateRepository((application as HyperEqApp).database.mediaStateDao())
        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        mediaSession = buildMediaSession(exoPlayer)

        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                observeLoopStateForCurrentItem()
            }
        })

        // 区間(A-B)ループの監視ループ。再生画面の有無に関わらず、このServiceが
        // 生きている間はずっと動き続ける
        serviceScope.launch {
            while (isActive) {
                enforceAbLoop()
                delay(200)
            }
        }
    }

    /** 現在再生中のメディアのURIに合わせて、そのファイルのループ設定(常時メモリ機能)を購読し直す */
    private fun observeLoopStateForCurrentItem() {
        loopObserveJob?.cancel()
        val uri = player?.currentMediaItem?.localConfiguration?.uri?.toString()
        if (uri == null) {
            cachedLoopStartMs = -1L
            cachedLoopEndMs = -1L
            cachedLoopEnabled = false
            return
        }
        loopObserveJob = serviceScope.launch {
            mediaStateRepo.observeState(uri).collectLatest { state ->
                cachedLoopStartMs = state?.loopStartMs ?: -1L
                cachedLoopEndMs = state?.loopEndMs ?: -1L
                cachedLoopEnabled = state?.loopEnabled ?: false
            }
        }
    }

    private fun enforceAbLoop() {
        val p = player ?: return
        if (cachedLoopEnabled && cachedLoopStartMs >= 0 && cachedLoopEndMs > cachedLoopStartMs && p.isPlaying) {
            if (p.currentPosition >= cachedLoopEndMs) {
                p.seekTo(cachedLoopStartMs)
            }
        }
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
            .setCallback(MediaSessionCallback())
            .build()
    }

    /** 通知にリピート(単一/リスト)・シャッフルの切り替えボタンを追加するためのコールバック */
    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            val sessionCommands = connectionResult.availableSessionCommands.buildUpon()
                .add(SessionCommand(CMD_REPEAT_CYCLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_SHUFFLE_TOGGLE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(sessionCommands, connectionResult.availablePlayerCommands)
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            session.setCustomLayout(buildCustomLayout())
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val p = player ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
            when (customCommand.customAction) {
                CMD_REPEAT_CYCLE -> {
                    p.repeatMode = when (p.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
                CMD_SHUFFLE_TOGGLE -> {
                    p.shuffleModeEnabled = !p.shuffleModeEnabled
                }
            }
            session.setCustomLayout(buildCustomLayout())
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun buildCustomLayout(): List<CommandButton> {
        val repeatButton = CommandButton.Builder()
            .setDisplayName(getString(R.string.action_repeat))
            .setSessionCommand(SessionCommand(CMD_REPEAT_CYCLE, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_repeat)
            .build()
        val shuffleButton = CommandButton.Builder()
            .setDisplayName(getString(R.string.action_shuffle))
            .setSessionCommand(SessionCommand(CMD_SHUFFLE_TOGGLE, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_shuffle)
            .build()
        return listOf(repeatButton, shuffleButton)
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
        serviceScope.cancel()
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
        private const val CMD_REPEAT_CYCLE = "jp.hyperequalizer.app.command.REPEAT_CYCLE"
        private const val CMD_SHUFFLE_TOGGLE = "jp.hyperequalizer.app.command.SHUFFLE_TOGGLE"
    }
}
