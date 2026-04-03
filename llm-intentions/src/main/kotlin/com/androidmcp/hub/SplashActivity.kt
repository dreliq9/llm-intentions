package com.androidmcp.hub

import android.content.Intent
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.androidmcp.hub.databinding.ActivitySplashBinding
import com.androidmcp.hub.stdio.HubHttpService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                onSplashVisible()
            }
        })
    }

    private fun onSplashVisible() {
        startHttpService()

        lifecycleScope.launch {
            binding.statusText.text = "Starting service..."

            repeat(40) {
                if (HubHttpService.sharedEngine != null) {
                    binding.statusText.text = "Discovering tools..."
                    delay(300)
                    launchHub()
                    return@launch
                }
                delay(250)
            }

            binding.statusText.text = "Service slow to start..."
            delay(500)
            launchHub()
        }
    }

    private fun launchHub() {
        startActivity(Intent(this, HubActivity::class.java))
        finish()
    }

    private fun startHttpService() {
        val intent = Intent(this, HubHttpService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
