package jp.hyperequalizer.app.ui.player

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import jp.hyperequalizer.app.data.AspectMode
import kotlin.math.max

/**
 * 動画の表示モード(縦横比率4パターン)とピンチズーム/パンを両立させるコンテナ。
 *
 * 内部のPlayerViewは常に RESIZE_MODE_FIT(元の比率を保った最大サイズ)を
 * ベースラインとし、本クラスがその上から scaleX/scaleY/translationX/Y を
 * 追加で適用することで FILL / CROP / ORIGINAL / ユーザーのピンチズーム量 を
 * 表現する。
 */
@UnstableApi
class ResizableVideoLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    val playerView: PlayerView = PlayerView(context).apply {
        useController = false
        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    var mode: AspectMode = AspectMode.FIT
        set(value) {
            field = value
            requestApply()
        }

    /** ユーザーのピンチ操作による追加ズーム倍率 (MIN_USER_ZOOM〜MAX_USER_ZOOM)。
     *  1.0未満も許可しており、元の動画サイズより小さく縮小表示できる。 */
    var userZoom: Float = 1.0f
        set(value) {
            field = value.coerceIn(MIN_USER_ZOOM, MAX_USER_ZOOM)
            requestApply()
        }

    var panX: Float = 0f
        set(value) { field = value; requestApply() }
    var panY: Float = 0f
        set(value) { field = value; requestApply() }

    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    init {
        clipChildren = true
        clipToPadding = true
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        requestApply()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestApply()
    }

    private fun requestApply() {
        post { applyTransform() }
    }

    private fun applyTransform() {
        val containerW = width.toFloat()
        val containerH = height.toFloat()
        if (containerW <= 0f || containerH <= 0f || videoWidth <= 0 || videoHeight <= 0) return

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val containerAspect = containerW / containerH

        // RESIZE_MODE_FIT時にPlayerViewが実際に描画するベースラインのサイズ
        val fitW: Float
        val fitH: Float
        if (videoAspect > containerAspect) {
            fitW = containerW
            fitH = containerW / videoAspect
        } else {
            fitH = containerH
            fitW = containerH * videoAspect
        }

        val modeScale = when (mode) {
            AspectMode.FIT -> 1.0f
            AspectMode.FILL -> max(containerW / fitW, 0.0001f) // scaleX/scaleYで非等倍に上書きする
            AspectMode.CROP -> max(containerW / fitW, containerH / fitH)
            AspectMode.ORIGINAL -> videoWidth / fitW
        }

        val scaleX: Float
        val scaleY: Float
        if (mode == AspectMode.FILL) {
            scaleX = (containerW / fitW) * userZoom
            scaleY = (containerH / fitH) * userZoom
        } else {
            scaleX = modeScale * userZoom
            scaleY = modeScale * userZoom
        }

        playerView.scaleX = scaleX
        playerView.scaleY = scaleY

        // はみ出し量に応じてパンを制限する
        val overflowX = max(0f, (fitW * scaleX - containerW) / 2f)
        val overflowY = max(0f, (fitH * scaleY - containerH) / 2f)
        playerView.translationX = panX.coerceIn(-overflowX, overflowX)
        playerView.translationY = panY.coerceIn(-overflowY, overflowY)
    }

    companion object {
        const val MAX_USER_ZOOM = 6.0f
        /** 元の動画サイズよりもさらに縮小できるようにするための下限倍率 */
        const val MIN_USER_ZOOM = 0.3f
    }
}
