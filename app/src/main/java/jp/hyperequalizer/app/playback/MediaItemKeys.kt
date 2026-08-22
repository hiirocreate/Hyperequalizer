package jp.hyperequalizer.app.playback

/**
 * MediaItemのメタデータ(extras)に種別(動画/音楽)を埋め込むためのキー。
 * PlayerActivity(書き込み側)とPlaybackService(読み込み側)の両方で同じ
 * 文字列リテラルを使う必要があるため、片方でのタイプミスによる不一致を防ぐ目的で
 * ここに一箇所だけ定義している。
 */
object MediaItemKeys {
    const val EXTRA_MEDIA_ITEM_TYPE = "media_item_type"
}
