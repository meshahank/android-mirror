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
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {
    private lateinit var state: TextView
    private var pendingPairCode: String? = null
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(this, Intent(this, MirrorService::class.java)
                .putExtra("data", result.data)
                .putExtra("result", result.resultCode))
            showSharing(pendingPairCode ?: pairCode())
        } else {
            pendingPairCode = null
            state.text = "Screen sharing was not approved. Nothing is being captured."
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 60, 48, 36); gravity = Gravity.CENTER_HORIZONTAL }
        content.addView(TextView(this).apply { text = "Android Mirror"; textSize = 28f })
        state = TextView(this).apply { textSize = 16f; setPadding(0, 24, 0, 24) }; content.addView(state)
        fun button(label:String, action:()->Unit) = content.addView(MaterialButton(this).apply { text=label; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin=12 })
        button("Start screen sharing") {
            pendingPairCode = newPairCode()
            getSharedPreferences("mirror", MODE_PRIVATE).edit().putString("pair_code", pendingPairCode).commit()
            showSharing(pendingPairCode!!)
            capture.launch((getSystemService(MediaProjectionManager::class.java)).createScreenCaptureIntent())
        }
        button("Stop sharing") {
            startService(Intent(this, MirrorService::class.java).setAction("stop"))
            pendingPairCode = null
            getSharedPreferences("mirror", MODE_PRIVATE).edit().remove("pair_code").commit()
            refresh()
        }
        button("Allow remote control") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("Unpair all computers") { getSharedPreferences("paired", MODE_PRIVATE).edit().clear().apply(); Toast.makeText(this, "All paired computers removed", Toast.LENGTH_SHORT).show() }
        content.addView(TextView(this).apply { text = "Remote control is optional. Enable it only if you want your paired Windows computer to tap, swipe, or use Back/Home/Recents during an active sharing session."; setPadding(0, 20, 0, 0) })
        setContentView(ScrollView(this).apply { addView(content) }); refresh()
    }

    override fun onResume() { super.onResume(); refresh() }
    private fun newPairCode() = (100000 + SecureRandom().nextInt(900000)).toString()
    private fun pairCode() = getSharedPreferences("mirror", MODE_PRIVATE).getString("pair_code", null) ?: newPairCode()
    private fun showSharing(code:String) { state.text = "Local pairing is available\nPair code: $code\nRemote control: " + if (RemoteControlService.instance == null) "off (view-only)" else "enabled" }
    private fun refresh() { val code = getSharedPreferences("mirror", MODE_PRIVATE).getString("pair_code", null); if (code == null) state.text = "Not sharing\nRemote control: " + if (RemoteControlService.instance == null) "off (view-only)" else "enabled" else showSharing(code) }
}
