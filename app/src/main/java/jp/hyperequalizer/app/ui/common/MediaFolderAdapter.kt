package jp.hyperequalizer.app.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.databinding.ItemMediaFolderBinding

/**
 * 「フォルダ別」一覧(動画/音楽タブ内の表示切り替え)用のアダプター。
 * サムネイルは表示せず、常に共通のフォルダアイコンを表示する。
 */
class MediaFolderAdapter(
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

    inner class VH(private val binding: ItemMediaFolderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: UiMediaFolder) {
            binding.folderName.text = item.folderPath
            binding.folderCount.text = binding.root.context.getString(R.string.folder_item_count, item.itemCount)
            binding.folderThumb.setImageResource(R.drawable.ic_folder)
            binding.root.setOnClickListener { onClick(item) }
            binding.menuButton.setOnClickListener { onMenu(it, item) }
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
