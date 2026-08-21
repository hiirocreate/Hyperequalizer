package jp.hyperequalizer.app.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * [PlaybackService] へバインドし、共有のExoPlayerインスタンスを取得するための
 * 小さなヘルパー。PlayerActivityとFloatingPlayerServiceの両方から使う。
 */
@UnstableApi
class PlaybackServiceConnector(private val appContext: Context) {

    private var bound = false
    private var connection: ServiceConnection? = null

    fun connect(onReady: (ExoPlayer) -> Unit) {
        // bindのライフサイクル(Activity/Serviceの生存期間)に関わらず再生を継続させるため、
        // 明示的にstartServiceでも起動しておく(単なるbindだけだと、バインド元が
        // すべて無くなった瞬間にサービスごと停止してしまいバックグラウンド再生ができない)
        appContext.startService(Intent(appContext, PlaybackService::class.java))

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? PlaybackService.LocalBinder ?: return
                onReady(binder.getPlayer())
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                bound = false
            }
        }
        connection = conn
        val bindIntent = Intent(appContext, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_LOCAL_BIND
        }
        bound = appContext.bindService(bindIntent, conn, Context.BIND_AUTO_CREATE)
    }

    fun disconnect() {
        if (bound) {
            connection?.let { appContext.unbindService(it) }
        }
        bound = false
        connection = null
    }
}
