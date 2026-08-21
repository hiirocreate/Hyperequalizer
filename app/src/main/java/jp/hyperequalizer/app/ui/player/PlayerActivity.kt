package jp.hyperequalizer.app.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
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
import jp.hyperequalizer.app.playback.PlaybackServiceConnector
import jp.hyperequalizer.app.ui.editor.EditorActivity
import jp.hyperequalizer.app.ui.equalizer.EqualizerSheet
import jp.hyperequalizer.app.ui.equalizer.SeparatedPlaybackController
import jp.hyperequalizer.app.util.BrightnessController
import jp.hyperequalizer.app.util.TimeFormatter
import jp.hyperequalizer.app.util.VolumeController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        buildMediaQueueAndPrepare()
    }

    private fun parseIntentQueue() {
        val single = intent.getStringExtra(EXTRA_URI)
        if (single != null) {
            queueUris = listOf(single)
            queueTypes = listOf(intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: MediaType.VIDEO.name)
        } else {
            queueUris = intent.getStringArrayListExtra(EXTRA_QUEUE_URIS) ?: emptyList()
            queueTypes = intent.getStringArrayListExtra(EXTRA_QUEUE_TYPES) ?: emptyList()
        }
    }

    private fun buildMediaQueueAndPrepare() {
        if (queueUris.isEmpty()) {
            finish()
            return
        }
        var uris = queueUris
        var types = queueTypes
        var startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, uris.size - 1)
        val shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, false)

        // ポップアップ表示や通知タップなどから、既に再生中と同じメディアの画面へ
        // 戻ってきた場合は、キューを再構築せず今の再生状態(位置・再生中かどうか)を
        // そのまま引き継ぐ。そうしないと画面を開き直すたびに最初から再生されてしまう。
        val requestedFirstUri = uris.getOrNull(startIndex)
        val alreadyPlayingSame = player.mediaItemCount > 0 &&
            player.currentMediaItem?.localConfiguration?.uri?.toString() == requestedFirstUri
        if (alreadyPlayingSame) {
            queueUris = uris
            queueTypes = types
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

        val items = uris.map { uri ->
            MediaItem.Builder()
                .setUri(uri.toUri())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(displayNameOf(uri))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
        player.setMediaItems(items, startIndex, 0L)
        player.shuffleModeEnabled = shuffle
        player.prepare()
        player.playWhenReady = true

        applyForIndex(startIndex)
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

    private fun displayNameOf(uriString: String): String {
        return try {
            Uri.parse(uriString).lastPathSegment ?: uriString
        } catch (e: Exception) {
            uriString
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
        binding.btnRepeat.setOnClickListener {
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
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
            binding.btnLock.alpha = if (binding.gestureOverlay.isEnabled) 1f else 0.4f
        }
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

    private fun updateShuffleUi() {
        binding.btnShuffle.alpha = if (player.shuffleModeEnabled) 1f else 0.5f
    }

    private fun updateRepeatUi() {
        binding.btnRepeat.alpha = if (player.repeatMode == Player.REPEAT_MODE_OFF) 0.5f else 1f
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
        binding.btnLoopToggle.text = getString(if (loopEnabled) R.string.loop_disable else R.string.loop_enable)
        binding.btnLoopToggle.alpha = if (loopEnabled) 1f else 0.6f
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

        fun newIntent(context: Context, uri: Uri, mediaType: MediaType): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_MEDIA_TYPE, mediaType.name)

        fun newIntentForQueue(
            context: Context,
            uris: List<String>,
            types: List<String>,
            startIndex: Int,
            shuffle: Boolean
        ): Intent =
            Intent(context, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_QUEUE_URIS, ArrayList(uris))
                .putStringArrayListExtra(EXTRA_QUEUE_TYPES, ArrayList(types))
                .putExtra(EXTRA_START_INDEX, startIndex)
                .putExtra(EXTRA_SHUFFLE, shuffle)
    }
}
