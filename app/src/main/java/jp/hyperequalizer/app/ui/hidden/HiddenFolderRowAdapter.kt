package jp.hyperequalizer.app.ui.hidden

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import jp.hyperequalizer.app.data.HiddenFolderEntity
import jp.hyperequalizer.app.databinding.ItemHiddenRowBinding

/** 非表示管理画面の「非表示のフォルダ」リスト用の簡易アダプター */
class HiddenFolderRowAdapter(
    private val onUnhide: (HiddenFolderEntity) -> Unit
) : RecyclerView.Adapter<HiddenFolderRowAdapter.VH>() {

    private var items: List<HiddenFolderEntity> = emptyList()

    fun submit(list: List<HiddenFolderEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHiddenRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.rowTitle.text = item.folderPath
        holder.binding.btnUnhide.setOnClickListener { onUnhide(item) }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemHiddenRowBinding) : RecyclerView.ViewHolder(binding.root)
}
