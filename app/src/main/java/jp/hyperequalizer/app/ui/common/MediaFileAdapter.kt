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

/**
 * ファイル一覧用アダプター。
 * 長押しで「まとめて選択」モードに入り、タップでチェックのON/OFFを切り替えられる。
 * 選択状態が変わるたびに [onSelectionChanged] へ現在の選択件数を通知する
 * (呼び出し側はこれを使って選択操作バーの表示/非表示や件数表示を更新する)。
 */
class MediaFileAdapter(
    private val scope: CoroutineScope,
    private val onClick: (UiMediaItem) -> Unit,
    private val onMenu: (View, UiMediaItem) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {}
) : ListAdapter<UiMediaItem, MediaFileAdapter.VH>(DIFF) {

    private var selectionMode = false
    private val selectedKeys = mutableSetOf<String>()

    /** 現在再生中のアイテムのURI(未再生ならnull)。一覧内のハイライト表示に使う。 */
    private var currentPlayingUri: String? = null

    private fun keyOf(item: UiMediaItem): String = "${item.uri}|${item.playlistItemId}"

    /**
     * 再生中のURIをセットし、一覧内のハイライト表示を更新する。
     * 旧・新の再生中アイテムだけを再描画すればよいため、リスト全体の再スキャンは不要。
     */
    fun setCurrentPlayingUri(uri: String?) {
        if (uri == currentPlayingUri) return
        val previousUri = currentPlayingUri
        currentPlayingUri = uri
        currentList.forEachIndexed { index, item ->
            val itemUri = item.uri.toString()
            if (itemUri == previousUri || itemUri == uri) {
                notifyItemChanged(index)
            }
        }
    }

    fun isSelectionMode(): Boolean = selectionMode

    fun selectedCount(): Int = selectedKeys.size

    fun selectedItems(): List<UiMediaItem> = currentList.filter { selectedKeys.contains(keyOf(it)) }

    fun enterSelectionMode(initialItem: UiMediaItem) {
        selectionMode = true
        selectedKeys.clear()
        selectedKeys.add(keyOf(initialItem))
        notifyDataSetChanged()
        onSelectionChanged(selectedKeys.size)
    }

    fun exitSelectionMode() {
        if (!selectionMode) return
        selectionMode = false
        selectedKeys.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun toggleSelection(item: UiMediaItem) {
        val key = keyOf(item)
        if (selectedKeys.contains(key)) selectedKeys.remove(key) else selectedKeys.add(key)
        if (selectedKeys.isEmpty()) {
            exitSelectionMode()
            return
        }
        val index = currentList.indexOfFirst { keyOf(it) == key }
        if (index >= 0) notifyItemChanged(index)
        onSelectionChanged(selectedKeys.size)
    }

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

            val selected = selectedKeys.contains(keyOf(item))
            binding.selectionCheckbox.visibility = if (selectionMode) View.VISIBLE else View.GONE
            binding.selectionCheckbox.isChecked = selected
            binding.menuButton.visibility = if (selectionMode) View.GONE else View.VISIBLE

            val isPlaying = item.uri.toString() == currentPlayingUri
            binding.nowPlayingIndicator.visibility = if (isPlaying) View.VISIBLE else View.GONE
            binding.title.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (isPlaying) R.color.hyper_accent else R.color.hyper_on_surface
                )
            )

            binding.root.setOnClickListener {
                if (selectionMode) toggleSelection(item) else onClick(item)
            }
            binding.root.setOnLongClickListener {
                if (!selectionMode) {
                    enterSelectionMode(item)
                }
                true
            }
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
