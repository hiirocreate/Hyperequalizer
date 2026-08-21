package jp.hyperequalizer.app.ui.main

import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import jp.hyperequalizer.app.data.MediaType
import jp.hyperequalizer.app.ui.playlist.PlaylistsFragment

class LibraryPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int) = when (position) {
        0 -> MediaListFragment.newInstance(MediaType.VIDEO)
        1 -> MediaListFragment.newInstance(MediaType.AUDIO)
        2 -> PlaylistsFragment()
        3 -> FavoritesFragment()
        else -> throw IllegalStateException("unknown tab $position")
    }
}
