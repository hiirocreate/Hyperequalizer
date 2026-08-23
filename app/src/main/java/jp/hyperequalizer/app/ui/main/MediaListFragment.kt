package jp.hyperequalizer.app.ui.main

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import jp.hyperequalizer.app.HyperEqApp
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.data.HiddenFolderRepository
import jp.hyperequalizer.app.data.MediaStateRepository
import jp.hyperequalizer.app.data.MediaType
import jp.hyperequalizer.app.data.PlaylistRepository
import jp.hyperequalizer.app.databinding.FragmentMediaListBinding
import jp.hyperequalizer.app.library.MediaFile
import jp.hyperequalizer.app.library.MediaLibraryScanner
import jp.hyperequalizer.app.playback.NowPlayingState
import jp.hyperequalizer.app.ui.common.AudioExtractDialogHelper
import jp.hyperequalizer.app.ui.common.MediaFileAdapter
import jp.hyperequalizer.app.ui.common.MediaFolderAdapter
import jp.hyperequalizer.app.ui.common.PlaylistPickerDialog
import jp.hyperequalizer.app.ui.common.UiMediaFolder
import jp.hyperequalizer.app.ui.common.UiMediaItem
import jp.hyperequalizer.app.ui.editor.EditorActivity
import jp.hyperequalizer.app.ui.folder.FolderContentsActivity
import jp.hyperequalizer.app.ui.player.PlayerActivity
import jp.hyperequalizer.app.util.MediaDeleter
import jp.hyperequalizer.app.util.MediaPermissions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「動画」「音楽」タブ共通のファイル一覧Fragment。
 * 通常の「一覧」表示と、保存フォルダごとにまとめる「フォルダ別」表示を
 * 切り替えられる。どちらの表示でも、このアプリ上で非表示にした
 * ファイル・フォルダは除外される(実ファイルには一切手を加えない)。
 */
class MediaListFragment : Fragment() {

    private enum class ViewMode { LIST, FOLDER }

    private var _binding: FragmentMediaListBinding? = null
    private val binding get() = _binding!!

    private lateinit var mediaType: MediaType
    private lateinit var scanner: MediaLibraryScanner
    private lateinit var mediaStateRepo: MediaStateRepository
    private lateinit var playlistRepo: PlaylistRepository
    private lateinit var hiddenFolderRepo: HiddenFolderRepository
    private lateinit var adapter: MediaFileAdapter
    private lateinit var folderAdapter: MediaFolderAdapter

