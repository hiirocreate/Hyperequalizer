package jp.hyperequalizer.app.ui.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.databinding.ActivityEditorBinding
import jp.hyperequalizer.app.ui.common.AudioExtractDialogHelper
import jp.hyperequalizer.app.util.TimeFormatter

/**
 * 動画の簡易編集画面。
 * トリミング/分割/複数クリップの結合/音声無効化/書き出しを行う。
 */
@UnstableApi
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var player: ExoPlayer
    private val clips = mutableListOf<EditorClip>()
    private lateinit var adapter: EditorClipAdapter
    private var selectedIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private var exporter: VideoExporter? = null

    private val pickClipLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) addClip(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        player = ExoPlayer.Builder(this).build()
        binding.previewPlayerView.player = player

        adapter = EditorClipAdapter(clips) { index ->
            selectedIndex = index
            adapter.selectedIndex = index
            loadClipIntoPreview(clips[index])
        }
        binding.clipRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.clipRecyclerView.adapter = adapter

        val initialUri = intent.getStringExtra(EXTRA_URI)
        if (initialUri != null) {
            addClip(Uri.parse(initialUri))
        }

        setupControls()
        handler.post(previewProgressRunnable)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_extract_audio) {
            if (selectedIndex in clips.indices) {
                val clip = clips[selectedIndex]
                AudioExtractDialogHelper.start(this, lifecycleScope, clip.uri, clip.displayName)
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun addClip(uri: Uri) {
        val name = uri.lastPathSegment ?: uri.toString()
        clips.add(EditorClip(uri = uri, displayName = name, trimStartMs = 0, trimEndMs = -1))
        adapter.notifyItemInserted(clips.size - 1)
        selectedIndex = clips.size - 1
        adapter.selectedIndex = selectedIndex
        loadClipIntoPreview(clips[selectedIndex])
    }

    private fun loadClipIntoPreview(clip: EditorClip) {
        player.setMediaItem(MediaItem.fromUri(clip.uri))
        player.prepare()
        player.seekTo(clip.trimStartMs)
        player.playWhenReady = false
        updateTrimRangeText(clip)
        binding.muteSwitch.isChecked = clip.muted
    }

    private fun setupControls() {
        binding.btnSetTrimStart.setOnClickListener {
            withSelected { clip ->
                clip.trimStartMs = player.currentPosition
                if (clip.trimEndMs in 0..clip.trimStartMs) clip.trimEndMs = -1
                refreshSelected()
            }
        }
        binding.btnSetTrimEnd.setOnClickListener {
            withSelected { clip ->
                if (player.currentPosition > clip.trimStartMs) {
                    clip.trimEndMs = player.currentPosition
                    refreshSelected()
                }
            }
        }
        binding.btnSplit.setOnClickListener {
            withSelected { clip ->
                val splitPoint = player.currentPosition
                if (splitPoint > clip.trimStartMs && (clip.trimEndMs < 0 || splitPoint < clip.trimEndMs)) {
                    val newClip = clip.copy(trimStartMs = splitPoint)
                    clip.trimEndMs = splitPoint
                    clips.add(selectedIndex + 1, newClip)
                    adapter.notifyItemInserted(selectedIndex + 1)
                    refreshSelected()
                    Toast.makeText(this, "分割しました", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.muteSwitch.setOnCheckedChangeListener { _, checked ->
            withSelected { it.muted = checked; refreshSelected() }
        }
        binding.btnAddClip.setOnClickListener { pickClipLauncher.launch("video/*") }
        binding.btnCopyClip.setOnClickListener {
            withSelected { clip ->
                clips.add(selectedIndex + 1, clip.copy())
                adapter.notifyItemInserted(selectedIndex + 1)
                Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnRemoveClip.setOnClickListener {
            if (clips.size > 1 && selectedIndex in clips.indices) {
                clips.removeAt(selectedIndex)
                adapter.notifyDataSetChanged()
                selectedIndex = selectedIndex.coerceIn(0, clips.size - 1)
                adapter.selectedIndex = selectedIndex
                loadClipIntoPreview(clips[selectedIndex])
            }
        }
        binding.btnExport.setOnClickListener { startExport() }

        binding.previewSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && player.duration > 0) {
                    player.seekTo(player.duration * progress / 1000)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private inline fun withSelected(action: (EditorClip) -> Unit) {
        if (selectedIndex in clips.indices) action(clips[selectedIndex])
    }

    private fun refreshSelected() {
        adapter.notifyItemChanged(selectedIndex)
        if (selectedIndex in clips.indices) updateTrimRangeText(clips[selectedIndex])
    }

    private fun updateTrimRangeText(clip: EditorClip) {
        val endLabel = if (clip.trimEndMs >= 0) TimeFormatter.format(clip.trimEndMs) else "末尾"
        binding.trimRangeText.text = "開始 ${TimeFormatter.format(clip.trimStartMs)} / 終了 $endLabel"
    }

    private val previewProgressRunnable = object : Runnable {
        override fun run() {
            if (player.duration > 0 && !binding.previewSeekBar.isPressed) {
                binding.previewSeekBar.progress = (player.currentPosition * 1000 / player.duration).toInt().coerceIn(0, 1000)
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun startExport() {
        if (clips.isEmpty()) return
        binding.btnExport.isEnabled = false
        binding.exportProgress.visibility = View.VISIBLE
        binding.exportProgress.progress = 0
        binding.exportStatusText.text = getString(R.string.editor_exporting, 0)

        val exp = VideoExporter(this)
        exporter = exp
        exp.export(clips, object : VideoExporter.Callback {
            override fun onProgress(percent: Int) {
                runOnUiThread {
                    binding.exportProgress.progress = percent
                    binding.exportStatusText.text = getString(R.string.editor_exporting, percent)
                }
            }

            override fun onSuccess(outputUri: Uri) {
                runOnUiThread {
                    binding.btnExport.isEnabled = true
                    binding.exportProgress.visibility = View.GONE
                    binding.exportStatusText.text = getString(R.string.editor_export_done)
                    Toast.makeText(this@EditorActivity, R.string.editor_export_done, Toast.LENGTH_LONG).show()
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    binding.btnExport.isEnabled = true
                    binding.exportProgress.visibility = View.GONE
                    binding.exportStatusText.text = getString(R.string.editor_export_failed, message)
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        exporter?.cancel()
        player.release()
    }

    companion object {
        private const val EXTRA_URI = "extra_uri"
        fun newIntent(context: Context, uri: Uri): Intent =
            Intent(context, EditorActivity::class.java).putExtra(EXTRA_URI, uri.toString())
    }
}
