package jp.hyperequalizer.app.util

import android.app.Activity
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest

/**
 * MediaStore経由でユーザーの動画・音楽ファイルを削除する。
 * API29以降は他アプリ所有のファイルを消す際にRecoverableSecurityExceptionが
 * 発生することがあるため、その場合はシステムの削除確認ダイアログを表示する。
 */
object MediaDeleter {

    fun delete(activity: Activity, uri: Uri, deleteRequestLauncher: ActivityResultLauncher<IntentSenderRequest>): Boolean {
        return try {
            activity.contentResolver.delete(uri, null, null)
            true
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recoverable = e as? android.app.RecoverableSecurityException
                val intentSender: IntentSender? = recoverable?.userAction?.actionIntent?.intentSender
                if (intentSender != null) {
                    deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
            }
            false
        }
    }
}
