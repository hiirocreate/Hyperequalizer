package jp.hyperequalizer.app.ui.playlist

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import jp.hyperequalizer.app.HyperEqApp
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.data.MediaType
import jp.hyperequalizer.app.data.PlaylistRepository
import jp.hyperequalizer.app.databinding.ActivityPlaylistDetailBinding
import jp.hyperequalizer.app.ui.common.MediaFileAdapter
import jp.hyperequalizer.app.ui.common.UiMediaItem
import jp.hyperequalizer.app.ui.player.PlayerActivity
import jp.hyperequalizer.app.util.TimeFormatter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlaylistDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistDetailBinding
    private lateinit var repo: PlaylistRepository
    private lateinit var adapter: MediaFileAdapter
    private var playlistId: Long = -1
    private var currentUris: List<String> = emptyList()
    private var currentTypes: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        playlistId = intent.getLongExtra(EXTRA_PLAYLIST_ID, -1)
        val app = application as HyperEqApp
        repo = PlaylistRepository(app.database.playlistDao())

        adapter = MediaFileAdapter(
            scope = lifecycleScope,
            onClick = { item ->
                val index = currentUris.indexOf(item.uri.toString()).coerceAtLeast(0)
                startActivity(PlayerActivity.newIntentForQueue(this, currentUris, currentTypes, index, shuffle = false))
            },
            onMenu = { anchor, item -> showMenu(anchor, item) },
            onSelectionChanged = { count -> updateSelectionBar(count) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        setupSelectionBar()

        binding.btnPlayAll.setOnClickListener {
            if (currentUris.isNotEmpty()) {
                startActivity(PlayerActivity.newIntentForQueue(this, currentUris, currentTypes, 0, shuffle = false))
            }
        }
        binding.btnShufflePlay.setOnClickListener {
            if (currentUris.isNotEmpty()) {
                startActivity(PlayerActivity.newIntentForQueue(this, currentUris, currentTypes, 0, shuffle = true))
            }
        }

        lifecycleScope.launch {
            repo.observePlaylist(playlistId).collectLatest { playlist ->
                supportActionBar?.title = playlist?.name ?: getString(R.string.tab_playlists)
            }
        }

        lifecycleScope.launch {
            repo.observeItems(playlistId).collectLatest { items ->
                currentUris = items.map { it.uri }
                currentTypes = items.map { it.mediaType }
                val ui = items.map {
                    UiMediaItem(
                        uri = Uri.parse(it.uri),
                        displayName = it.displayName,
                        subtitle = TimeFormatter.format(0),
                        durationMs = 0,
                        mediaType = MediaType.valueOf(it.mediaType),
                        isFavorite = false,
                        playlistItemId = it.id
                    )
                }
                adapter.submitList(ui)
                binding.emptyText.visibility = if (ui.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * プレイリスト詳細画面では「まとめて選択」時の操作は「プレイリストから削除」のみ表示する。
     */
    private fun setupSelectionBar() {
        binding.selectionBar.btnSelFavorite.visibility = View.GONE
        binding.selectionBar.btnSelPlaylist.visibility = View.GONE
        binding.selectionBar.btnSelHide.visibility = View.GONE
        binding.selectionBar.btnSelDelete.setText(R.string.action_remove_from_playlist)
        binding.selectionBar.btnSelectionClose.setOnClickListener {
            adapter.exitSelectionMode()
        }
        binding.selectionBar.btnSelDelete.setOnClickListener {
            val items = adapter.selectedItems()
            lifecycleScope.launch {
                items.forEach { repo.removeItemByUri(playlistId, it.uri.toString()) }
                adapter.exitSelectionMode()
            }
        }
    }

    private fun updateSelectionBar(count: Int) {
        if (count <= 0) {
            binding.selectionBar.root.visibility = View.GONE
            return
        }
        binding.selectionBar.root.visibility = View.VISIBLE
        binding.selectionBar.selectionCountText.text = getString(R.string.selection_count_format, count)
    }

    private fun showMenu(anchor: View, item: UiMediaItem) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.action_delete)
        popup.setOnMenuItemClickListener {
            lifecycleScope.launch { repo.removeItemByUri(playlistId, item.uri.toString()) }
            true
        }
        popup.show()
    }

    companion object {
        private const val EXTRA_PLAYLIST_ID = "extra_playlist_id"
        fun newIntent(context: Context, playlistId: Long) =
            Intent(context, PlaylistDetailActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}
