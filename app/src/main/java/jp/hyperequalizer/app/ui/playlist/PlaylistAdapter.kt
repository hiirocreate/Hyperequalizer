package jp.hyperequalizer.app.ui.playlist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jp.hyperequalizer.app.data.PlaylistEntity
import jp.hyperequalizer.app.databinding.ItemPlaylistBinding

data class PlaylistUi(val entity: PlaylistEntity, val itemCount: Int)

class PlaylistAdapter(
    private val onClick: (PlaylistEntity) -> Unit,
    private val onMenu: (View, PlaylistEntity) -> Unit
) : ListAdapter<PlaylistUi, PlaylistAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PlaylistUi) {
            binding.name.text = item.entity.name
            binding.count.text = "${item.itemCount}件"
            binding.root.setOnClickListener { onClick(item.entity) }
            binding.menuButton.setOnClickListener { onMenu(it, item.entity) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PlaylistUi>() {
            override fun areItemsTheSame(oldItem: PlaylistUi, newItem: PlaylistUi) = oldItem.entity.id == newItem.entity.id
            override fun areContentsTheSame(oldItem: PlaylistUi, newItem: PlaylistUi) = oldItem == newItem
        }
    }
}
