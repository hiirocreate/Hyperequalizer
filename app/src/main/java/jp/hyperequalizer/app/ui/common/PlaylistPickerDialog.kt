package jp.hyperequalizer.app.ui.common

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputEditText
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.data.MediaType
import jp.hyperequalizer.app.data.PlaylistEntity
import jp.hyperequalizer.app.data.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「プレイリストに追加」用のシンプルな選択ダイアログ。
 * 既存プレイリスト一覧 + 「新規プレイリストを作成」を選択肢として表示する。
 */
object PlaylistPickerDialog {

    fun show(
        context: Context,
        scope: CoroutineScope,
        repository: PlaylistRepository,
        playlists: List<PlaylistEntity>,
        item: UiMediaItem,
        onAdded: () -> Unit
    ) {
        val labels = playlists.map { it.name }.toMutableList()
        labels.add(context.getString(R.string.action_new_playlist))

        AlertDialog.Builder(context)
            .setTitle(R.string.action_add_to_playlist)
            .setItems(labels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                if (which == playlists.size) {
                    showCreateDialog(context, scope, repository, item, onAdded)
                } else {
                    val target = playlists[which]
                    scope.launch {
                        repository.addItem(target.id, item.uri.toString(), item.displayName, item.mediaType)
                        onAdded()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showCreateDialog(
        context: Context,
        scope: CoroutineScope,
        repository: PlaylistRepository,
        item: UiMediaItem?,
        onAdded: () -> Unit
    ) {
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_text_input, null)
        val input = view.findViewById<TextInputEditText>(R.id.input)
        AlertDialog.Builder(context)
            .setTitle(R.string.action_new_playlist)
            .setView(view)
            .setPositiveButton(R.string.dialog_ok) { dialog, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    scope.launch {
                        val type = item?.mediaType ?: MediaType.MIXED
                        val id = repository.createPlaylist(name, type)
                        if (item != null) {
                            repository.addItem(id, item.uri.toString(), item.displayName, item.mediaType)
                        }
                        onAdded()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    fun showCreateOnly(context: Context, scope: CoroutineScope, repository: PlaylistRepository, onAdded: () -> Unit) {
        showCreateDialog(context, scope, repository, null, onAdded)
    }

    /**
     * まとめて選択した複数項目を、選んだプレイリストへ一括追加する。
     */
    fun showBulk(
        context: Context,
        scope: CoroutineScope,
        repository: PlaylistRepository,
        playlists: List<PlaylistEntity>,
        items: List<UiMediaItem>,
        onAdded: () -> Unit
    ) {
        if (items.isEmpty()) return
        val labels = playlists.map { it.name }.toMutableList()
        labels.add(context.getString(R.string.action_new_playlist))

        AlertDialog.Builder(context)
            .setTitle(R.string.action_add_to_playlist)
            .setItems(labels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                if (which == playlists.size) {
                    showCreateDialogBulk(context, scope, repository, items, onAdded)
                } else {
                    val target = playlists[which]
                    scope.launch {
                        items.forEach { item ->
                            repository.addItem(target.id, item.uri.toString(), item.displayName, item.mediaType)
                        }
                        onAdded()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showCreateDialogBulk(
        context: Context,
        scope: CoroutineScope,
        repository: PlaylistRepository,
        items: List<UiMediaItem>,
        onAdded: () -> Unit
    ) {
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_text_input, null)
        val input = view.findViewById<TextInputEditText>(R.id.input)
        AlertDialog.Builder(context)
            .setTitle(R.string.action_new_playlist)
            .setView(view)
            .setPositiveButton(R.string.dialog_ok) { dialog, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    scope.launch {
                        val type = items.firstOrNull()?.mediaType ?: MediaType.MIXED
                        val id = repository.createPlaylist(name, type)
                        items.forEach { item ->
                            repository.addItem(id, item.uri.toString(), item.displayName, item.mediaType)
                        }
                        onAdded()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
