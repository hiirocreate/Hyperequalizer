package jp.hyperequalizer.app.data

/**
 * 動画の再生時縦横比率(表示モード)。4パターン。
 * FIT      : 元の比率を保ったまま画面内に収める(レターボックス)
 * FILL     : 画面いっぱいに引き伸ばす(比率無視)
 * CROP     : 比率を保ったまま画面を埋めるように拡大し、はみ出た部分を切り取る
 * ORIGINAL : 動画の元サイズ(等倍/1px=1px)で表示する。画面より大きい場合はスクロール可能
 */
enum class AspectMode {
    FIT, FILL, CROP, ORIGINAL;

    fun next(): AspectMode {
        val values = entries
        return values[(this.ordinal + 1) % values.size]
    }
}
