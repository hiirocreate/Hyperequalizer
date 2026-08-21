package jp.hyperequalizer.app

import android.app.Application
import jp.hyperequalizer.app.data.AppDatabase

/**
 * アプリ全体で共有するシングルトン(DB接続など)を保持するApplicationクラス。
 */
class HyperEqApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
}
