package com.example.whattodo

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.bottomnavigation.BottomNavigationView

class ShellActivity : AppCompatActivity() {

    private lateinit var bottomNavigationView: BottomNavigationView
    private var currentTabId: Int = R.id.nav_home
    private var pendingReturnTabId: Int = R.id.nav_home

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_host)

        bottomNavigationView = findViewById(R.id.bottomNavigation)
        currentTabId = savedInstanceState?.getInt(STATE_CURRENT_TAB_ID, R.id.nav_home) ?: R.id.nav_home

        setupInsets()
        setupBottomNavigation(savedInstanceState == null)
        setupBackHandling()
    }

    private fun setupInsets() {
        val root = findViewById<android.view.View>(R.id.hostRoot)
        val initialBottomPadding = bottomNavigationView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            bottomNavigationView.setPadding(
                bottomNavigationView.paddingLeft,
                bottomNavigationView.paddingTop,
                bottomNavigationView.paddingRight,
                initialBottomPadding + bars.bottom,
            )
            insets
        }
    }

    private fun setupBottomNavigation(firstLaunch: Boolean) {
        if (firstLaunch) {
            bottomNavigationView.selectedItemId = currentTabId
            showTab(currentTabId, replace = true)
        } else {
            bottomNavigationView.selectedItemId = currentTabId
            restoreExistingFragments()
        }

        bottomNavigationView.setOnItemSelectedListener { item ->
            if (item.itemId == currentTabId) return@setOnItemSelectedListener true
            showTab(item.itemId, replace = false)
            true
        }

        bottomNavigationView.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_home) {
                (supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment)?.scrollToTop()
            }
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTabId != R.id.nav_home) {
                    showTab(R.id.nav_home, replace = false)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun restoreExistingFragments() {
        val selectedTag = tagForTab(currentTabId)
        val fragment = supportFragmentManager.findFragmentByTag(selectedTag)
        if (fragment == null) {
            showTab(currentTabId, replace = true)
        } else {
            showTab(currentTabId, replace = false)
        }
    }

    private fun showTab(tabId: Int, replace: Boolean) {
        val nextTag = tagForTab(tabId)
        val nextFragment = supportFragmentManager.findFragmentByTag(nextTag) ?: createFragmentForTab(tabId)
        val currentFragment = supportFragmentManager.findFragmentByTag(tagForTab(currentTabId))

        supportFragmentManager.commit {
            setReorderingAllowed(true)
            if (currentFragment != null && currentFragment != nextFragment) {
                hide(currentFragment)
                setMaxLifecycle(currentFragment, androidx.lifecycle.Lifecycle.State.STARTED)
            }
            if (supportFragmentManager.findFragmentByTag(nextTag) == null || replace) {
                add(R.id.navHostContainer, nextFragment, nextTag)
            } else {
                show(nextFragment)
            }
            setMaxLifecycle(nextFragment, androidx.lifecycle.Lifecycle.State.RESUMED)
        }

        currentTabId = tabId
        bottomNavigationView.menu.findItem(tabId)?.isChecked = true
    }

    fun selectTab(tabId: Int) {
        if (tabId == currentTabId) return
        showTab(tabId, replace = false)
    }

    fun openTaskEditor(taskId: Long? = null, returnToTabId: Int = R.id.nav_home) {
        pendingReturnTabId = returnToTabId
        if (currentTabId != R.id.nav_home) {
            showTab(R.id.nav_home, replace = false)
        }
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment)?.openTaskEditor(taskId)
    }

    fun onTaskEditorClosed() {
        val targetTab = pendingReturnTabId
        pendingReturnTabId = R.id.nav_home
        if (targetTab != currentTabId) {
            showTab(targetTab, replace = false)
        }
    }

    private fun createFragmentForTab(tabId: Int): Fragment {
        return when (tabId) {
            R.id.nav_schedule -> BrandTabFragment.newInstance(BrandTabFragment.Kind.SCHEDULE)
            R.id.nav_focus -> FocusFragment()
            R.id.nav_data -> BrandTabFragment.newInstance(BrandTabFragment.Kind.DATA)
            R.id.nav_settings -> BrandTabFragment.newInstance(BrandTabFragment.Kind.SETTINGS)
            else -> HomeFragment()
        }
    }

    private fun tagForTab(tabId: Int): String {
        return when (tabId) {
            R.id.nav_schedule -> TAG_SCHEDULE
            R.id.nav_focus -> TAG_FOCUS
            R.id.nav_data -> TAG_DATA
            R.id.nav_settings -> TAG_SETTINGS
            else -> TAG_HOME
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_CURRENT_TAB_ID, currentTabId)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val STATE_CURRENT_TAB_ID = "state_current_tab_id"
        private const val TAG_HOME = "tab_home"
        private const val TAG_SCHEDULE = "tab_schedule"
        private const val TAG_FOCUS = "tab_focus"
        private const val TAG_DATA = "tab_data"
        private const val TAG_SETTINGS = "tab_settings"
    }
}
