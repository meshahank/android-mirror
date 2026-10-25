package com.shahan.androidmirror.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.shahan.androidmirror.accessibility.RemoteControlService
import com.shahan.androidmirror.service.MirrorService

class MainActivity : AppCompatActivity() {
    private lateinit var state: TextView
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) { ContextCompat.startForegroundService(this, Intent(this, MirrorService::class.java).putExtra("data", result.data).putExtra("result", result.resultCode)); state.text = "Starting local sharing… Pair code will appear below."; state.postDelayed({ refresh() }, 800) }
        else state.text = "Screen sharing was not approved. Nothing is being captured."
    }
    override fun onCreate(saved: Bundle?) { super.onCreate(saved); if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2); val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 60, 48, 36); gravity = Gravity.CENTER_HORIZONTAL }
        content.addView(TextView(this).apply { text = "Android Mirror"; textSize = 28f })
        state = TextView(this).apply { textSize = 16f; setPadding(0, 24, 0, 24) }; content.addView(state)
        fun button(label:String, action:()->Unit) = content.addView(MaterialButton(this).apply { text=label; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin=12 })
        button("Start screen sharing") { capture.launch((getSystemService(MediaProjectionManager::class.java)).createScreenCaptureIntent()) }
        button("Stop sharing") { startService(Intent(this, MirrorService::class.java).setAction("stop")); refresh() }
        button("Allow remote control") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("Unpair all computers") { getSharedPreferences("paired", MODE_PRIVATE).edit().clear().apply(); Toast.makeText(this, "All paired computers removed", Toast.LENGTH_SHORT).show() }
        content.addView(TextView(this).apply { text = "Remote control is optional. Enable it only if you want your paired Windows computer to tap, swipe, or use Back/Home/Recents during an active sharing session."; setPadding(0, 20, 0, 0) })
        setContentView(ScrollView(this).apply { addView(content) }); refresh()
    }
    override fun onResume() { super.onResume(); refresh() }
    private fun refresh() { val code = getSharedPreferences("mirror", MODE_PRIVATE).getString("pair_code", null); state.text = buildString { append(if (code == null) "Not sharing" else "Local pairing is available\nPair code: $code"); append("\nRemote control: "); append(if (RemoteControlService.instance == null) "off (view-only)" else "enabled") } }
}
