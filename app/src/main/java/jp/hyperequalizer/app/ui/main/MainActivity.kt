package jp.hyperequalizer.app.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.tabs.TabLayoutMediator
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.databinding.ActivityMainBinding
import jp.hyperequalizer.app.ui.hidden.HiddenItemsActivity
import jp.hyperequalizer.app.util.MediaPermissions

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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

        ensurePermissions()
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
            else -> super.onOptionsItemSelected(item)
        }
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
