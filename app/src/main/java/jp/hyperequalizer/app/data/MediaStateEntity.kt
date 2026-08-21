package jp.hyperequalizer.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 「常時メモリ機能」の中核となるテーブル。
 * 1件のメディア(動画/音楽ファイルのcontent URIごと)につき1レコードを保持し、
 * 再生位置・ループ設定・縮尺(アスペクト比/ズーム)・お気に入り・
 * ボーカル/伴奏の個別音量・イコライザー設定などを常に上書き保存する。
 * アプリを閉じて再起動しても、このテーブルの値を読み込むだけで
 * 直前の状態を完全に復元できる。
 */
@Entity(tableName = "media_state")
data class MediaStateEntity(
    @PrimaryKey val uri: String,
    val displayName: String = "",
    val mediaType: String = MediaType.VIDEO.name,

    // 再生位置・速度
    val lastPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,

    // 表示(縦横比率・ズーム・パン)
    val aspectMode: String = AspectMode.FIT.name,
    val zoomScale: Float = 1.0f,
    val panX: Float = 0f,
    val panY: Float = 0f,

    // 区間ループ(A-Bループ)
    val loopStartMs: Long = -1L,
    val loopEndMs: Long = -1L,
    val loopEnabled: Boolean = false,

    // お気に入り
    val isFavorite: Boolean = false,

    // このアプリ内だけでの非表示(実ファイルには一切手を加えない)。
    // MIGRATION_1_2でのALTER TABLE(既存行にNOT NULL列を追加するにはSQLite上
    // DEFAULT指定が必須)と、Roomがコンパイル時に期待するスキーマとを一致させる
    // ため、defaultValueを明示している(省略するとRoomの起動時スキーマ検証で
    // 「実際のテーブルにはデフォルト値があるが期待するスキーマには無い」という
    // 不一致になり、マイグレーション後にクラッシュする)。
    @ColumnInfo(defaultValue = "0")
    val isHidden: Boolean = false,

    // ボーカル / 伴奏 個別音量 (0.0〜2.0、1.0が基準)
    val vocalVolume: Float = 1.0f,
    val instrumentalVolume: Float = 1.0f,
    val separatedVocalPath: String? = null,
    val separatedInstrumentalPath: String? = null,
    val separationStatus: String = SeparationStatus.NONE.name,

    // このメディア専用のイコライザー設定(CSV形式のdB値)。nullの場合は全体設定を使用
    val eqBandLevelsCsv: String? = null,
    val eqEnabled: Boolean = true,
    val bassBoostStrength: Int = 0,

    val updatedAt: Long = System.currentTimeMillis()
)

enum class SeparationStatus { NONE, PROCESSING, DONE, FAILED }
