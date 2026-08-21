package jp.hyperequalizer.app.util

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「フォルダ別」一覧や通常のファイル一覧でサムネイル(動画のフレーム/音声の
 * 埋め込みアートワーク)を表示するための軽量ローダー。
 * 外部の画像読み込みライブラリを追加せず、Android標準APIのみで実装している。
 * デコード結果はメモリ内LRUキャッシュに保持し、同じ項目の再デコードを避ける。
 */
object ThumbnailLoader {

    private const val CACHE_SIZE_BYTES = 24 * 1024 * 1024 // 24MB

    private val cache = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun loadVideoThumbnail(context: Context, uri: Uri, sizePx: Int = 256): Bitmap? {
        val key = "v:$uri"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bmp = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
                } else {
                    @Suppress("DEPRECATION")
                    val id = ContentUris.parseId(uri)
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null
                    )
                }
            } catch (e: Exception) {
                null
            }
            if (bmp != null) cache.put(key, bmp)
            bmp
        }
    }

    /** 音声ファイルに埋め込まれたアートワーク(ジャケット画像)があれば取得する。無ければnull */
    suspend fun loadAudioArt(context: Context, uri: Uri): Bitmap? {
        val key = "a:$uri"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val bmp = try {
                retriever.setDataSource(context, uri)
                val bytes = retriever.embeddedPicture
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } catch (e: Exception) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    // 解放失敗は無視して良い
                }
            }
            if (bmp != null) cache.put(key, bmp)
            bmp
        }
    }
}
