package jp.hyperequalizer.app.data

import androidx.room.Entity

/**
 * フォルダ単位でこのアプリ上から非表示にする機能のためのテーブル。
 * 個々のファイル(MediaStateEntity.isHidden)とは別に、フォルダごとまとめて
 * 一覧から除外したい場合に使う。実ファイル・実フォルダには一切手を加えない。
 */
@Entity(tableName = "hidden_folder", primaryKeys = ["folderPath", "mediaType"])
data class HiddenFolderEntity(
    val folderPath: String,
    val mediaType: String,
    val hiddenAt: Long = System.currentTimeMillis()
)
