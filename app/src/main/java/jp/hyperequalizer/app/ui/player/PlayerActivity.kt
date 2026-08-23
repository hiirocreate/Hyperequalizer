package jp.hyperequalizer.app.ui.player

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import jp.hyperequalizer.app.HyperEqApp
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.data.AspectMode
import jp.hyperequalizer.app.data.MediaStateRepository
import jp.hyperequalizer.app.data.MediaType
import jp.hyperequalizer.app.databinding.ActivityPlayerBinding
import jp.hyperequalizer.app.playback.FloatingPlayerService
import jp.hyperequalizer.app.playback.MediaItemKeys
import jp.hyperequalizer.app.playback.PendingPlaybackQueue
import jp.hyperequalizer.app.playback.PlaybackServiceConnector
import jp.hyperequalizer.app.ui.editor.EditorActivity
import jp.hyperequalizer.app.ui.equalizer.EqualizerSheet
import jp.hyperequalizer.app.ui.equalizer.SeparatedPlaybackController
import jp.hyperequalizer.app.util.BrightnessController
import jp.hyperequalizer.app.util.MediaDisplayNameResolver
import jp.hyperequalizer.app.util.TimeFormatter
import jp.hyperequalizer.app.util.VolumeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 動画・音楽 共通の再生画面。
 * ジェスチャー操作(シーク/明るさ/音量/ズーム/ダブルタップスキップ)、
 * 速度調整、区間(A-B)ループ、リピート、シャッフル、お気に入り、
 * アスペクト比4パターン、そして「常時メモリ機能」による状態復元をすべてここで扱う。
 */
@UnstableApi
class PlayerActivity : AppCompatActivity(), GestureOverlayView.Listener {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var player: ExoPlayer
    private var playerReady = false
    private lateinit var serviceConnector: PlaybackServiceConnector
    private lateinit var repo: MediaStateRepository
    private lateinit var brightness: BrightnessController
    private lateinit var volume: VolumeController

    private var queueUris: List<String> = emptyList()
    private var queueTypes: List<String> = emptyList()
    private var startIndexFromLaunch: Int = 0
    private var shuffleFromLaunch: Boolean = false
    private var currentUri: String = ""
    private var currentMediaType: MediaType = MediaType.VIDEO

    private var scrubTargetMs: Long = -1L
    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private var loopStartMs: Long = -1L
    private var loopEndMs: Long = -1L
    private var loopEnabled: Boolean = false
    private var isFavorite: Boolean = false
    private val speedSteps = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
    private var speedIndex = 2
    private var separatedController: SeparatedPlaybackController? = null

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startPopupMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as HyperEqApp
        repo = MediaStateRepository(app.database.mediaStateDao())
        brightness = BrightnessController(this)
        volume = VolumeController(this)

        parseIntentQueue()

        binding.gestureOverlay.listener = this

        handler.post(progressRunnable)
        scheduleAutoHide()

