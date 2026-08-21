package jp.hyperequalizer.app.ui.main

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import jp.hyperequalizer.app.HyperEqApp
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.data.MediaStateRepository
import jp.hyperequalizer.app.data.MediaType
import jp.hyperequalizer.app.databinding.FragmentMediaListBinding
import jp.hyperequalizer.app.ui.common.MediaFileAdapter
import jp.hyperequalizer.app.ui.common.UiMediaItem
import jp.hyperequalizer.app.ui.player.PlayerActivity
import jp.hyperequalizer.app.util.TimeFormatter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FavoritesFragment : Fragment() {

    private var _binding: FragmentMediaListBinding? = null
    private val binding get() = _binding!!
    private lateinit var repo: MediaStateRepository
    private lateinit var adapter: MediaFileAdapter
    private var currentItems: List<UiMediaItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = requireActivity().application as HyperEqApp
        repo = MediaStateRepository(app.database.mediaStateDao())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.swipeRefresh.isEnabled = false
        binding.emptyText.text = getString(R.string.empty_favorites)
        adapter = MediaFileAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onClick = { item ->
                // お気に入り一覧内の他のファイルも含めてキューを組み、リスト再生時に
                // 「次へ」「前へ」やリストループ・シャッフルが効くようにする
                val uris = currentItems.map { it.uri.toString() }
                val types = currentItems.map { it.mediaType.name }
                val startIndex = uris.indexOf(item.uri.toString()).coerceAtLeast(0)
                startActivity(PlayerActivity.newIntentForQueue(requireContext(), uris, types, startIndex, shuffle = false))
            },
            onMenu = { anchor, item -> showMenu(anchor, item) },
            onSelectionChanged = { count -> updateSelectionBar(count) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        setupSelectionBar()

        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeFavorites().collectLatest { list ->
                // このアプリ上で非表示にした項目は、お気に入り一覧からも除外する
                val items = list.filter { !it.isHidden }.map {
                    UiMediaItem(
                        uri = Uri.parse(it.uri),
                        displayName = it.displayName.ifBlank { it.uri },
                        subtitle = TimeFormatter.format(it.durationMs),
                        durationMs = it.durationMs,
                        mediaType = MediaType.valueOf(it.mediaType),
                        isFavorite = true
                    )
                }
                currentItems = items
                adapter.submitList(items)
                binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * お気に入り画面では「まとめて選択」時の操作は「お気に入りから削除」のみ表示する
     * (プレイリスト追加/非表示/削除の各ボタンは非表示にし、削除ボタンを流用する)。
     */
    private fun setupSelectionBar() {
        binding.selectionBar.btnSelFavorite.visibility = View.GONE
        binding.selectionBar.btnSelPlaylist.visibility = View.GONE
        binding.selectionBar.btnSelHide.visibility = View.GONE
        binding.selectionBar.btnSelDelete.setText(R.string.action_remove_from_favorites)
        binding.selectionBar.btnSelectionClose.setOnClickListener {
            adapter.exitSelectionMode()
        }
        binding.selectionBar.btnSelDelete.setOnClickListener {
            val items = adapter.selectedItems()
            lifecycleScope.launch {
                items.forEach { repo.setFavorite(it.uri.toString(), false) }
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
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(R.string.action_remove_from_favorites)
        popup.setOnMenuItemClickListener {
            lifecycleScope.launch { repo.setFavorite(item.uri.toString(), false) }
            true
        }
        popup.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
