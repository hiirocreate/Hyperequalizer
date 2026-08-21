package jp.hyperequalizer.app.util

import android.app.Activity
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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

    /**
     * まとめて選択した複数ファイルを一括削除する。
     * API30以降は [MediaStore.createDeleteRequest] を使い、
     * システムの確認ダイアログを1回だけ表示してまとめて削除できるようにする。
     * それより前のバージョンでは1件ずつ [delete] を試みる(削除ごとに確認が必要になる場合がある)。
     */
    fun deleteAll(
        activity: Activity,
        uris: List<Uri>,
        deleteRequestLauncher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(activity.contentResolver, uris)
                deleteRequestLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                return
            } catch (e: Exception) {
                // フォールバックとして1件ずつ削除を試みる
            }
        }
        uris.forEach { uri -> delete(activity, uri, deleteRequestLauncher) }
    }
}
