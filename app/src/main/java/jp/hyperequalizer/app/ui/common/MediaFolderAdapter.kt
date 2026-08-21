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
import jp.hyperequalizer.app.databinding.ItemMediaFolderBinding
import jp.hyperequalizer.app.util.ThumbnailLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 「フォルダ別」一覧(動画/音楽タブ内の表示切り替え)用のアダプター */
class MediaFolderAdapter(
    private val scope: CoroutineScope,
    private val onClick: (UiMediaFolder) -> Unit,
    private val onMenu: (View, UiMediaFolder) -> Unit
) : ListAdapter<UiMediaFolder, MediaFolderAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMediaFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.cancelThumbnailJob()
    }

    inner class VH(private val binding: ItemMediaFolderBinding) : RecyclerView.ViewHolder(binding.root) {
        private var thumbJob: Job? = null

        fun bind(item: UiMediaFolder) {
            cancelThumbnailJob()
            binding.folderName.text = item.folderPath
            binding.folderCount.text = binding.root.context.getString(R.string.folder_item_count, item.itemCount)
            resetToPlaceholder()
            binding.root.setOnClickListener { onClick(item) }
            binding.menuButton.setOnClickListener { onMenu(it, item) }

            val uri = item.thumbnailUri ?: return
            val context = binding.root.context
            thumbJob = scope.launch {
                val bmp = if (item.mediaType == MediaType.VIDEO) {
                    ThumbnailLoader.loadVideoThumbnail(context, uri)
                } else {
                    ThumbnailLoader.loadAudioArt(context, uri)
                }
                if (bmp != null) {
                    binding.folderThumb.scaleType = ImageView.ScaleType.CENTER_CROP
                    binding.folderThumb.imageTintList = null
                    binding.folderThumb.setPadding(0, 0, 0, 0)
                    binding.folderThumb.setImageBitmap(bmp)
                }
            }
        }

        private fun resetToPlaceholder() {
            val context = binding.root.context
            binding.folderThumb.scaleType = ImageView.ScaleType.FIT_CENTER
            binding.folderThumb.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.hyper_muted))
            val pad = (16 * context.resources.displayMetrics.density).toInt()
            binding.folderThumb.setPadding(pad, pad, pad, pad)
            binding.folderThumb.setImageResource(R.drawable.ic_folder)
        }

        fun cancelThumbnailJob() {
            thumbJob?.cancel()
            thumbJob = null
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<UiMediaFolder>() {
            override fun areItemsTheSame(oldItem: UiMediaFolder, newItem: UiMediaFolder) =
                oldItem.folderPath == newItem.folderPath && oldItem.mediaType == newItem.mediaType

            override fun areContentsTheSame(oldItem: UiMediaFolder, newItem: UiMediaFolder) = oldItem == newItem
        }
    }
}
