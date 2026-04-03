package com.androidmcp.hub

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.androidmcp.hub.databinding.ActivityHubBinding
import com.androidmcp.hub.ui.AppsFragment
import com.androidmcp.hub.ui.DashboardFragment
import com.androidmcp.hub.ui.InboxFragment
import com.androidmcp.hub.ui.ToolsFragment

class HubActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHubBinding

    private val dashboardFragment = DashboardFragment()
    private val appsFragment = AppsFragment()
    private val toolsFragment = ToolsFragment()
    private val inboxFragment = InboxFragment()
    private var activeFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            showFragment(dashboardFragment)
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> showFragment(dashboardFragment)
                R.id.nav_apps -> showFragment(appsFragment)
                R.id.nav_tools -> showFragment(toolsFragment)
                R.id.nav_inbox -> showFragment(inboxFragment)
            }
            true
        }

        requestNotificationPermission()
    }

    private fun showFragment(fragment: Fragment) {
        if (fragment === activeFragment) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
        activeFragment = fragment
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }
}
