package jp.hyperequalizer.app.ui.common

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.data.MediaType
import jp.hyperequalizer.app.databinding.ItemMediaFileBinding
import jp.hyperequalizer.app.util.ThumbnailLoader
import jp.hyperequalizer.app.util.TimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MediaFileAdapter(
    private val scope: CoroutineScope,
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

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.cancelThumbnailJob()
    }

    inner class VH(private val binding: ItemMediaFileBinding) : RecyclerView.ViewHolder(binding.root) {
        private var thumbJob: Job? = null

        fun bind(item: UiMediaItem) {
            cancelThumbnailJob()
            binding.title.text = item.displayName
            binding.subtitle.text = item.subtitle
            binding.durationBadge.text = TimeFormatter.format(item.durationMs)
            resetToPlaceholder(item.mediaType)
            binding.favoriteIcon.visibility = if (item.isFavorite) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { onClick(item) }
            binding.menuButton.setOnClickListener { onMenu(it, item) }

            val context = binding.root.context
            thumbJob = scope.launch {
                val bmp = if (item.mediaType == MediaType.VIDEO) {
                    ThumbnailLoader.loadVideoThumbnail(context, item.uri)
                } else {
                    ThumbnailLoader.loadAudioArt(context, item.uri)
                }
                if (bmp != null) {
                    binding.iconType.scaleType = ImageView.ScaleType.CENTER_CROP
                    binding.iconType.imageTintList = null
                    binding.iconType.setPadding(0, 0, 0, 0)
                    binding.iconType.setImageBitmap(bmp)
                }
            }
        }

        private fun resetToPlaceholder(mediaType: MediaType) {
            val context = binding.root.context
            binding.iconType.scaleType = ImageView.ScaleType.FIT_CENTER
            binding.iconType.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.hyper_muted))
            val pad = (16 * context.resources.displayMetrics.density).toInt()
            binding.iconType.setPadding(pad, pad, pad, pad)
            binding.iconType.setImageResource(
                if (mediaType == MediaType.VIDEO) R.drawable.ic_video else R.drawable.ic_music
            )
        }

        fun cancelThumbnailJob() {
            thumbJob?.cancel()
            thumbJob = null
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
