package jp.hyperequalizer.app.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.tabs.TabLayoutMediator
import jp.hyperequalizer.app.R
import jp.hyperequalizer.app.databinding.ActivityMainBinding
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

    private fun ensurePermissions() {
        val needed = MediaPermissions.required().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
