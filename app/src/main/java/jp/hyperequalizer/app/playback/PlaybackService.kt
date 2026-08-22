package jp.hyperequalizer.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.ts.AdtsExtractor
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
import jp.hyperequalizer.app.data.MediaType
import jp.hyperequalizer.app.ui.player.PlayerActivity
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
        val exoPlayer = buildExoPlayer()
        player = exoPlayer
        val session = buildMediaSession(exoPlayer)
        mediaSession = session
        // 本アプリはPlayerActivity/FloatingPlayerServiceから独自のLocalBinderで直接
        // バインドしており(onBind参照)、標準のMediaController経由の接続を一切使わないため、
        // MediaSessionServiceが内部で自動的に行うはずの addSession() 呼び出しが発生しない。
        // これを呼ばないと通知(バックグラウンド再生の操作・フォアグラウンド化)が
        // 一切表示されないため、ここで明示的に登録する。
        addSession(session)
        registerNowPlayingPublisher(exoPlayer)

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

    /**
     * 一覧画面・フォルダ内一覧・メイン画面下部のショートカットバーが「今何が
     * 再生されているか」を軽量に参照できるよう、再生アイテムの切り替わり・
     * タイトル確定(通知タイトルの数字→実ファイル名解決を含む)・再生/一時停止の
     * 切り替わりのたびに[NowPlayingState]を更新する。
     */
    private fun registerNowPlayingPublisher(exoPlayer: ExoPlayer) {
        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                publishNowPlaying(exoPlayer)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                // PlayerActivity側でファイル名解決が完了しタイトルが更新された際にも
                // ここで拾って、一覧側の表示にも実ファイル名が反映されるようにする。
                publishNowPlaying(exoPlayer)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publishNowPlaying(exoPlayer)
            }
        })
        publishNowPlaying(exoPlayer)
    }

    private fun publishNowPlaying(exoPlayer: ExoPlayer) {
        val item = exoPlayer.currentMediaItem
        val uri = item?.localConfiguration?.uri?.toString()
        if (item == null || uri == null) {
            NowPlayingState.clear()
            return
        }
        val mediaTypeName = item.mediaMetadata.extras?.getString(MediaItemKeys.EXTRA_MEDIA_ITEM_TYPE)
        val mediaType = try {
            MediaType.valueOf(mediaTypeName ?: MediaType.VIDEO.name)
        } catch (e: Exception) {
            MediaType.VIDEO
        }
        val name = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: uri
        NowPlayingState.update(NowPlayingInfo(uri, name, mediaType, exoPlayer.isPlaying))
    }

    /**
     * 通知やロック画面をタップした際に「再生中の画面(PlayerActivity)」を直接開くための
     * MediaSessionを作る。キュー情報を持たないIntentを使い、PlayerActivity側で
     * 今このServiceが再生しているキューへそのまま接続させる
     * ([PlayerActivity.newIntentReopenCurrent]参照)。
     */
    private fun buildMediaSession(exoPlayer: ExoPlayer): MediaSession {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            PlayerActivity.newIntentReopenCurrent(this),
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
        mediaSession?.let {
            removeSession(it)
            it.release()
        }
        player?.release()
        mediaSession = null
        player = null
        NowPlayingState.clear()
        super.onDestroy()
    }

    private fun requirePlayer(): ExoPlayer =
        player ?: buildExoPlayer().also {
            player = it
            val session = buildMediaSession(it)
            mediaSession = session
            addSession(session)
            registerNowPlayingPublisher(it)
        }

    /**
     * ExoPlayerインスタンスを構築する。2つの再生品質対策をここで行っている。
     *
     * (1) 拡張子.aac(生のADTS AACストリーム)や.mp3のようにコンテナ自体に総再生時間の
     *     情報を持たないファイルは、標準構成のままだと内部のAdtsExtractor/Mp3Extractorが
     *     総再生時間を算出できず、player.duration が C.TIME_UNSET(=画面には00:00)の
     *     ままとなり、シークバーも機能しない状態になっていた(再生自体は可能なため
     *     気づきにくい)。FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS を指定すると、
     *     ファイル全体を平均ビットレートとみなして総再生時間・シーク位置を推定する
     *     ようになる。
     *
     * (2) 一部の動画ファイルで「音声だけ再生されて映像が出ない(シークバーも
     *     効かない)」という報告があった。考えられる原因の一つは、その動画の
     *     映像コーデック(プロファイル)がこの端末のハードウェアデコーダーと
     *     相性が悪く、優先デコーダーの初期化に失敗しているケース。
     *     setEnableDecoderFallback(true) を指定すると、優先デコーダーの初期化に
     *     失敗した場合に他の(ソフトウェアデコーダーなどの)代替デコーダーへ
     *     自動的にフォールバックするようになるため、こうした端末依存の再生不可を
     *     回避できる可能性がある。
     */
    private fun buildExoPlayer(): ExoPlayer {
        val extractorsFactory = DefaultExtractorsFactory()
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS)
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS)
        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    companion object {
        const val ACTION_LOCAL_BIND = "jp.hyperequalizer.app.action.LOCAL_BIND"
        private const val CMD_REPEAT_CYCLE = "jp.hyperequalizer.app.command.REPEAT_CYCLE"
        private const val CMD_SHUFFLE_TOGGLE = "jp.hyperequalizer.app.command.SHUFFLE_TOGGLE"
    }
}
