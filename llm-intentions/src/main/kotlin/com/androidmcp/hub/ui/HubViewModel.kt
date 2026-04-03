package com.androidmcp.hub.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.androidmcp.core.protocol.ToolInfo
import com.androidmcp.hub.HubMcpEngine
import com.androidmcp.hub.discovery.DiscoveredApp
import com.androidmcp.hub.health.IntentHealthMonitor
import com.androidmcp.hub.inbox.InboxManager
import com.androidmcp.hub.stdio.HubHttpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HubViewModel(application: Application) : AndroidViewModel(application) {

    private val _isServiceRunning = MutableLiveData(false)
    val isServiceRunning: LiveData<Boolean> = _isServiceRunning

    private val _discoveredApps = MutableLiveData<List<DiscoveredApp>>(emptyList())
    val discoveredApps: LiveData<List<DiscoveredApp>> = _discoveredApps

    private val _healthStatuses = MutableLiveData<Map<String, IntentHealthMonitor.HealthStatus>>(emptyMap())
    val healthStatuses: LiveData<Map<String, IntentHealthMonitor.HealthStatus>> = _healthStatuses

    private val _toolsByNamespace = MutableLiveData<Map<String, List<ToolInfo>>>(emptyMap())
    val toolsByNamespace: LiveData<Map<String, List<ToolInfo>>> = _toolsByNamespace

    private val _toolCount = MutableLiveData(0)
    val toolCount: LiveData<Int> = _toolCount

    private val _inboxMessages = MutableLiveData<List<InboxManager.Message>>(emptyList())
    val inboxMessages: LiveData<List<InboxManager.Message>> = _inboxMessages

    private val _isRefreshing = MutableLiveData(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    init {
        refreshState()
    }

    fun refreshState() {
        val engine = HubHttpService.sharedEngine
        _isServiceRunning.value = engine != null

        if (engine != null) {
            _discoveredApps.value = engine.discoveredApps
            _toolCount.value = engine.registry.size()
            groupTools(engine)
        }
    }

    fun refreshApps() {
        val engine = HubHttpService.sharedEngine ?: return
        _isRefreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            engine.refresh()
            withContext(Dispatchers.Main) {
                _discoveredApps.value = engine.discoveredApps
                _toolCount.value = engine.registry.size()
                groupTools(engine)
                _isRefreshing.value = false
            }
        }
    }

    fun checkHealth() {
        val engine = HubHttpService.sharedEngine ?: return
        val apps = engine.discoveredApps
        if (apps.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val statuses = engine.healthMonitor.checkAll(apps)
            val map = statuses.associateBy { it.packageName }
            withContext(Dispatchers.Main) {
                _healthStatuses.value = map
            }
        }
    }

    fun refreshInbox() {
        _inboxMessages.value = InboxManager.peek(100)
    }

    fun dismissMessage(id: Long) {
        InboxManager.remove(id)
        refreshInbox()
    }

    fun clearInbox() {
        InboxManager.clear()
        refreshInbox()
    }

    fun startService() {
        if (_isServiceRunning.value == true) return
        val context = getApplication<Application>()
        val intent = Intent(context, HubHttpService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        viewModelScope.launch {
            // Poll until engine is available (service started)
            repeat(10) {
                kotlinx.coroutines.delay(500)
                if (HubHttpService.sharedEngine != null) {
                    refreshState()
                    return@launch
                }
            }
            refreshState()
        }
    }

    fun stopService() {
        if (_isServiceRunning.value == false) return
        val context = getApplication<Application>()
        context.stopService(Intent(context, HubHttpService::class.java))
        _isServiceRunning.value = false
        _toolCount.value = 0
        _discoveredApps.value = emptyList()
        _toolsByNamespace.value = emptyMap()
    }

    private fun groupTools(engine: HubMcpEngine) {
        val allTools = engine.registry.list()
        val grouped = allTools.groupBy { tool ->
            val dot = tool.name.indexOf('.')
            if (dot > 0) tool.name.substring(0, dot) else "other"
        }
        _toolsByNamespace.value = grouped
    }
}
