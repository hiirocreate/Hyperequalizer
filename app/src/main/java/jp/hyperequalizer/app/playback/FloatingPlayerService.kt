package jp.hyperequalizer.app.playback

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import jp.hyperequalizer.app.data.MediaType
import jp.hyperequalizer.app.databinding.FloatingPlayerWindowBinding
import jp.hyperequalizer.app.ui.player.PlayerActivity
import kotlin.math.abs

/**
 * 動画を「ポップアップ(フローティングウィンドウ)」として、他アプリの上に
 * 重ねて再生表示するためのサービス。
 *
 * 再生自体は行わず、[PlaybackService] が保持している共有ExoPlayerインスタンスに
 * このウィンドウのPlayerViewを紐付けるだけ。Media3のPlayerViewは、同じPlayerを
 * 別のPlayerViewへ付け替えると映像の出力先だけを自動的に切り替えてくれるため、
 * 再生位置や再生状態を引き継ぐための特別な処理は不要。
 *
 * ドラッグでウィンドウ位置を、ピンチでウィンドウサイズをそれぞれ変更できる。
 * 表示には SYSTEM_ALERT_WINDOW 権限(「他のアプリの上に重ねて表示」)が必要で、
 * 権限確認はこのサービスを起動する側(PlayerActivity)が事前に行う。
 */
@UnstableApi
class FloatingPlayerService : Service() {

    private lateinit var windowManager: WindowManager
    private var binding: FloatingPlayerWindowBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var connector: PlaybackServiceConnector? = null
    private var player: ExoPlayer? = null

    private var videoAspect: Float = 16f / 9f

    private lateinit var scaleDetector: ScaleGestureDetector
    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0
    private var isDragging = false

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            applyVideoSize(videoSize)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupGestures()
        addOverlayWindow()

        val c = PlaybackServiceConnector(applicationContext)
        connector = c
        c.connect { exoPlayer ->
            player = exoPlayer
            binding?.popupPlayerView?.player = exoPlayer
            exoPlayer.addListener(playerListener)
            applyVideoSize(exoPlayer.videoSize)
        }
    }

    private fun applyVideoSize(videoSize: VideoSize) {
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        videoAspect = videoSize.width.toFloat() / videoSize.height.toFloat()
        val params = layoutParams ?: return
        params.height = (params.width / videoAspect).toInt().coerceAtLeast(MIN_SIZE_PX)
        binding?.root?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun addOverlayWindow() {
        val b = FloatingPlayerWindowBinding.inflate(LayoutInflater.from(this))
        binding = b

        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.55f).toInt().coerceAtLeast(MIN_SIZE_PX)
        val height = (width / videoAspect).toInt().coerceAtLeast(MIN_SIZE_PX)

        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (displayMetrics.widthPixels - width) / 2
            y = (displayMetrics.heightPixels - height) / 3
        }
        layoutParams = params
        windowManager.addView(b.root, params)

        b.btnPopupClose.setOnClickListener { stopSelf() }
        b.btnPopupExpand.setOnClickListener { expandToActivity() }
        b.root.setOnTouchListener { _, event -> onRootTouch(event) }
    }

    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val params = layoutParams ?: return false
                val root = binding?.root ?: return false
                val maxWidth = resources.displayMetrics.widthPixels
                val newWidth = (params.width * detector.scaleFactor).toInt().coerceIn(MIN_SIZE_PX, maxWidth)
                params.width = newWidth
                params.height = (newWidth / videoAspect).toInt().coerceAtLeast(MIN_SIZE_PX)
                windowManager.updateViewLayout(root, params)
                return true
            }
        })
    }

    private fun onRootTouch(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val params = layoutParams ?: return false
        val root = binding?.root ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downParamX = params.x
                downParamY = params.y
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) return true
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging && (abs(dx) > TOUCH_SLOP_PX || abs(dy) > TOUCH_SLOP_PX)) {
                    isDragging = true
                }
                if (isDragging) {
                    params.x = downParamX + dx.toInt()
                    params.y = downParamY + dy.toInt()
                    windowManager.updateViewLayout(root, params)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // ドラッグでなければ単純なタップとみなし、再生/一時停止を切り替える
                    player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
                }
                isDragging = false
            }
        }
        return true
    }

    private fun expandToActivity() {
        val uri = player?.currentMediaItem?.localConfiguration?.uri
        if (uri != null) {
            val intent = PlayerActivity.newIntentForQueue(
                this,
                listOf(uri.toString()),
                listOf(MediaType.VIDEO.name),
                0,
                false
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        player?.removeListener(playerListener)
        connector?.disconnect()
        binding?.root?.let {
            try {
                windowManager.removeView(it)
            } catch (e: IllegalArgumentException) {
                // 既にウィンドウが取り外し済みであれば無視する
            }
        }
        binding = null
        layoutParams = null
        super.onDestroy()
    }

    companion object {
        private const val MIN_SIZE_PX = 240
        private const val TOUCH_SLOP_PX = 12

        fun start(context: Context) {
            // PlaybackServiceが既にフォアグラウンドでプロセスを保護しているため、
            // このサービス自体はフォアグラウンド化せず通常起動で十分
            context.startService(Intent(context, FloatingPlayerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingPlayerService::class.java))
        }
    }
}
