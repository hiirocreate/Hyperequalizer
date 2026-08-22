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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
    private var startIndexFromLaunch: Int = 0
    private var shuffleFromLaunch: Boolean = false

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
        val single = intent.getStringExtra(EXTRA_URI)
        if (single != null) {
            queueUris = listOf(single)
            queueTypes = listOf(intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: MediaType.VIDEO.name)
            startIndexFromLaunch = 0
            shuffleFromLaunch = false
            return
        }
        if (intent.getBooleanExtra(EXTRA_HAS_PENDING_QUEUE, false)) {
            // 一覧画面などから大量件数のキューを渡された場合。Intent(Binder IPC)経由で
            // URIリストをまるごと運ぶとサイズ上限を超えてクラッシュすることがあったため、
            // 実体は PendingPlaybackQueue(同一プロセス内)から受け取る。
            val pending = PendingPlaybackQueue.take()
            if (pending != null) {
                queueUris = pending.uris
                queueTypes = pending.types
                startIndexFromLaunch = pending.startIndex
                shuffleFromLaunch = pending.shuffle
                return
            }
        }
        // 後方互換: 直接Intent extrasにキューが載っている旧来の呼び出し方(件数が少ない場合)
        queueUris = intent.getStringArrayListExtra(EXTRA_QUEUE_URIS) ?: emptyList()
        queueTypes = intent.getStringArrayListExtra(EXTRA_QUEUE_TYPES) ?: emptyList()
        startIndexFromLaunch = intent.getIntExtra(EXTRA_START_INDEX, 0)
        shuffleFromLaunch = intent.getBooleanExtra(EXTRA_SHUFFLE, false)
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
                    .setTitle(displayNameOf(uri))
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
     * 表示用のファイル名を返す。content:// URIの [Uri.lastPathSegment] は
     * 単なる数字のIDでしかないため、それだけでは「通知や再生画面のタイトルが
     * 数字になる」問題が再発してしまう。ここではまずキャッシュ済みの実ファイル名が
     * あればそれを返し、無ければ非同期でMediaStoreに問い合わせて解決する
     * ([resolveDisplayNameAsync])。解決が終わるまでの間は暫定的に数字IDなどを表示する。
     */
    private fun displayNameOf(uriString: String): String {
        MediaDisplayNameResolver.peek(uriString)?.let { return it }
        resolveDisplayNameAsync(uriString)
        return try {
            Uri.parse(uriString).lastPathSegment ?: uriString
        } catch (e: Exception) {
            uriString
        }
    }

    /**
     * MediaStoreへ問い合わせて実ファイル名を非同期で解決する。判明したら
     * (1) 今まさに表示中の曲/動画であれば画面上のタイトル表示を更新し、
     * (2) 通知に表示されるメタデータ(MediaItemのタイトル)も実ファイル名に更新する。
     * これにより「通知の表示名が数字のまま」という問題を解消する。
     */
    private fun resolveDisplayNameAsync(uriString: String) {
        lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                MediaDisplayNameResolver.resolve(applicationContext, uriString)
            }
            if (currentUri == uriString) {
                binding.titleText.text = resolved
            }
            if (playerReady) {
                updateMediaItemTitle(uriString, resolved)
            }
        }
    }

    /** 指定URIに対応するMediaItemのタイトルメタデータを実ファイル名に差し替える(通知表示の更新用) */
    private fun updateMediaItemTitle(uriString: String, resolvedName: String) {
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            if (item.localConfiguration?.uri?.toString() == uriString &&
                item.mediaMetadata.title?.toString() != resolvedName
            ) {
                val updated = item.buildUpon()
                    .setMediaMetadata(item.mediaMetadata.buildUpon().setTitle(resolvedName).build())
                    .build()
                player.replaceMediaItem(i, updated)
            }
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
                .show(supportFragmentManager, "equalizer")
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

    fun currentAudioSessionId(): Int = player.audioSessionId
    fun currentUriString(): String = currentUri

    companion object {
        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_MEDIA_TYPE = "extra_media_type"
        private const val EXTRA_QUEUE_URIS = "extra_queue_uris"
        private const val EXTRA_QUEUE_TYPES = "extra_queue_types"
        private const val EXTRA_START_INDEX = "extra_start_index"
        private const val EXTRA_SHUFFLE = "extra_shuffle"

        /** キュー本体をIntentに載せず[PendingPlaybackQueue]経由で渡したことを示す目印 */
        private const val EXTRA_HAS_PENDING_QUEUE = "extra_has_pending_queue"

        /** MediaItemのメタデータ(extras)に種別(動画/音楽)を埋め込むためのキー */
        private const val EXTRA_MEDIA_ITEM_TYPE = "media_item_type"

        fun newIntent(context: Context, uri: Uri, mediaType: MediaType): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_MEDIA_TYPE, mediaType.name)

        /**
         * 再生キュー(URIリスト)を渡して再生画面を起動するIntentを作る。
         *
         * 一覧画面全体(数百〜数千件)がそのまま渡ってくることがあるため、キュー本体は
         * Intent extrasには載せず [PendingPlaybackQueue] という同一プロセス内の
         * 受け渡し場所に直接セットする。Intent extrasに文字列配列としてそのまま載せると
         * Binder IPCのトランザクションサイズ上限(端末あたり約1MB)を超えて
         * TransactionTooLargeExceptionでクラッシュすることがあったため。
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
