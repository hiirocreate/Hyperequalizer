package jp.hyperequalizer.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object MediaPermissions {

    /** 動画・音楽ライブラリの読み込みに必須の権限。これが揃っていない間は一覧を表示しない */
    fun required(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * バックグラウンド再生時の通知(操作できるプレーヤー表示)のための権限。
     * TIRAMISU(API33)未満では不要。ライブラリ表示自体には必須ではないため
     * [required] とは別枠で扱い、拒否されても一覧表示はブロックしない。
     */
    fun notificationPermissionIfNeeded(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }
    }

    fun hasAll(context: Context): Boolean {
        return required().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
