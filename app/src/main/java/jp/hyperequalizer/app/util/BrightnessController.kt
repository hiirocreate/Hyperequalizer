package jp.hyperequalizer.app.util

import android.app.Activity
import android.view.WindowManager

/**
 * 動画左側の縦スライドで使用する画面輝度コントローラ。
 * 0.01〜1.0の範囲でWindow単位の明るさを制御する(端末全体の輝度は変更しない)。
 */
class BrightnessController(private val activity: Activity) {

    fun current(): Float {
        val value = activity.window.attributes.screenBrightness
        return if (value in 0f..1f) value else 0.5f
    }

    fun set(value: Float) {
        val clamped = value.coerceIn(0.01f, 1.0f)
        val layoutParams: WindowManager.LayoutParams = activity.window.attributes
        layoutParams.screenBrightness = clamped
        activity.window.attributes = layoutParams
    }

    fun adjustBy(delta: Float): Float {
        val newValue = (current() + delta).coerceIn(0.01f, 1.0f)
        set(newValue)
        return newValue
    }
}
