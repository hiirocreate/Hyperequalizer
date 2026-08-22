package jp.hyperequalizer.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.concurrent.ConcurrentHashMap

/**
 * content:// 形式のメディアURI(例: content://media/external/video/media/12345)から
 * 実際のファイル名を解決するためのユーティリティ。
 *
 * このアプリが扱う再生対象は MediaStore の content:// URIで、[Uri.lastPathSegment] は
 * ファイル名ではなく単なる行ID(数字)を返してしまう。これが「通知や再生画面の
 * タイトルが数字になってしまう」不具合の原因だった。
 * ここでは MediaStore(実体は多くのContentProviderと同様に [OpenableColumns.DISPLAY_NAME]
 * に対応している)へ問い合わせて実ファイル名を取得する。
 *
 * MediaStoreへの問い合わせはI/Oを伴うため、[resolve] は必ずバックグラウンドスレッドから
 * 呼び出すこと。一覧再生時などに同じURIへ何度も問い合わせるのを避けるため、
 * 解決結果はプロセス内メモリにキャッシュする。
 */
object MediaDisplayNameResolver {
    private val cache = ConcurrentHashMap<String, String>()

    /** キャッシュ済みであれば即座に返す(UIスレッドから呼んでよい)。未解決ならnull。 */
    fun peek(uriString: String): String? = cache[uriString]

    /**
     * 実ファイル名を解決してキャッシュする。呼び出し元は必ずI/Oスレッド
     * (Dispatchers.IOなど)から呼ぶこと。
     */
    fun resolve(context: Context, uriString: String): String {
        cache[uriString]?.let { return it }
        val resolved = resolveInternal(context, uriString)
        cache[uriString] = resolved
        return resolved
    }

    private fun resolveInternal(context: Context, uriString: String): String {
        val uri = try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            return uriString
        }
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            } catch (e: Exception) {
                // 問い合わせ失敗時は下のフォールバックへ
            }
        }
        return uri.lastPathSegment ?: uriString
    }
}
