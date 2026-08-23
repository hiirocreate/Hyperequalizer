package jp.hyperequalizer.app.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * アプリがクラッシュした際のスタックトレースを端末内のファイルに保存しておく
 * ための仕組み。
 *
 * クラッシュの原因を正確に直すには実際のスタックトレースが一番の手がかりに
 * なるが、開発側は端末のログ(logcat)を直接見ることができない。そこで、
 * クラッシュが発生したら次回起動時にアプリ内から内容を確認・コピーできる
 * ようにしておき、その内容を報告してもらうことで正確な原因特定ができるようにする。
 *
 * 標準の[Thread.UncaughtExceptionHandler]をラップし、ログをファイルへ書き出した
 * 上で必ず元のハンドラー(システム標準のクラッシュ処理)へ委譲する。つまり
 * クラッシュを揉み消すのではなく、記録を追加するだけの仕組み。
 */
object CrashLogger {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(appContext, thread, throwable)
            } catch (e: Throwable) {
                // ログ保存自体に失敗しても、下の元のクラッシュ処理は必ず継続させる
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val content = buildString {
            append("発生日時: ").append(timestamp).append("\n")
            append("スレッド: ").append(thread.name).append("\n")
            append("--------------------------------\n")
            append(sw.toString())
        }
        File(context.filesDir, FILE_NAME).writeText(content)
    }

    /** 記録済みのクラッシュログがあれば返す(無ければnull)。新しいクラッシュが起きるたびに上書きされる。 */
    fun getLastCrashLog(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return if (file.exists()) file.readText() else null
    }

    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE_NAME).delete()
    }
}
