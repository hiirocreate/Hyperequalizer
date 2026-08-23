package jp.hyperequalizer.app

import android.app.Application
import jp.hyperequalizer.app.data.AppDatabase
import jp.hyperequalizer.app.util.CrashLogger

/**
 * アプリ全体で共有するシングルトン(DB接続など)を保持するApplicationクラス。
 */
class HyperEqApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        // クラッシュ発生時にスタックトレースを端末内へ記録しておく(原因調査用)。
        // アプリのどの画面よりも先に、可能な限り早いタイミングで仕込む必要がある。
        CrashLogger.install(this)
    }
}
