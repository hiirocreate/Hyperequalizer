package jp.hyperequalizer.app.ui.hidden

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import jp.hyperequalizer.app.data.MediaStateEntity
import jp.hyperequalizer.app.databinding.ItemHiddenRowBinding

/** 非表示管理画面の「非表示のファイル」リスト用の簡易アダプター */
class HiddenFileRowAdapter(
    private val onUnhide: (MediaStateEntity) -> Unit
) : RecyclerView.Adapter<HiddenFileRowAdapter.VH>() {

    private var items: List<MediaStateEntity> = emptyList()

    fun submit(list: List<MediaStateEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHiddenRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.rowTitle.text = item.displayName.ifBlank { item.uri }
        holder.binding.btnUnhide.setOnClickListener { onUnhide(item) }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: ItemHiddenRowBinding) : RecyclerView.ViewHolder(binding.root)
}
