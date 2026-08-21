package jp.hyperequalizer.app.ui.hidden

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import jp.hyperequalizer.app.HyperEqApp
import jp.hyperequalizer.app.data.HiddenFolderRepository
import jp.hyperequalizer.app.data.MediaStateRepository
import jp.hyperequalizer.app.data.MediaType
import jp.hyperequalizer.app.databinding.ActivityHiddenItemsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * このアプリ上で非表示にしたファイル・フォルダをまとめて確認し、
 * 個別に再表示(非表示解除)できる管理画面。
 */
class HiddenItemsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHiddenItemsBinding
    private lateinit var mediaStateRepo: MediaStateRepository
    private lateinit var hiddenFolderRepo: HiddenFolderRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHiddenItemsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val app = application as HyperEqApp
        mediaStateRepo = MediaStateRepository(app.database.mediaStateDao())
        hiddenFolderRepo = HiddenFolderRepository(app.database.hiddenFolderDao())

        val fileAdapter = HiddenFileRowAdapter { entity ->
            lifecycleScope.launch {
                mediaStateRepo.setHidden(entity.uri, false)
            }
        }
        val folderAdapter = HiddenFolderRowAdapter { entity ->
            lifecycleScope.launch {
                hiddenFolderRepo.unhide(entity.folderPath, MediaType.valueOf(entity.mediaType))
            }
        }
        binding.hiddenFilesRecycler.layoutManager = LinearLayoutManager(this)
        binding.hiddenFilesRecycler.adapter = fileAdapter
        binding.hiddenFoldersRecycler.layoutManager = LinearLayoutManager(this)
        binding.hiddenFoldersRecycler.adapter = folderAdapter

        lifecycleScope.launch {
            mediaStateRepo.observeHidden().collectLatest { list ->
                fileAdapter.submit(list)
                binding.emptyHiddenFiles.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            hiddenFolderRepo.observeAll().collectLatest { list ->
                folderAdapter.submit(list)
                binding.emptyHiddenFolders.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, HiddenItemsActivity::class.java)
    }
}
