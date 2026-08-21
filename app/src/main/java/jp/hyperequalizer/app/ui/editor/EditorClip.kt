package jp.hyperequalizer.app.ui.editor

import android.net.Uri

data class EditorClip(
    val uri: Uri,
    val displayName: String,
    var trimStartMs: Long,
    var trimEndMs: Long, // -1 = 末尾まで
    var muted: Boolean = false
)
