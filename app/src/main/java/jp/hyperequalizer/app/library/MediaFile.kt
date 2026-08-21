package jp.hyperequalizer.app.library

import android.net.Uri
import jp.hyperequalizer.app.data.MediaType

data class MediaFile(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mediaType: MediaType,
    val mimeType: String?,
    val dateAdded: Long,
    /** 「フォルダ別」表示・フォルダ単位の非表示機能で使う、保存先フォルダの相対パス */
    val folderPath: String
)
