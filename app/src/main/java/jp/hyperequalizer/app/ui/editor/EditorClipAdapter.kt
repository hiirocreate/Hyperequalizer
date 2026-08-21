package jp.hyperequalizer.app.ui.editor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import jp.hyperequalizer.app.databinding.ItemEditorClipBinding
import jp.hyperequalizer.app.util.TimeFormatter

class EditorClipAdapter(
    private val clips: MutableList<EditorClip>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<EditorClipAdapter.VH>() {

    var selectedIndex: Int = -1
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemEditorClipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = clips.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val clip = clips[position]
        val endLabel = if (clip.trimEndMs >= 0) TimeFormatter.format(clip.trimEndMs) else "末尾"
        holder.binding.clipLabel.text = "${position + 1}. ${clip.displayName}  ${TimeFormatter.format(clip.trimStartMs)}-$endLabel${if (clip.muted) " (無音)" else ""}"
        holder.binding.selectedIndicator.visibility = if (position == selectedIndex) View.VISIBLE else View.INVISIBLE
        holder.binding.root.setOnClickListener { onClick(position) }
    }

    class VH(val binding: ItemEditorClipBinding) : RecyclerView.ViewHolder(binding.root)
}
