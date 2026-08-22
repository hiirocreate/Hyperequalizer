package jp.hyperequalizer.app.playback

import jp.hyperequalizer.app.data.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 現在再生中のコンテンツ1件分の情報。メイン画面/リスト/フォルダ画面での
 *  「再生中インジケーター」表示に使う。 */
data class NowPlayingInfo(
    val uri: String,
    val displayName: String,
    val mediaType: MediaType,
    val isPlaying: Boolean
)

/**
 * 「今何を再生しているか」をアプリ内のどの画面からでも購読できるようにする
 * 軽量なプロセス内Pub/Sub。PlaybackService側のExoPlayerイベントで更新し、
 * MainActivityやリスト/フォルダ画面のFragment/Activityがこれを収集して
 * ショートカットバーの表示や、リスト内の再生中アイテムのハイライトに使う。
 * わざわざ各画面がPlaybackServiceへバインドしなくても状態が分かるようにするための仕組み。
 */
object NowPlayingState {
    private val _current = MutableStateFlow<NowPlayingInfo?>(null)
    val current: StateFlow<NowPlayingInfo?> = _current.asStateFlow()

    fun update(info: NowPlayingInfo) {
        _current.value = info
    }

    fun clear() {
        _current.value = null
    }
}
