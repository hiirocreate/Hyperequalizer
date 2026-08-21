package jp.hyperequalizer.app.ui.common

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import jp.hyperequalizer.app.extract.AudioExtractor
import jp.hyperequalizer.app.extract.ExtractFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「動画の音声だけを書き出す」機能の共通UI。動画一覧・編集画面など、
 * どこからでも同じ手順(形式選択→バックグラウンドで抽出→端末に保存)で呼び出せる。
 */
object AudioExtractDialogHelper {

    fun start(context: Context, scope: CoroutineScope, sourceUri: Uri, displayName: String) {
        val formats = arrayOf(
            "M4A (推奨・高速・音質そのまま)",
            "WAV (無圧縮・汎用的・容量大)"
        )
        AlertDialog.Builder(context)
            .setTitle("音声を抽出して保存")
            .setMessage("MP3形式そのものへの直接書き出しはAndroid標準機能では対応していないため、\n次のいずれかの形式で保存します。WAVは他の変換ツールで簡単にMP3へ変換できます。")
            .setItems(formats) { dialog, which ->
                dialog.dismiss()
                val format = if (which == 0) ExtractFormat.M4A else ExtractFormat.WAV
                runExtract(context, scope, sourceUri, displayName, format)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun runExtract(context: Context, scope: CoroutineScope, sourceUri: Uri, displayName: String, format: ExtractFormat) {
        Toast.makeText(context, "音声を抽出しています…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = AudioExtractor.extract(context, sourceUri, displayName, format)
            if (result != null) {
                Toast.makeText(context, "音声ファイルを保存しました(音楽タブに表示されます)", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "音声の抽出に失敗しました", Toast.LENGTH_LONG).show()
            }
        }
    }
}
