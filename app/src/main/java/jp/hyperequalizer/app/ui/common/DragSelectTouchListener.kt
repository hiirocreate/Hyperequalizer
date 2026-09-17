package jp.hyperequalizer.app.ui.common

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/**
 * 長押しで選択を開始したあと、指を離さずそのままスライドさせることで
 * なぞった範囲をまとめて選択できるようにする [RecyclerView.OnItemTouchListener]。
 *
 * 使い方:
 * 1. [MediaFileAdapter] の長押しコールバック(onDragSelectStart)からこのリスナーの
 *    [start] を呼び、ドラッグ選択セッションを開始する。
 * 2. 以後は指を離す([MotionEvent.ACTION_UP]/[MotionEvent.ACTION_CANCEL])まで、
 *    このリスナーが以降のタッチイベントを横取りし、なぞった位置に応じて
 *    [MediaFileAdapter.selectRange] を呼び続ける。
 * 3. 指がリスト上端/下端付近に来ると、自動的にゆっくりスクロールしながら
 *    選択範囲を広げていく(速すぎて誤操作にならないよう、あえて低速に抑えてある)。
 */
class DragSelectTouchListener(
    private val recyclerView: RecyclerView,
    private val adapter: MediaFileAdapter
) : RecyclerView.OnItemTouchListener {

    private var isDragging = false
    private var anchorPosition = RecyclerView.NO_POSITION
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val handler = Handler(Looper.getMainLooper())
    private var autoScrollSpeed = 0
    private var isAutoScrollScheduled = false

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            isAutoScrollScheduled = false
            if (!isDragging || autoScrollSpeed == 0) return
            recyclerView.scrollBy(0, autoScrollSpeed)
            updateSelectionForCurrentTouch(lastTouchX, lastTouchY)
            isAutoScrollScheduled = true
            handler.postDelayed(this, SCROLL_INTERVAL_MS)
        }
    }

    /** 長押しでドラッグ範囲選択を開始する。[position] は長押しした項目の位置(選択の起点)。 */
    fun start(position: Int) {
        isDragging = true
        anchorPosition = position
    }

    fun isActive(): Boolean = isDragging

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (!isDragging) return false
        if (e.action == MotionEvent.ACTION_DOWN) {
            // 新しいジェスチャーの先頭イベント。前回のドラッグが指を離さないまま
            // 終わっていないはずだが、念のためここでは横取りしない
            // (start()が改めて呼ばれた時点で有効化される)。
            return false
        }
        return true
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!isDragging) return
        when (e.action) {
            MotionEvent.ACTION_MOVE -> {
                lastTouchX = e.x
                lastTouchY = e.y
                updateSelectionForCurrentTouch(e.x, e.y)
                updateAutoScroll(e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endDrag()
            }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // ドラッグ中に親(ViewPagerなど)へタッチを奪われると選択が途中で止まってしまうため、
        // ここでは何もしない(要求を無視する)。
    }

    private fun endDrag() {
        isDragging = false
        anchorPosition = RecyclerView.NO_POSITION
        autoScrollSpeed = 0
        adapter.endDragSelectSession()
    }

    private fun updateSelectionForCurrentTouch(x: Float, y: Float) {
        if (anchorPosition == RecyclerView.NO_POSITION) return
        val maxY = (recyclerView.height - 1).coerceAtLeast(0).toFloat()
        val clampedY = y.coerceIn(0f, maxY)
        val child = recyclerView.findChildViewUnder(x, clampedY)
        val position = if (child != null) {
            recyclerView.getChildAdapterPosition(child)
        } else if (adapter.itemCount > 0) {
            // 指がリストの上端/下端より外に出た場合は、先頭/末尾の項目とみなす
            if (clampedY <= 0f) 0 else adapter.itemCount - 1
        } else {
            RecyclerView.NO_POSITION
        }
        if (position != RecyclerView.NO_POSITION) {
            adapter.selectRange(anchorPosition, position)
        }
    }

    private fun updateAutoScroll(y: Float) {
        val height = recyclerView.height
        if (height <= 0) {
            autoScrollSpeed = 0
            return
        }
        val edgeSize = height * EDGE_FRACTION
        autoScrollSpeed = when {
            y < edgeSize -> {
                val proximity = 1f - (y / edgeSize).coerceIn(0f, 1f)
                -speedFor(proximity)
            }
            y > height - edgeSize -> {
                val proximity = 1f - ((height - y) / edgeSize).coerceIn(0f, 1f)
                speedFor(proximity)
            }
            else -> 0
        }
        if (autoScrollSpeed != 0 && !isAutoScrollScheduled) {
            isAutoScrollScheduled = true
            handler.postDelayed(autoScrollRunnable, SCROLL_INTERVAL_MS)
        }
    }

    private fun speedFor(proximity: Float): Int {
        val range = MAX_SCROLL_SPEED_PX - MIN_SCROLL_SPEED_PX
        return (MIN_SCROLL_SPEED_PX + range * proximity).toInt().coerceIn(MIN_SCROLL_SPEED_PX, MAX_SCROLL_SPEED_PX)
    }

    companion object {
        // リストの上下何%を「端付近」とみなして自動スクロールを始めるか
        private const val EDGE_FRACTION = 0.15f
        // 自動スクロールの更新間隔(ミリ秒)。短すぎるとスクロールが速くなりすぎるため注意。
        private const val SCROLL_INTERVAL_MS = 50L
        // 自動スクロール速度(px/回)の最小/最大値。誤操作を防ぐため、あえて低速に抑えている。
        private const val MIN_SCROLL_SPEED_PX = 4
        private const val MAX_SCROLL_SPEED_PX = 16
    }
}
