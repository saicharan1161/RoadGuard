package com.roadguard.ai

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class ModeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val prefs = getSharedPreferences("connection", MODE_PRIVATE)
        if (!intent.getBooleanExtra("choose", false)) {
            prefs.getString("mode", null)?.let { open(it); return }
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,48,24,24) }
        layout.addView(TextView(this).apply { text = "RoadGuard AI — choose this device's role"; textSize = 24f })
        listOf("car" to "CAR — record USB camera", "phone" to "PHONE — view and download").forEach { (mode, label) ->
            layout.addView(Button(this).apply { text = label; setOnClickListener {
                prefs.edit().putString("mode", mode).apply(); open(mode)
            } })
        }
        setContentView(layout)
    }
    private fun open(mode: String) {
        startActivity(Intent(this, if (mode == "phone") PhoneActivity::class.java else MainActivity::class.java))
        finish()
    }
}
