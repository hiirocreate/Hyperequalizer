package jp.hyperequalizer.app.playback

/**
 * 一覧画面などから大量のファイル(数百〜数千件)をまとめて再生キューとして
 * [jp.hyperequalizer.app.ui.player.PlayerActivity] へ渡す際に使う、プロセス内の受け渡し場所。
 *
 * 従来は Intent の putStringArrayListExtra で URIリストをまるごと渡していたが、
 * これは Binder IPC 経由でシリアライズされるため、端末内の全動画/全音楽のような
 * 大きなライブラリを一覧からそのまま渡すと Android のBinderトランザクションサイズ上限
 * (端末あたり合計で約1MB)を超え、TransactionTooLargeException で
 * アプリがクラッシュすることがあった(「一覧からの再生でクラッシュする」不具合の原因)。
 *
 * これを避けるため、キュー本体(URIリスト・種別リスト・開始位置・シャッフル有無)は
 * Intentには載せず、この同一プロセス内シングルトンに直接保持する。Intent側には
 * 「キューを受け取ってください」という目印(真偽値1個)だけを載せるので、
 * サイズ上限に達することはない。
 *
 * 同一プロセス内での画面遷移(一覧→再生画面)でのみ機能する前提。
 * 別プロセスからの起動(例: 他アプリからのIntent)は元々想定していない。
 */
object PendingPlaybackQueue {

    data class Queue(
        val uris: List<String>,
        val types: List<String>,
        val startIndex: Int,
        val shuffle: Boolean
    )

    @Volatile
    private var pending: Queue? = null

    fun set(uris: List<String>, types: List<String>, startIndex: Int, shuffle: Boolean) {
        pending = Queue(uris, types, startIndex, shuffle)
    }

    /**
     * 一度だけ取り出せる(取り出すと同時にクリアされる)。
     * 画面回転などで同じIntentからActivityが再生成された場合に、
     * 既に消費済みのキューを誤って再適用しないようにするため。
     */
    fun take(): Queue? {
        val q = pending
        pending = null
        return q
    }
}
