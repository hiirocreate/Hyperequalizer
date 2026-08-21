package jp.hyperequalizer.app.ui.folder

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import jp.hyperequalizer.app.HyperEqApp
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.data.MediaStateRepository
import jp.hyperequalizer.app.data.MediaType
import jp.hyperequalizer.app.data.PlaylistRepository
import jp.hyperequalizer.app.databinding.ActivityFolderContentsBinding
import jp.hyperequalizer.app.library.MediaLibraryScanner
import jp.hyperequalizer.app.ui.common.AudioExtractDialogHelper
import jp.hyperequalizer.app.ui.common.MediaFileAdapter
import jp.hyperequalizer.app.ui.common.PlaylistPickerDialog
import jp.hyperequalizer.app.ui.common.UiMediaItem
import jp.hyperequalizer.app.ui.editor.EditorActivity
import jp.hyperequalizer.app.ui.player.PlayerActivity
import jp.hyperequalizer.app.util.MediaDeleter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「フォルダ別」一覧からフォルダをタップした際に表示する、
 * そのフォルダ内のファイルだけを絞り込んだ一覧画面。
 */
class FolderContentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFolderContentsBinding
    private lateinit var scanner: MediaLibraryScanner
    private lateinit var mediaStateRepo: MediaStateRepository
    private lateinit var playlistRepo: PlaylistRepository
    private lateinit var adapter: MediaFileAdapter
    private lateinit var mediaType: MediaType
    private lateinit var folderPath: String

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFolderContentsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        mediaType = MediaType.valueOf(intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: MediaType.VIDEO.name)
        folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH) ?: ""
        supportActionBar?.title = folderPath

        val app = application as HyperEqApp
        scanner = MediaLibraryScanner(this)
        mediaStateRepo = MediaStateRepository(app.database.mediaStateDao())
        playlistRepo = PlaylistRepository(app.database.playlistDao())

        adapter = MediaFileAdapter(
            scope = lifecycleScope,
            onClick = { startActivity(PlayerActivity.newIntent(this, it.uri, it.mediaType)) },
            onMenu = { anchor, item -> showMenu(anchor, item) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.emptyText.text = getString(if (mediaType == MediaType.VIDEO) R.string.empty_videos else R.string.empty_music)

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val files = (if (mediaType == MediaType.VIDEO) scanner.scanVideos() else scanner.scanAudios())
                .filter { it.folderPath == folderPath }
            val hiddenUris = mediaStateRepo.getHiddenUris()
            val favoriteUris = mediaStateRepo.observeFavorites().first().map { it.uri }.toSet()
            val items = files
                .filter { it.uri.toString() !in hiddenUris }
                .map {
                    UiMediaItem(
                        uri = it.uri,
                        displayName = it.displayName,
                        subtitle = formatSize(it.sizeBytes),
                        durationMs = it.durationMs,
                        mediaType = it.mediaType,
                        isFavorite = favoriteUris.contains(it.uri.toString())
                    )
                }
            adapter.submitList(items)
            binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showMenu(anchor: View, item: UiMediaItem) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_media_item, popup.menu)
        popup.menu.findItem(R.id.action_toggle_favorite).setTitle(
            if (item.isFavorite) R.string.action_remove_from_favorites else R.string.action_add_to_favorites
        )
        popup.menu.findItem(R.id.action_edit).isVisible = (item.mediaType == MediaType.VIDEO)
        popup.menu.findItem(R.id.action_extract_audio).isVisible = (item.mediaType == MediaType.VIDEO)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_toggle_favorite -> {
                    lifecycleScope.launch {
                        mediaStateRepo.setFavorite(
                            item.uri.toString(), !item.isFavorite,
                            displayName = item.displayName, mediaType = item.mediaType, durationMs = item.durationMs
                        )
                        reload()
                    }
                    true
                }
                R.id.action_add_to_playlist -> {
                    lifecycleScope.launch {
                        val playlists = playlistRepo.observePlaylists().first()
                        PlaylistPickerDialog.show(this@FolderContentsActivity, lifecycleScope, playlistRepo, playlists, item) {}
                    }
                    true
                }
                R.id.action_edit -> {
                    startActivity(EditorActivity.newIntent(this, item.uri))
                    true
                }
                R.id.action_extract_audio -> {
                    AudioExtractDialogHelper.start(this, lifecycleScope, item.uri, item.displayName)
                    true
                }
                R.id.action_toggle_hidden -> {
                    lifecycleScope.launch {
                        mediaStateRepo.setHidden(
                            item.uri.toString(), true,
                            displayName = item.displayName, mediaType = item.mediaType, durationMs = item.durationMs
                        )
                        reload()
                    }
                    true
                }
                R.id.action_delete -> {
                    MediaDeleter.delete(this, item.uri, deleteRequestLauncher)
                    lifecycleScope.launch {
                        mediaStateRepo.delete(item.uri.toString())
                        reload()
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.1f MB", mb)
    }

    companion object {
        private const val EXTRA_MEDIA_TYPE = "extra_media_type"
        private const val EXTRA_FOLDER_PATH = "extra_folder_path"

        fun newIntent(context: Context, mediaType: MediaType, folderPath: String): Intent =
            Intent(context, FolderContentsActivity::class.java)
                .putExtra(EXTRA_MEDIA_TYPE, mediaType.name)
                .putExtra(EXTRA_FOLDER_PATH, folderPath)
    }
}
