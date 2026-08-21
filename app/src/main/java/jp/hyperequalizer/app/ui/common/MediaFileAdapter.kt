package jp.hyperequalizer.app.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.data.MediaType
import jp.hyperequalizer.app.databinding.ItemMediaFileBinding
import jp.hyperequalizer.app.util.TimeFormatter

class MediaFileAdapter(
    private val onClick: (UiMediaItem) -> Unit,
    private val onMenu: (View, UiMediaItem) -> Unit
) : ListAdapter<UiMediaItem, MediaFileAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMediaFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemMediaFileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: UiMediaItem) {
            binding.title.text = item.displayName
            binding.subtitle.text = item.subtitle
            binding.durationBadge.text = TimeFormatter.format(item.durationMs)
            binding.iconType.setImageResource(
                if (item.mediaType == MediaType.VIDEO) R.drawable.ic_video else R.drawable.ic_music
            )
            binding.favoriteIcon.visibility = if (item.isFavorite) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { onClick(item) }
            binding.menuButton.setOnClickListener { onMenu(it, item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<UiMediaItem>() {
            override fun areItemsTheSame(oldItem: UiMediaItem, newItem: UiMediaItem) =
                oldItem.uri == newItem.uri && oldItem.playlistItemId == newItem.playlistItemId

            override fun areContentsTheSame(oldItem: UiMediaItem, newItem: UiMediaItem) = oldItem == newItem
        }
    }
}
