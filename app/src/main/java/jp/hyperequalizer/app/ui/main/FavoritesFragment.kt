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
            onClick = { startActivity(PlayerActivity.newIntent(requireContext(), it.uri, it.mediaType)) },
            onMenu = { anchor, item -> showMenu(anchor, item) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

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
                adapter.submitList(items)
                binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
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
