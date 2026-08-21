package jp.hyperequalizer.app.ui.common

import android.net.Uri
import jp.hyperequalizer.app.data.MediaType

data class UiMediaItem(
    val uri: Uri,
    val displayName: String,
    val subtitle: String,
    val durationMs: Long,
    val mediaType: MediaType,
    val isFavorite: Boolean,
    val playlistItemId: Long? = null // プレイリスト内で表示する場合のみ設定(削除操作用)
)