        // 実際の再生(ExoPlayer)は PlaybackService が保持する。バックグラウンド再生・
        // 通知からの操作・ポップアップ表示のすべてで同一のインスタンスを共有するため。
        // 区間(A-B)ループの監視・シークもPlaybackService側で行っており、この画面が
        // 閉じてバックグラウンド再生に切り替わってもループが効き続けるようになっている。
        serviceConnector = PlaybackServiceConnector(applicationContext)
        serviceConnector.connect { exoPlayer -> onPlayerReady(exoPlayer) }
    }

    /** [PlaybackService] への接続が完了し、共有ExoPlayerインスタンスが使えるようになったタイミングで呼ばれる */
    private fun onPlayerReady(exoPlayer: ExoPlayer) {
        if (playerReady) return
        player = exoPlayer
        playerReady = true

        binding.videoLayout.playerView.player = player
        player.addListener(playerListener)

        setupControls()
        updateShuffleUi()
        updateRepeatUi()
        buildMediaQueueAndPrepare()
    }

    private fun parseIntentQueue() {
        if (intent.getBooleanExtra(EXTRA_HAS_PENDING_QUEUE, false)) {
            // Binder(IPC)の1トランザクションあたりの上限(~1MB)を超えると
            // TransactionTooLargeExceptionで落ちることがあるため、大きな配列になり得る
            // キュー情報はIntentのextrasではなくプロセス内シングルトン(PendingPlaybackQueue)
            // 経由で受け渡す。
            val pending = PendingPlaybackQueue.take()
            if (pending != null) {
                queueUris = pending.uris
                queueTypes = pending.types
                startIndexFromLaunch = pending.startIndex
                shuffleFromLaunch = pending.shuffle
                return
            }
            // take()で既に消費済み(画面回転などでonCreateが再度呼ばれた場合)は、
            // 単発URIや従来の複数URI extrasへのフォールバックを試みる。
        }
        val single = intent.getStringExtra(EXTRA_URI)
        if (single != null) {
            queueUris = listOf(single)
            queueTypes = listOf(intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: MediaType.VIDEO.name)
            startIndexFromLaunch = 0
            shuffleFromLaunch = false
        } else {
            queueUris = intent.getStringArrayListExtra(EXTRA_QUEUE_URIS) ?: emptyList()
            queueTypes = intent.getStringArrayListExtra(EXTRA_QUEUE_TYPES) ?: emptyList()
            startIndexFromLaunch = intent.getIntExtra(EXTRA_START_INDEX, 0)
            shuffleFromLaunch = intent.getBooleanExtra(EXTRA_SHUFFLE, false)
        }
    }

    private fun buildMediaQueueAndPrepare() {
        if (queueUris.isEmpty()) {
            // キュー情報が渡されていない = 通知をタップして「今再生中の画面にそのまま
            // 戻る」呼び出し(PlaybackServiceの通知タップ用Intentを参照)。
            // すでにPlaybackService側のExoPlayerがキューを持っていればそれを
            // そのまま引き継いで表示し、何も再生されていなければ開く意味がないので閉じる。
            if (player.mediaItemCount > 0) {
                rebuildQueueFromPlayer()
                applyForIndex(player.currentMediaItemIndex)
            } else {
                finish()
            }
            return
        }
        var uris = queueUris
        var types = queueTypes
        var startIndex = startIndexFromLaunch.coerceIn(0, uris.size - 1)
        val shuffle = shuffleFromLaunch

        // ポップアップ表示や通知タップなどから、既に再生中と同じメディアの画面へ
        // 戻ってきた場合は、キューを再構築せず今の再生状態(位置・再生中かどうか)を
        // そのまま引き継ぐ。そうしないと画面を開き直すたびに最初から再生されてしまう。
        val requestedFirstUri = uris.getOrNull(startIndex)
        val alreadyPlayingSame = player.mediaItemCount > 0 &&
            player.currentMediaItem?.localConfiguration?.uri?.toString() == requestedFirstUri
        if (alreadyPlayingSame) {
            // ここで渡されたuris/typesは単発Intent(EXTRA_URIのみ)の場合1件しかないことがあり、
            // それをそのまま採用すると実際のキュー(複数曲/複数動画)が1件に縮んでしまう。
            // 実際に再生中のキューは常にPlaybackService側のExoPlayerが正なので、そちらから
            // 組み直す。
            rebuildQueueFromPlayer()
            applyForIndex(player.currentMediaItemIndex)
            return
        }

        if (shuffle && uris.size > 1) {
            val zipped = uris.zip(types).toMutableList()
            val head = zipped.removeAt(startIndex)
            zipped.shuffle()
            zipped.add(0, head)
            uris = zipped.map { it.first }
            types = zipped.map { it.second }
            startIndex = 0
        }
        queueUris = uris
        queueTypes = types

        val items = uris.mapIndexed { index, uri -> buildMediaItem(uri, types.getOrElse(index) { MediaType.VIDEO.name }) }
        player.setMediaItems(items, startIndex, 0L)
        player.shuffleModeEnabled = shuffle
        player.prepare()
        player.playWhenReady = true

        applyForIndex(startIndex)
    }

    private fun buildMediaItem(uri: String, mediaType: String): MediaItem {
        val extras = Bundle().apply { putString(EXTRA_MEDIA_ITEM_TYPE, mediaType) }
        return MediaItem.Builder()
            .setUri(uri.toUri())
            // mediaIdを明示的に指定する(未指定だと全アイテムが空文字列の同一IDになり、
            // MediaSessionの内部処理で複数曲/複数動画のキューを正しく区別できなくなるため)
            .setMediaId(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(displayNameFallback(uri))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    /**
     * 通知タップなどでこの画面を開き直した際、Intentにキュー情報が無い(または渡された
     * URIが1件だけ)場合に、実際に再生中のキューを PlaybackService 側の ExoPlayer から
     * 直接読み直す。各アイテムの種別(動画/音楽)は [buildMediaItem] でメタデータの
     * extrasに埋め込んであるものをここで取り出す。
     */
    private fun rebuildQueueFromPlayer() {
        val uris = mutableListOf<String>()
        val types = mutableListOf<String>()
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            val uri = item.localConfiguration?.uri?.toString() ?: continue
            val type = item.mediaMetadata.extras?.getString(EXTRA_MEDIA_ITEM_TYPE) ?: MediaType.VIDEO.name
            uris.add(uri)
            types.add(type)
        }
        queueUris = uris
        queueTypes = types
    }

    private fun applyForIndex(index: Int) {
        if (index !in queueUris.indices) return
        currentUri = queueUris[index]
        currentMediaType = MediaType.valueOf(queueTypes.getOrElse(index) { MediaType.VIDEO.name })
        binding.videoLayout.visibility = if (currentMediaType == MediaType.VIDEO) View.VISIBLE else View.GONE
        binding.audioModeIcon.visibility = if (currentMediaType == MediaType.VIDEO) View.GONE else View.VISIBLE
        binding.titleText.text = displayNameOf(currentUri)
        binding.btnEdit.visibility = if (currentMediaType == MediaType.VIDEO) View.VISIBLE else View.GONE
        binding.btnPopup.visibility = if (currentMediaType == MediaType.VIDEO) View.VISIBLE else View.GONE
        restoreStateForCurrent()
    }

    /**
     * MediaStoreのcontent:// URIは [Uri.lastPathSegment] だと数値の行IDしか取れず
     * ファイル名として表示できないため、[MediaDisplayNameResolver] で実際の表示名を解決する。
     * 同期的に取得できるキャッシュがあればそれを即返し、無ければひとまずURIの末尾
     * (フォールバック)を返しつつ、非同期で正しい名前を取得してタイトルを更新する
     * ([updateMediaItemTitle]経由で[Player.replaceMediaItem]を呼び出す=副作用あり)。
     *
     * この副作用のある解決は「今まさに表示/再生中の1件」に対してのみ行うこと
     * ([applyForIndex]・お気に入りボタンなど)。キュー構築時に一覧の全件分を
     * まとめて呼び出すと、非同期解決が完了するたびに [Player.replaceMediaItem] が
     * ほぼ同時多発的にメインスレッドへ飛び、ExoPlayerの内部状態を乱して
     * 「映像だけ表示されなくなる」「画面全体が操作不能になる(ANR)」といった
     * 不具合につながることが分かったため、キュー構築(複数件を一度に処理する場面)
     * では代わりに副作用の無い [displayNameFallback] を使う。
     */
    private fun displayNameOf(uriString: String): String {
        val cached = MediaDisplayNameResolver.peek(uriString)
        if (cached != null) return cached
        resolveDisplayNameAsync(uriString)
        return try {
            Uri.parse(uriString).lastPathSegment ?: uriString
        } catch (e: Exception) {
            uriString
        }
    }

    /**
     * [displayNameOf] と異なり、非同期解決やExoPlayerへの [Player.replaceMediaItem] 呼び出しを
     * 一切トリガーしない、副作用の無いバージョン。複数アイテムをまとめて構築する
     * [buildMediaItem] (=[buildMediaQueueAndPrepare] から一覧の全件に対して呼ばれる)専用。
     * キャッシュ済みの表示名があればそれを使い、無ければURIの末尾を暫定表示名として使う
     * (実際に再生され[applyForIndex]が呼ばれた時点で改めて正しい名前が解決される)。
     */
    private fun displayNameFallback(uriString: String): String {
        val cached = MediaDisplayNameResolver.peek(uriString)
        if (cached != null) return cached
        return try {
            Uri.parse(uriString).lastPathSegment ?: uriString
        } catch (e: Exception) {
            uriString
        }
    }

    private fun resolveDisplayNameAsync(uriString: String) {
        lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                MediaDisplayNameResolver.resolve(applicationContext, uriString)
            }
            updateMediaItemTitle(uriString, resolved)
        }
    }

    /**
     * 非同期解決が終わった時点でまだ同じアイテムを表示中であれば画面上のタイトルを更新する。
     *
     * これに加えて、実際に再生中のExoPlayerが持つ [MediaItem] のメタデータ自体も
     * 更新している。システム通知(バックグラウンド再生中の通知)のタイトルは
     * 画面のTextViewではなく、この MediaItem.mediaMetadata.title から生成されるため、
     * 画面のテキストだけ直しても通知側はいつまでもMediaStoreの数値ID
     * (Uri.lastPathSegmentによるフォールバック値)のまま表示され続けてしまう。
     * [Player.replaceMediaItem] は再生位置・再生状態を保ったままメタデータだけを
     * 差し替えられるため、再生を中断せずに通知のタイトルも直せる。
     *
     * COMMAND_CHANGE_MEDIA_ITEMSが利用できない(=呼び出せない)状態や、
     * 端末・タイミングによっては例外を投げる場合があるため、失敗しても再生自体を
     * 巻き込んでクラッシュさせないよう防御的に呼び出す。
     */
    private fun updateMediaItemTitle(uriString: String, resolvedName: String) {
        if (uriString == currentUri) {
            binding.titleText.text = resolvedName
        }
        if (!playerReady) return
        if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            if (item.localConfiguration?.uri?.toString() != uriString) continue
            if (item.mediaMetadata.title?.toString() == resolvedName) break
            try {
                val updatedMetadata = item.mediaMetadata.buildUpon().setTitle(resolvedName).build()
                val updatedItem = item.buildUpon().setMediaMetadata(updatedMetadata).build()
                player.replaceMediaItem(i, updatedItem)
            } catch (e: Exception) {
                // 通知タイトルの更新に失敗しても、再生そのものは継続させる(致命的ではないため)
            }
            break
        }
    }

    private fun restoreStateForCurrent() {
        lifecycleScope.launch {
            val state = repo.getState(currentUri)
            loopStartMs = state.loopStartMs
            loopEndMs = state.loopEndMs
            loopEnabled = state.loopEnabled
            isFavorite = state.isFavorite
            updateLoopMarkerUi()
            updateFavoriteUi()

            val mode = try {
                AspectMode.valueOf(state.aspectMode)
            } catch (e: Exception) {
                AspectMode.FIT
            }
            binding.videoLayout.mode = mode
            binding.videoLayout.userZoom = state.zoomScale
            binding.videoLayout.panX = state.panX
            binding.videoLayout.panY = state.panY
            updateAspectChipText()

            speedIndex = speedSteps.indexOfFirst { kotlin.math.abs(it - state.playbackSpeed) < 0.01f }
                .let { if (it < 0) 2 else it }
            player.playbackParameters = PlaybackParameters(speedSteps[speedIndex])
            updateSpeedChipText()

            if (state.lastPositionMs > 0L) {
                player.seekTo(state.lastPositionMs)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            binding.videoLayout.setVideoSize(videoSize.width, videoSize.height)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveCurrentStateNow()
            deactivateSeparatedPlayback() // 曲/動画が切り替わったら分離再生は一旦解除する
            // イコライザー画面(EqualizerSheet)は開いた時点のアイテムのURI/audioSessionId・
            // 分離結果を保持したまま動作しているため、ループ/次へなどで裏側の再生対象が
            // 切り替わってしまうと、もう再生されていない曲に対して帯域調整や分離を続行
            // してしまったり、無効になったAudioEffectセッションへ操作を投げて例外に
            // つながる可能性がある。開いたままにしておく理由が無いため、アイテムが
            // 切り替わった時点で自動的に閉じ、次に開いたときに新しいアイテムの状態で
            // 作り直させる。
            dismissEqualizerSheetIfShowing()
            // 前のアイテムの映像サイズ/変形情報を引きずったまま次のアイテムを表示してしまう
            // (=映像が更新されないまま古い変形だけ残る)ことを避けるため、いったんリセットする。
            // onVideoSizeChangedが呼ばれるまでの間はvideoWidth/Height<=0となり、
            // ResizableVideoLayout側の描画変形処理は早期リターンして何もしない状態になる。
            binding.videoLayout.setVideoSize(0, 0)
            applyForIndex(player.currentMediaItemIndex)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            separatedController?.takeIf { it.isActive }?.mirrorPlayWhenReady(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                binding.totalTimeText.text = TimeFormatter.format(player.duration.coerceAtLeast(0))
                updateLoopMarkerUi()
            }
        }

        // 通知側のシャッフル/リピート切り替えボタン(PlaybackServiceのカスタムコマンド)で
        // 状態が変わった場合も、同じExoPlayerインスタンスを共有しているためこれらが呼ばれる。
        // これによりこの画面のチップ表示も常に実際の状態と一致する。
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            updateShuffleUi()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateRepeatUi()
        }

        /**
         * 「映像が流れずシークバーも操作できずファイル名も表示されないが、音声のみ再生される」
         * という報告について、再現手順・ログが無く根本原因を断定できていない。
         * PlaybackService側でデコーダのフォールバック(setEnableDecoderFallback)を有効化した
         * ことで一部の「特定コーデックでハードウェアデコーダが初期化に失敗する」ケースは
         * 緩和される可能性があるが、確証はない。再発時に原因を特定できるよう、実際の
         * エラー内容をここで表示するようにした。
         */
        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(
                this@PlayerActivity,
                getString(R.string.playback_error_format, error.message ?: error.errorCodeName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { if (player.isPlaying) player.pause() else player.play() }
        binding.btnReplay10.setOnClickListener { seekBy(-10_000) }
        binding.btnForward10.setOnClickListener { seekBy(10_000) }
        binding.btnPrev.setOnClickListener { if (player.hasPreviousMediaItem()) player.seekToPrevious() }
        binding.btnNext.setOnClickListener { if (player.hasNextMediaItem()) player.seekToNext() }
        binding.btnShuffle.setOnClickListener {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            updateShuffleUi()
        }
        binding.btnRepeatList.setOnClickListener {
            // リストループ(REPEAT_MODE_ALL)のON/OFF切り替え。1ループとは排他。
            player.repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ALL) {
                Player.REPEAT_MODE_OFF
            } else {
                Player.REPEAT_MODE_ALL
            }
            updateRepeatUi()
        }
        binding.btnRepeatOne.setOnClickListener {
            // 1ループ(REPEAT_MODE_ONE、当該ファイルのみ繰り返し)のON/OFF切り替え。
            player.repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) {
                Player.REPEAT_MODE_OFF
            } else {
                Player.REPEAT_MODE_ONE
            }
            updateRepeatUi()
        }
        binding.btnFavorite.setOnClickListener {
            isFavorite = !isFavorite
            updateFavoriteUi()
            lifecycleScope.launch {
                repo.setFavorite(
                    currentUri, isFavorite,
                    displayName = displayNameOf(currentUri),
                    mediaType = currentMediaType,
                    durationMs = player.duration.coerceAtLeast(0)
                )
            }
        }
        binding.btnAspect.setOnClickListener {
            binding.videoLayout.mode = binding.videoLayout.mode.next()
            updateAspectChipText()
            persistAspectSoon()
        }
        binding.btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speedSteps.size
            player.playbackParameters = PlaybackParameters(speedSteps[speedIndex])
            separatedController?.takeIf { it.isActive }?.mirrorSpeed(speedSteps[speedIndex])
            updateSpeedChipText()
            lifecycleScope.launch { repo.updateSpeed(currentUri, speedSteps[speedIndex]) }
        }
        binding.btnLoopSetA.setOnClickListener {
            loopStartMs = player.currentPosition
            if (loopEndMs in 0..loopStartMs) loopEndMs = -1
            updateLoopMarkerUi()
            persistLoopSoon()
        }
        binding.btnLoopSetB.setOnClickListener {
            if (player.currentPosition > loopStartMs) {
                loopEndMs = player.currentPosition
                loopEnabled = true
                updateLoopMarkerUi()
                persistLoopSoon()
            }
        }
        binding.btnLoopToggle.setOnClickListener {
            loopEnabled = !loopEnabled
            updateLoopMarkerUi()
            persistLoopSoon()
        }
        binding.btnEqualizer.setOnClickListener {
            EqualizerSheet.newInstance(currentUri, player.audioSessionId)
                .show(supportFragmentManager, EQUALIZER_SHEET_TAG)
        }
        binding.btnEdit.setOnClickListener {
            if (currentMediaType == MediaType.VIDEO) {
                startActivity(EditorActivity.newIntent(this, Uri.parse(currentUri)))
            }
        }
        binding.btnLock.setOnClickListener {
            binding.gestureOverlay.isEnabled = !binding.gestureOverlay.isEnabled
            updateLockUi()
        }
        updateLockUi()
        binding.btnPopup.setOnClickListener { onPopupClicked() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && player.duration > 0) {
                    val target = player.duration * progress / 1000
                    binding.currentTimeText.text = TimeFormatter.format(target)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                cancelAutoHide()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (player.duration > 0) {
                    val target = player.duration * seekBar.progress / 1000
                    seekMain(target)
                }
                scheduleAutoHide()
            }
        })
    }

    /** メインプレイヤーをシークし、分離再生が有効な場合はそちらも追従させる */
    private fun seekMain(positionMs: Long) {
        player.seekTo(positionMs)
        separatedController?.takeIf { it.isActive }?.mirrorSeek(positionMs)
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (!playerReady) {
                handler.postDelayed(this, 1000)
                return
            }
            if (player.duration > 0) {
                val progress = (player.currentPosition * 1000 / player.duration).toInt()
                if (!binding.seekBar.isPressed) {
                    binding.seekBar.progress = progress.coerceIn(0, 1000)
                    binding.currentTimeText.text = TimeFormatter.format(player.currentPosition)
                }
            }
            if (currentUri.isNotEmpty()) {
                lifecycleScope.launch {
                    repo.updatePosition(currentUri, player.currentPosition, player.duration.coerceAtLeast(0))
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceIn(0, player.duration.coerceAtLeast(0))
        seekMain(target)
        showSkipFlash(deltaMs > 0)
    }

    // ---- GestureOverlayView.Listener ----
    override fun onSingleTap() {
        setControlsVisible(!controlsVisible)
    }

    override fun onDoubleTapSkip(forward: Boolean) {
        seekBy(if (forward) 10_000 else -10_000)
    }

    override fun onScrubStart() {
        cancelAutoHide()
        scrubTargetMs = player.currentPosition
    }

    override fun onScrubBy(deltaMs: Long) {
        val max = player.duration.coerceAtLeast(0)
        scrubTargetMs = (scrubTargetMs + deltaMs).coerceIn(0, max)
        binding.seekPreviewText.visibility = View.VISIBLE
        binding.seekPreviewText.text = "${TimeFormatter.format(scrubTargetMs)} / ${TimeFormatter.format(max)}"
    }

    override fun onScrubEnd() {
        if (scrubTargetMs >= 0) seekMain(scrubTargetMs)
        binding.seekPreviewText.visibility = View.GONE
        scrubTargetMs = -1
        scheduleAutoHide()
    }

    override fun onBrightnessDelta(delta: Float) {
        val value = brightness.adjustBy(delta)
        showFeedback(R.drawable.ic_brightness, (value * 100).toInt())
    }

    override fun onVolumeDelta(delta: Float) {
        val value = volume.adjustBy(delta)
        showFeedback(R.drawable.ic_volume, (value * 100).toInt())
    }

    override fun onScale(factor: Float) {
        binding.videoLayout.userZoom = binding.videoLayout.userZoom * factor
        persistAspectSoon()
    }

    // ---- UI helpers ----
    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        binding.topBar.visibility = if (visible) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) scheduleAutoHide() else cancelAutoHide()
    }

    private fun scheduleAutoHide() {
        cancelAutoHide()
        handler.postDelayed(hideControlsRunnable, 4000)
    }

    private fun cancelAutoHide() {
        handler.removeCallbacks(hideControlsRunnable)
    }

    private var feedbackHideRunnable: Runnable? = null
    private fun showFeedback(iconRes: Int, percent: Int) {
        binding.feedbackIcon.setImageResource(iconRes)
        binding.feedbackText.text = "${percent.coerceIn(0, 100)}%"
        binding.feedbackPill.visibility = View.VISIBLE
        feedbackHideRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { binding.feedbackPill.visibility = View.GONE }
        feedbackHideRunnable = runnable
        handler.postDelayed(runnable, 800)
    }

    private var skipFlashHideRunnable: Runnable? = null
    private fun showSkipFlash(forward: Boolean) {
        binding.skipFlashText.text = if (forward) "»» 10秒" else "«« 10秒"
        binding.skipFlashText.visibility = View.VISIBLE
        skipFlashHideRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { binding.skipFlashText.visibility = View.GONE }
        skipFlashHideRunnable = runnable
        handler.postDelayed(runnable, 600)
    }

    private fun updateAspectChipText() {
        binding.btnAspect.text = when (binding.videoLayout.mode) {
            AspectMode.FIT -> getString(R.string.aspect_fit)
            AspectMode.FILL -> getString(R.string.aspect_fill)
            AspectMode.CROP -> getString(R.string.aspect_crop)
            AspectMode.ORIGINAL -> getString(R.string.aspect_original)
        }
    }

    private fun updateSpeedChipText() {
        binding.btnSpeed.text = getString(R.string.speed_label, speedSteps[speedIndex])
    }

    private fun updateFavoriteUi() {
        binding.btnFavorite.setImageResource(if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
    }

    /** 文字チップ(A/B/AB)の背景色/文字色を切り替えて、有効なモードが一目で分かるようにする */
    private fun setChipActive(chip: TextView, active: Boolean) {
        chip.setBackgroundResource(if (active) R.drawable.bg_chip_active else R.drawable.bg_chip)
        chip.setTextColor(getColor(if (active) R.color.hyper_bg else R.color.hyper_on_surface))
    }

    /** アイコンボタン(シャッフル/リストループ/1ループ/ロック)の色を切り替えて状態を示す */
    private fun setIconActive(icon: ImageView, active: Boolean) {
        val color = getColor(if (active) R.color.hyper_accent else R.color.hyper_muted)
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color))
        icon.alpha = if (active) 1f else 0.7f
    }

    private fun updateShuffleUi() {
        setIconActive(binding.btnShuffle, player.shuffleModeEnabled)
    }

    private fun updateRepeatUi() {
        setIconActive(binding.btnRepeatList, player.repeatMode == Player.REPEAT_MODE_ALL)
        setIconActive(binding.btnRepeatOneIcon, player.repeatMode == Player.REPEAT_MODE_ONE)
    }

    /** ロックOFF(通常操作可能)は半透明、ON(ロック中)は点灯(アクセントカラー+不透明)にする */
    private fun updateLockUi() {
        val locked = !binding.gestureOverlay.isEnabled
        setIconActive(binding.btnLock, locked)
    }

    private fun updateLoopMarkerUi() {
        val duration = player.duration.coerceAtLeast(1)
        val width = binding.seekBar.width
        if (loopStartMs >= 0 && width > 0) {
            binding.loopMarkerA.visibility = View.VISIBLE
            binding.loopMarkerA.translationX = (loopStartMs.toFloat() / duration) * width
        } else {
            binding.loopMarkerA.visibility = View.GONE
        }
        if (loopEndMs >= 0 && width > 0) {
            binding.loopMarkerB.visibility = View.VISIBLE
            binding.loopMarkerB.translationX = (loopEndMs.toFloat() / duration) * width
        } else {
            binding.loopMarkerB.visibility = View.GONE
        }
        setChipActive(binding.btnLoopSetA, loopStartMs >= 0)
        setChipActive(binding.btnLoopSetB, loopEndMs >= 0)
        setChipActive(binding.btnLoopToggle, loopEnabled)
    }

    private var loopSaveJob: Job? = null
    private fun persistLoopSoon() {
        loopSaveJob?.cancel()
        loopSaveJob = lifecycleScope.launch {
            delay(200)
            repo.updateLoop(currentUri, loopStartMs, loopEndMs, loopEnabled)
        }
    }

    private var aspectSaveJob: Job? = null
    private fun persistAspectSoon() {
        aspectSaveJob?.cancel()
        aspectSaveJob = lifecycleScope.launch {
            delay(200)
            repo.updateAspect(
                currentUri, binding.videoLayout.mode, binding.videoLayout.userZoom,
                binding.videoLayout.panX, binding.videoLayout.panY
            )
        }
    }

    private fun saveCurrentStateNow() {
        if (!playerReady || currentUri.isEmpty()) return
        lifecycleScope.launch {
            repo.updatePosition(currentUri, player.currentPosition, player.duration.coerceAtLeast(0))
        }
    }

    override fun onResume() {
        super.onResume()
        // ポップアップ表示から戻ってきた場合など、映像の表示先が別のPlayerViewに
        // 切り替わっていることがあるため、この画面のPlayerViewへ表示先を取り戻す
        if (playerReady) {
            binding.videoLayout.playerView.player = player
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentStateNow()
    }

    override fun onStop() {
        super.onStop()
        saveCurrentStateNow()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        separatedController?.release()
        // ExoPlayer自体は PlaybackService が所有しているため、ここでは解放せず
        // サービスとの接続を切るだけにする(バックグラウンド再生・ポップアップ表示を継続させるため)
        if (playerReady) {
            player.removeListener(playerListener)
        }
        serviceConnector.disconnect()
    }

    // ---- ポップアップ(フローティングウィンドウ)再生 ----

    private fun onPopupClicked() {
        if (!playerReady || currentMediaType != MediaType.VIDEO) return
        if (Settings.canDrawOverlays(this)) {
            startPopupMode()
        } else {
            showOverlayPermissionRationale()
        }
    }

    private fun showOverlayPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle(R.string.popup_permission_title)
            .setMessage(R.string.popup_permission_message)
            .setPositiveButton(R.string.permission_grant) { _, _ ->
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri()
                )
                overlayPermissionLauncher.launch(settingsIntent)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun startPopupMode() {
        FloatingPlayerService.start(this)
        finish()
    }

    // ---- イコライザー画面(EqualizerSheet)から呼び出される分離再生API ----

    /** ボーカル/伴奏に分離済みの音声で再生を開始する(メインプレイヤーはミュートする) */
    fun activateSeparatedPlayback(vocalPath: String, instrumentalPath: String, vocalVolume: Float, instrumentalVolume: Float) {
        val controller = separatedController ?: SeparatedPlaybackController(this).also { separatedController = it }
        controller.start(
            vocalPath = vocalPath,
            instrumentalPath = instrumentalPath,
            mainPositionMs = player.currentPosition,
            playWhenReady = player.isPlaying,
            speed = speedSteps[speedIndex]
        )
        controller.setVolumes(vocalVolume, instrumentalVolume)
        controller.startDriftCorrection { player.currentPosition }
        player.volume = 0f
    }

    fun updateSeparatedVolumes(vocalVolume: Float, instrumentalVolume: Float) {
        separatedController?.setVolumes(vocalVolume, instrumentalVolume)
        lifecycleScope.launch { repo.updateVolumeMix(currentUri, vocalVolume, instrumentalVolume) }
    }

    fun deactivateSeparatedPlayback() {
        separatedController?.stop()
        player.volume = 1f
    }

    /** 再生対象が切り替わった際、開きっぱなしのイコライザー画面があれば閉じる(詳細は[onMediaItemTransition]参照) */
    private fun dismissEqualizerSheetIfShowing() {
        val fragment = supportFragmentManager.findFragmentByTag(EQUALIZER_SHEET_TAG) as? EqualizerSheet
        fragment?.dismissAllowingStateLoss()
    }

    fun currentAudioSessionId(): Int = player.audioSessionId
    fun currentUriString(): String = currentUri

    companion object {
        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_MEDIA_TYPE = "extra_media_type"
        private const val EXTRA_QUEUE_URIS = "extra_queue_uris"
        private const val EXTRA_QUEUE_TYPES = "extra_queue_types"
        private const val EXTRA_START_INDEX = "extra_start_index"
        private const val EXTRA_SHUFFLE = "extra_shuffle"

        /** EqualizerSheetをshow()する際のFragmentTag(切り替え時に見つけて閉じるためのキー) */
        private const val EQUALIZER_SHEET_TAG = "equalizer"

        /**
         * queueUris/queueTypesがBinder(IPC)の1トランザクション上限(~1MB)を超える
         * サイズになり得るため、Intent extrasには載せず [PendingPlaybackQueue] へ
         * 一時保存したことを示すだけのフラグ。
         */
        private const val EXTRA_HAS_PENDING_QUEUE = "extra_has_pending_queue"

        /** MediaItemのメタデータ(extras)に種別(動画/音楽)を埋め込むためのキー */
        private const val EXTRA_MEDIA_ITEM_TYPE = MediaItemKeys.EXTRA_MEDIA_ITEM_TYPE

        fun newIntent(context: Context, uri: Uri, mediaType: MediaType): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_MEDIA_TYPE, mediaType.name)

        /**
         * 複数曲/複数動画のキュー付きで再生画面を開くためのIntentを作る。
         * 大きなリストをIntent extrasへ直接載せるとBinderのトランザクションサイズ上限
         * (~1MB)を超えて TransactionTooLargeException で落ちることがあるため、
         * 実データは [PendingPlaybackQueue] (プロセス内シングルトン)へ渡し、
         * Intentには「保留中のキューがある」ことを示すフラグだけを載せる。
         */
        fun newIntentForQueue(
            context: Context,
            uris: List<String>,
            types: List<String>,
            startIndex: Int,
            shuffle: Boolean
        ): Intent {
            PendingPlaybackQueue.set(uris, types, startIndex, shuffle)
            return Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_HAS_PENDING_QUEUE, true)
        }

        /**
         * 通知(PlaybackServiceの再生中通知)をタップした時に使う、キュー情報を
         * 一切含まないIntent。あえて何も渡さないことで、この画面側は
         * 「今PlaybackServiceが再生しているキューへそのまま戻る」と解釈する
         * ([buildMediaQueueAndPrepare]参照)。サービス(非Activity)コンテキストから
         * 起動するため FLAG_ACTIVITY_NEW_TASK が必須。
         */
        fun newIntentReopenCurrent(context: Context): Intent =
            Intent(context, PlayerActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
