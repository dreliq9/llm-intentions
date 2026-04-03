package com.example.mycapapp // <-- Change to your package name

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.xmlpull.v1.XmlPullParser

/**
 * CapApp Settings Activity — drop this into any CapApp project.
 *
 * Shows:
 *   - App namespace and version
 *   - List of tools this CapApp provides
 *   - Android permission status (granted / missing)
 *
 * No XML layout dependency — builds its own UI programmatically.
 *
 * Usage:
 *   1. Copy this file into your CapApp's source
 *   2. Change the package name at the top
 *   3. Add to AndroidManifest.xml:
 *      <activity android:name=".CapAppSettingsActivity" android:exported="true">
 *          <intent-filter>
 *              <action android:name="android.intent.action.MAIN" />
 *              <category android:name="android.intent.category.LAUNCHER" />
 *          </intent-filter>
 *      </activity>
 */
class CapAppSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // Read app info
        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val versionName = packageInfo.versionName ?: "unknown"

        // Read tool definitions from manifest metadata
        val namespace = readNamespace()
        val tools = readTools()

        // Header
        layout.addView(heading("CapApp Settings", 24f, density))

        // App Info Card
        layout.addView(heading("App Info", 18f, density))
        layout.addView(bodyText("Namespace: ${namespace ?: "unknown"}", density))
        layout.addView(bodyText("Version: $versionName", density))
        layout.addView(bodyText("Tools: ${tools.size}", density))
        layout.addView(spacer(16, density))

        // Tools List
        layout.addView(heading("Tools", 18f, density))
        if (tools.isEmpty()) {
            layout.addView(bodyText("No tools defined in mcp_tools.xml", density, Color.GRAY))
        } else {
            for ((name, description) in tools) {
                layout.addView(TextView(this).apply {
                    text = name
                    textSize = 14f
                    typeface = Typeface.MONOSPACE
                    setPadding(0, (6 * density).toInt(), 0, 0)
                })
                layout.addView(bodyText(description, density, Color.GRAY))
            }
        }
        layout.addView(spacer(16, density))

        // Permissions
        layout.addView(heading("Permissions", 18f, density))
        val requestedPermissions = packageInfo.requestedPermissions
        if (requestedPermissions.isNullOrEmpty()) {
            layout.addView(bodyText("No permissions requested", density, Color.GRAY))
        } else {
            for (perm in requestedPermissions) {
                val shortName = perm.substringAfterLast('.')
                val granted = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
                val statusColor = if (granted) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
                val statusText = if (granted) "granted" else "missing"

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                }

                row.addView(TextView(this@CapAppSettingsActivity).apply {
                    text = "●"
                    setTextColor(statusColor)
                    setPadding(0, 0, (8 * density).toInt(), 0)
                })

                row.addView(TextView(this@CapAppSettingsActivity).apply {
                    text = "$shortName — $statusText"
                    textSize = 14f
                })

                layout.addView(row)
            }
        }

        scroll.addView(layout)
        setContentView(scroll)
    }

    private fun readNamespace(): String? {
        return try {
            val serviceInfo = packageManager.getServiceInfo(
                android.content.ComponentName(packageName, "$packageName.CommandGatewayService"),
                PackageManager.GET_META_DATA
            )
            val resId = serviceInfo.metaData?.getInt("com.llmintentions.mcp.tools") ?: return null
            val parser = resources.getXml(resId)
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "mcp-tools") {
                    return parser.getAttributeValue(null, "namespace")
                }
                parser.next()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun readTools(): List<Pair<String, String>> {
        val tools = mutableListOf<Pair<String, String>>()
        try {
            val serviceInfo = packageManager.getServiceInfo(
                android.content.ComponentName(packageName, "$packageName.CommandGatewayService"),
                PackageManager.GET_META_DATA
            )
            val resId = serviceInfo.metaData?.getInt("com.llmintentions.mcp.tools") ?: return tools
            val parser = resources.getXml(resId)
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "tool") {
                    val name = parser.getAttributeValue(null, "name") ?: "unnamed"
                    val desc = parser.getAttributeValue(null, "description") ?: ""
                    tools.add(name to desc)
                }
                parser.next()
            }
        } catch (e: Exception) {
            // Service or metadata not found
        }
        return tools
    }

    private fun heading(text: String, size: Float, density: Float): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
        }
    }

    private fun bodyText(text: String, density: Float, color: Int = Color.BLACK): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(color)
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
        }
    }

    private fun spacer(dp: Int, density: Float): android.view.View {
        return android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (dp * density).toInt()
            )
        }
    }
}
