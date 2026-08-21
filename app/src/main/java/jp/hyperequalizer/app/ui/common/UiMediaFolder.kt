package jp.hyperequalizer.app.ui.common

import jp.hyperequalizer.app.data.MediaType

/** 「フォルダ別」一覧の1タイル分のデータ */
data class UiMediaFolder(
    val folderPath: String,
    val mediaType: MediaType,
    val itemCount: Int
)