    private var viewMode = ViewMode.LIST
    private var allFiles: List<MediaFile> = emptyList()
    private var hiddenUris: Set<String> = emptySet()
    private var hiddenFolders: Set<String> = emptySet()

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
        hiddenFolderRepo = HiddenFolderRepository(app.database.hiddenFolderDao())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = MediaFileAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onClick = { openPlayer(it) },
            onMenu = { anchor, item -> showMenu(anchor, item) },
            onSelectionChanged = { count -> updateSelectionBar(count) }
        )
        folderAdapter = MediaFolderAdapter(
            onClick = { openFolder(it) },
            onMenu = { anchor, folder -> showFolderMenu(anchor, folder) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.emptyText.text = getString(if (mediaType == MediaType.VIDEO) R.string.empty_videos else R.string.empty_music)
        binding.swipeRefresh.setOnRefreshListener { reload() }

        binding.viewModeBar.visibility = View.VISIBLE
        binding.btnViewList.setOnClickListener { setViewMode(ViewMode.LIST) }
        binding.btnViewFolder.setOnClickListener { setViewMode(ViewMode.FOLDER) }
        setViewMode(ViewMode.LIST)
        setupSelectionBar()
        observeNowPlaying()

        reload()
    }

    /** 再生中のコンテンツが変わるたびに一覧内の該当アイテムをハイライトする */
    private fun observeNowPlaying() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NowPlayingState.current.collect { info ->
                    adapter.setCurrentPlayingUri(info?.uri)
                }
            }
        }
    }

    /** まとめて選択した項目に対する一括操作(お気に入り/プレイリスト追加/非表示/削除)のバーを設定する */
    private fun setupSelectionBar() {
        binding.selectionBar.btnSelectionClose.setOnClickListener {
            adapter.exitSelectionMode()
        }
        binding.selectionBar.btnSelFavorite.setOnClickListener {
            val items = adapter.selectedItems()
            lifecycleScope.launch {
                items.forEach {
                    mediaStateRepo.setFavorite(
                        it.uri.toString(), true,
                        displayName = it.displayName, mediaType = it.mediaType, durationMs = it.durationMs
                    )
                }
                adapter.exitSelectionMode()
                reload()
            }
        }
        binding.selectionBar.btnSelPlaylist.setOnClickListener {
            val items = adapter.selectedItems()
            lifecycleScope.launch {
                val playlists = playlistRepo.observePlaylists().first()
                PlaylistPickerDialog.showBulk(requireContext(), lifecycleScope, playlistRepo, playlists, items) {
                    adapter.exitSelectionMode()
                    reload()
                }
            }
        }
        binding.selectionBar.btnSelHide.setOnClickListener {
            val items = adapter.selectedItems()
            lifecycleScope.launch {
                items.forEach {
                    mediaStateRepo.setHidden(
                        it.uri.toString(), true,
                        displayName = it.displayName, mediaType = it.mediaType, durationMs = it.durationMs
                    )
                }
                adapter.exitSelectionMode()
                reload()
            }
        }
        binding.selectionBar.btnSelDelete.setOnClickListener {
            val items = adapter.selectedItems()
            MediaDeleter.deleteAll(requireActivity(), items.map { it.uri }, deleteRequestLauncher)
            lifecycleScope.launch {
                items.forEach { mediaStateRepo.delete(it.uri.toString()) }
                adapter.exitSelectionMode()
                reload()
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

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun setViewMode(mode: ViewMode) {
        if (mode != ViewMode.LIST && ::adapter.isInitialized) {
            adapter.exitSelectionMode()
        }
        viewMode = mode
        binding.recyclerView.adapter = if (mode == ViewMode.LIST) adapter else folderAdapter
        styleViewModeChips()
        renderCurrent()
    }

    private fun styleViewModeChips() {
        val context = requireContext()
        val selectedBg = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.hyper_primary))
        val selectedText = ContextCompat.getColor(context, R.color.white)
        val unselectedText = ContextCompat.getColor(context, R.color.hyper_muted)
        if (viewMode == ViewMode.LIST) {
            binding.btnViewList.backgroundTintList = selectedBg
            binding.btnViewList.setTextColor(selectedText)
            binding.btnViewFolder.backgroundTintList = null
            binding.btnViewFolder.setTextColor(unselectedText)
        } else {
            binding.btnViewFolder.backgroundTintList = selectedBg
            binding.btnViewFolder.setTextColor(selectedText)
            binding.btnViewList.backgroundTintList = null
            binding.btnViewList.setTextColor(unselectedText)
        }
    }

    private fun reload() {
        if (!MediaPermissions.hasAll(requireContext())) {
            binding.swipeRefresh.isRefreshing = false
            binding.emptyText.visibility = View.VISIBLE
            return
        }
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            allFiles = if (mediaType == MediaType.VIDEO) scanner.scanVideos() else scanner.scanAudios()
            hiddenUris = mediaStateRepo.getHiddenUris()
            hiddenFolders = hiddenFolderRepo.getHiddenFolderPaths(mediaType)
            renderCurrent()
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun visibleFiles(): List<MediaFile> =
        allFiles.filter { it.uri.toString() !in hiddenUris && it.folderPath !in hiddenFolders }

    private fun renderCurrent() {
        if (!::adapter.isInitialized) return
        lifecycleScope.launch {
            val favoriteUris = mediaStateRepo.observeFavorites().first().map { it.uri }.toSet()
            val visible = visibleFiles()
            if (viewMode == ViewMode.LIST) {
                val items = visible.map {
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
            } else {
                val folders = visible.groupBy { it.folderPath }
                    .map { (path, files) ->
                        UiMediaFolder(
                            folderPath = path,
                            mediaType = mediaType,
                            itemCount = files.size
                        )
                    }
                    .sortedBy { it.folderPath.lowercase() }
                folderAdapter.submitList(folders)
                binding.emptyText.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openPlayer(item: UiMediaItem) {
        // 一覧(LIST表示)内の他のファイルも含めてキューを組み、リスト再生時に
        // 「次へ」「前へ」やリストループ・シャッフルが効くようにする
        val files = visibleFiles()
        val uris = files.map { it.uri.toString() }
        val types = files.map { it.mediaType.name }
        val startIndex = uris.indexOf(item.uri.toString()).coerceAtLeast(0)
        startActivity(PlayerActivity.newIntentForQueue(requireContext(), uris, types, startIndex, shuffle = false))
    }

    private fun openFolder(folder: UiMediaFolder) {
        startActivity(FolderContentsActivity.newIntent(requireContext(), folder.mediaType, folder.folderPath))
    }

    private fun showFolderMenu(anchor: View, folder: UiMediaFolder) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, R.string.action_hide_folder)
        popup.setOnMenuItemClickListener {
            lifecycleScope.launch {
                hiddenFolderRepo.hide(folder.folderPath, folder.mediaType)
                reload()
            }
            true
        }
        popup.show()
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
