package jp.hyperequalizer.app.ui.playlist

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
import jp.hyperequalizer.app.data.PlaylistEntity
import jp.hyperequalizer.app.data.PlaylistRepository
import jp.hyperequalizer.app.databinding.FragmentMediaListBinding
import jp.hyperequalizer.app.ui.common.PlaylistPickerDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlaylistsFragment : Fragment() {

    private var _binding: FragmentMediaListBinding? = null
    private val binding get() = _binding!!
    private lateinit var repo: PlaylistRepository
    private lateinit var adapter: PlaylistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = requireActivity().application as HyperEqApp
        repo = PlaylistRepository(app.database.playlistDao())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.swipeRefresh.isEnabled = false
        binding.emptyText.text = getString(R.string.empty_playlists)
        binding.fab.visibility = View.VISIBLE
        binding.fab.setOnClickListener {
            PlaylistPickerDialog.showCreateOnly(requireContext(), lifecycleScope, repo) {}
        }

        adapter = PlaylistAdapter(
            onClick = { startActivity(PlaylistDetailActivity.newIntent(requireContext(), it.id)) },
            onMenu = { anchor, entity -> showMenu(anchor, entity) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            repo.observePlaylists().collectLatest { playlists ->
                val ui = playlists.map { PlaylistUi(it, repo.itemCount(it.id)) }
                adapter.submitList(ui)
                binding.emptyText.visibility = if (ui.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showMenu(anchor: View, entity: PlaylistEntity) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_playlist_item, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_rename -> {
                    val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_text_input, null)
                    val input = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input)
                    input.setText(entity.name)
                    androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(R.string.action_rename)
                        .setView(view)
                        .setPositiveButton(R.string.dialog_ok) { d, _ ->
                            val newName = input.text?.toString()?.trim().orEmpty()
                            if (newName.isNotEmpty()) {
                                lifecycleScope.launch { repo.renamePlaylist(entity, newName) }
                            }
                            d.dismiss()
                        }
                        .setNegativeButton(R.string.dialog_cancel, null)
                        .show()
                    true
                }
                R.id.action_delete -> {
                    lifecycleScope.launch { repo.deletePlaylist(entity.id) }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
