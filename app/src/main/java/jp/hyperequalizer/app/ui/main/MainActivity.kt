package jp.hyperequalizer.app.ui.main

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.util.UnstableApi
import com.google.android.material.tabs.TabLayoutMediator
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.databinding.ActivityMainBinding
import jp.hyperequalizer.app.ui.common.NowPlayingBarController
import jp.hyperequalizer.app.ui.hidden.HiddenItemsActivity
import jp.hyperequalizer.app.util.CrashLogger
import jp.hyperequalizer.app.util.MediaPermissions

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var nowPlayingBar: NowPlayingBarController? = null

    private val tabTitles by lazy {
        listOf(
            getString(R.string.tab_videos),
            getString(R.string.tab_music),
            getString(R.string.tab_playlists),
            getString(R.string.tab_favorites)
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 結果に関わらずUIは各Fragmentが再読込を担当 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.viewPager.adapter = LibraryPagerAdapter(this)
        binding.viewPager.offscreenPageLimit = 3
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()

        nowPlayingBar = NowPlayingBarController(
            activity = this,
            barRoot = binding.nowPlayingBar.root,
            icon = binding.nowPlayingBar.nowPlayingIcon,
            title = binding.nowPlayingBar.nowPlayingTitle,
            playPauseButton = binding.nowPlayingBar.nowPlayingPlayPause
        ).also { it.start() }

        ensurePermissions()

        // 前回起動時にクラッシュしていた場合、その場で内容を確認できるようにする
        // (原因調査のため、その内容をそのまま報告してもらうことを想定している)。
        // 設定変更などでActivityが再生成された場合に毎回出ないよう、プロセスの
        // 初回起動(savedInstanceState == null)時のみ自動表示する。
        if (savedInstanceState == null) {
            checkForCrashLog()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nowPlayingBar?.stop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_manage_hidden -> {
                startActivity(HiddenItemsActivity.newIntent(this))
                true
            }
            R.id.action_view_crash_log -> {
                showCrashLogDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun checkForCrashLog() {
        if (CrashLogger.getLastCrashLog(this) != null) {
            showCrashLogDialog()
        }
    }

    /** クラッシュログをダイアログで表示し、コピーして報告しやすくする */
    private fun showCrashLogDialog() {
        val log = CrashLogger.getLastCrashLog(this)
        if (log == null) {
            Toast.makeText(this, R.string.crash_log_none, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_log_dialog_title)
            .setMessage(log)
            .setPositiveButton(R.string.crash_log_copy) { _, _ -> copyCrashLogToClipboard(log) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun copyCrashLogToClipboard(log: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("crash_log", log))
        Toast.makeText(this, R.string.crash_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun ensurePermissions() {
        val all = MediaPermissions.required() + MediaPermissions.notificationPermissionIfNeeded()
        val needed = all.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
