package jp.hyperequalizer.app.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import jp.hyperequalizer.app.HyperEqApp
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.data.MediaStateRepository
import jp.hyperequalizer.app.data.MediaType
import jp.hyperequalizer.app.data.PlaylistRepository
import jp.hyperequalizer.app.databinding.FragmentMediaListBinding
import jp.hyperequalizer.app.library.MediaLibraryScanner
import jp.hyperequalizer.app.ui.common.AudioExtractDialogHelper
import jp.hyperequalizer.app.ui.common.MediaFileAdapter
import jp.hyperequalizer.app.ui.common.PlaylistPickerDialog
import jp.hyperequalizer.app.ui.common.UiMediaItem
import jp.hyperequalizer.app.ui.editor.EditorActivity
import jp.hyperequalizer.app.ui.player.PlayerActivity
import jp.hyperequalizer.app.util.MediaDeleter
import jp.hyperequalizer.app.util.MediaPermissions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「動画」「音楽」タブ共通のファイル一覧Fragment。
 */
class MediaListFragment : Fragment() {

    private var _binding: FragmentMediaListBinding? = null
    private val binding get() = _binding!!

    private lateinit var mediaType: MediaType
    private lateinit var scanner: MediaLibraryScanner
    private lateinit var mediaStateRepo: MediaStateRepository
    private lateinit var playlistRepo: PlaylistRepository
    private lateinit var adapter: MediaFileAdapter

    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaType = MediaType.valueOf(requireArguments().getString(ARG_TYPE)!!)
        val app = requireActivity().application as HyperEqApp
        scanner = MediaLibraryScanner(requireContext())
        mediaStateRepo = MediaStateRepository(app.database.mediaStateDao())
        playlistRepo = PlaylistRepository(app.database.playlistDao())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = MediaFileAdapter(onClick = { openPlayer(it) }, onMenu = { anchor, item -> showMenu(anchor, item) })
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.emptyText.text = getString(if (mediaType == MediaType.VIDEO) R.string.empty_videos else R.string.empty_music)
        binding.swipeRefresh.setOnRefreshListener { reload() }
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        if (!MediaPermissions.hasAll(requireContext())) {
            binding.swipeRefresh.isRefreshing = false
            binding.emptyText.visibility = View.VISIBLE
            return
        }
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            val files = if (mediaType == MediaType.VIDEO) scanner.scanVideos() else scanner.scanAudios()
            val favoriteUris = mediaStateRepo.observeFavorites().first().map { it.uri }.toSet()
            val items = files.map {
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
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun openPlayer(item: UiMediaItem) {
        startActivity(PlayerActivity.newIntent(requireContext(), item.uri, item.mediaType))
    }

    private fun showMenu(anchor: View, item: UiMediaItem) {
        val popup = PopupMenu(requireContext(), anchor)
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
                        PlaylistPickerDialog.show(requireContext(), lifecycleScope, playlistRepo, playlists, item) {}
                    }
                    true
                }
                R.id.action_edit -> {
                    startActivity(EditorActivity.newIntent(requireContext(), item.uri))
                    true
                }
                R.id.action_extract_audio -> {
                    AudioExtractDialogHelper.start(requireContext(), lifecycleScope, item.uri, item.displayName)
                    true
                }
                R.id.action_delete -> {
                    MediaDeleter.delete(requireActivity(), item.uri, deleteRequestLauncher)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_TYPE = "arg_type"
        fun newInstance(type: MediaType) = MediaListFragment().apply {
            arguments = bundleOf(ARG_TYPE to type.name)
        }
    }
}
