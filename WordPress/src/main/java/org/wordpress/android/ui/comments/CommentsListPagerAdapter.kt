package org.wordpress.android.ui.comments

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import org.wordpress.android.ui.comments.CommentsListFragment.CommentStatusCriteria
import java.lang.ref.WeakReference

class CommentsListPagerAdapter(
    private val pages: List<CommentStatusCriteria>,
    fragmentActivity: FragmentActivity
) : FragmentStateAdapter(fragmentActivity) {
    private val listFragments = mutableMapOf<Int, WeakReference<CommentsListFragment>>()

    fun getItemAtPosition(position: Int): CommentsListFragment? {
        return listFragments[position]?.get()
    }

    override fun getItemCount(): Int = pages.size

    override fun createFragment(position: Int): Fragment {
        val fragment = CommentsListFragment.newInstance(pages[position])
        listFragments[position] = WeakReference(fragment)
        return fragment
    }
}
