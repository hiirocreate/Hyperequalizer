package jp.hyperequalizer.app.ui.player

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

/**
 * 動画/音楽プレイヤー画面のジェスチャー操作を一手に検出する透明オーバーレイ。
 *
 * - シングルタップ: コントロールUIの表示/非表示切替
 * - ダブルタップ(右半分): 10秒早送り / (左半分): 10秒早戻し
 * - 横方向ドラッグ: シークバーのスクラブ操作
 * - 縦方向ドラッグ(左半分): 画面の明るさ調整
 * - 縦方向ドラッグ(右半分): メディア音量調整
 * - ピンチ操作: 動画のズーム
 */
class GestureOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onSingleTap()
        fun onDoubleTapSkip(forward: Boolean)
        fun onScrubStart()
        fun onScrubBy(deltaMs: Long)
        fun onScrubEnd()
        fun onBrightnessDelta(delta: Float)
        fun onVolumeDelta(delta: Float)
        fun onScale(factor: Float)
    }

    var listener: Listener? = null
    var seekRangeMsForFullWidth: Long = 120_000L

    private enum class DragMode { NONE, HORIZONTAL_SEEK, VERTICAL_BRIGHTNESS, VERTICAL_VOLUME }
    private var dragMode = DragMode.NONE
    private var isScaling = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            listener?.onScale(detector.scaleFactor)
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            listener?.onSingleTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            listener?.onDoubleTapSkip(forward = e.x >= width / 2f)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (isScaling || e1 == null) return false
            if (dragMode == DragMode.NONE) {
                dragMode = if (abs(distanceX) > abs(distanceY)) {
                    listener?.onScrubStart()
                    DragMode.HORIZONTAL_SEEK
                } else if (e1.x < width / 2f) {
                    DragMode.VERTICAL_BRIGHTNESS
                } else {
                    DragMode.VERTICAL_VOLUME
                }
            }
            when (dragMode) {
                DragMode.HORIZONTAL_SEEK -> {
                    val deltaMs = (-distanceX / width.coerceAtLeast(1)) * seekRangeMsForFullWidth
                    listener?.onScrubBy(deltaMs.toLong())
                }
                DragMode.VERTICAL_BRIGHTNESS -> {
                    // distanceYは「前回位置 - 今回位置」(GestureDetectorの仕様)なので、
                    // 上方向へのスワイプで正の値になる。符号をそのまま使うことで
                    // 「上にスワイプ = 増やす、下にスワイプ = 減らす」という直感的な
                    // 方向に統一している(以前は符号を反転させていたため逆になっていた)。
                    val delta = (distanceY / height.coerceAtLeast(1))
                    listener?.onBrightnessDelta(delta)
                }
                DragMode.VERTICAL_VOLUME -> {
                    val delta = (distanceY / height.coerceAtLeast(1))
                    listener?.onVolumeDelta(delta)
                }
                DragMode.NONE -> {}
            }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragMode == DragMode.HORIZONTAL_SEEK) {
                listener?.onScrubEnd()
            }
            dragMode = DragMode.NONE
        }
        return true
    }
}
