package jp.hyperequalizer.app.library

import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import jp.hyperequalizer.app.data.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 端末内(MediaStore)から動画・音楽ファイルを列挙する。
 * 対応フォーマットは端末のMediaStoreに認識されている拡張子(mp4, mkv, webm, 3gp,
 * mp3, m4a, flac, wav, ogg, aac 等)に準じる。一部の古いコンテナ(aviなど)は
 * MediaStoreに登録されていても再生時にMedia3側での対応状況に依存する。
 */
class MediaLibraryScanner(private val context: Context) {

    suspend fun scanVideos(): List<MediaFile> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MediaFile>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = buildProjection(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_ADDED
        )
        context.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                list.add(
                    MediaFile(
                        uri = uri,
                        displayName = cursor.getString(nameCol) ?: uri.toString(),
                        durationMs = cursor.getLong(durCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        mediaType = MediaType.VIDEO,
                        mimeType = cursor.getString(mimeCol),
                        dateAdded = cursor.getLong(dateCol),
                        folderPath = folderPathOf(cursor)
                    )
                )
            }
        }
        list
    }

    suspend fun scanAudios(): List<MediaFile> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MediaFile>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildProjection(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        context.contentResolver.query(
            collection, projection, selection, null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                list.add(
                    MediaFile(
                        uri = uri,
                        displayName = cursor.getString(nameCol) ?: uri.toString(),
                        durationMs = cursor.getLong(durCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        mediaType = MediaType.AUDIO,
                        mimeType = cursor.getString(mimeCol),
                        dateAdded = cursor.getLong(dateCol),
                        folderPath = folderPathOf(cursor)
                    )
                )
            }
        }
        list
    }

    /** APIレベルに応じて、フォルダ判定に必要な列(RELATIVE_PATHまたはDATA)を追加したprojectionを作る */
    private fun buildProjection(vararg base: String): Array<String> {
        val extra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.MediaColumns.DATA
        }
        return base + extra
    }

    /**
     * カーソルの現在行から「フォルダ別」表示・フォルダ非表示機能で使う
     * フォルダパスを取り出す。API29以降はRELATIVE_PATH(例: "Movies/Camera/")、
     * それ以前は絶対パス(DATA)の親ディレクトリ名から組み立てる。
     */
    private fun folderPathOf(cursor: Cursor): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val col = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val raw = if (col >= 0) cursor.getString(col) else null
            raw?.trim('/')?.takeIf { it.isNotEmpty() } ?: UNKNOWN_FOLDER
        } else {
            @Suppress("DEPRECATION")
            val col = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            val path = if (col >= 0) cursor.getString(col) else null
            path?.let { File(it).parentFile?.name }?.takeIf { it.isNotEmpty() } ?: UNKNOWN_FOLDER
        }
    }

    companion object {
        const val UNKNOWN_FOLDER = "(不明なフォルダ)"
    }
}
