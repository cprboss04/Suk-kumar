package com.agentpilot

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.WindowInsets
import android.widget.*

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var runBtn: Button
    private val h = Handler(Looper.getMainLooper())

    // Har 700ms par status + log refresh
    private val tick = object : Runnable {
        override fun run() {
            status.text = if (Bus.service != null) "Service: ON" else "Service: OFF (Accessibility me enable karo)"
            logView.text = synchronized(Bus.logs) { Bus.logs.joinToString("\n") }
            runBtn.isEnabled = !Bus.running
            h.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(col) }
        // Android 15: edge-to-edge enforce hai, isliye system bars ke liye padding
        scroll.setOnApplyWindowInsetsListener { v, ins ->
            val s = ins.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(pad + s.left, pad + s.top, pad + s.right, pad + s.bottom)
            ins
        }
        setContentView(scroll)

        fun field(ph: String, key: String, secret: Boolean = false, n: Int = 1): EditText =
            EditText(this).apply {
                hint = ph
                setText(prefs.getString(key, ""))
                if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                if (n > 1) { minLines = n; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
                col.addView(this)
            }
        fun btn(t: String, f: () -> Unit): Button =
            Button(this).apply { text = t; setOnClickListener { f() }; col.addView(this) }

        col.addView(TextView(this).apply { text = "Agent Pilot"; textSize = 22f })
        status = TextView(this).also { col.addView(it) }
        btn("1) Accessibility settings kholo") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        val key = field("API key", "key", secret = true)
        val base = field("Base URL (khali = OpenAI)", "base")
        val model = field("Model (khali = gpt-4o-mini)", "model")
        val goal = field("Goal: kya karwana hai?", "goal", n = 3)
        val risky = CheckBox(this).apply { text = "Risky buttons allow karo (send/pay/delete...)"; col.addView(this) }
        runBtn = btn("2) Run (phir 5 sec me target app kholo)") {
            val k = key.text.toString().trim(); val g = goal.text.toString().trim()
            prefs.edit().putString("key", k).putString("base", base.text.toString().trim())
                .putString("model", model.text.toString().trim()).putString("goal", g).apply()
            val svc = Bus.service
            when {
                svc == null -> Toast.makeText(this, "Pehle Accessibility service ON karo", Toast.LENGTH_LONG).show()
                k.isEmpty() || g.isEmpty() -> Toast.makeText(this, "API key aur goal daalo", Toast.LENGTH_SHORT).show()
                else -> {
                    svc.start(g, Cfg(k,
                        base.text.toString().trim().ifEmpty { "https://api.openai.com/v1" },
                        model.text.toString().trim().ifEmpty { "gpt-4o-mini" },
                        risky.isChecked))
                    moveTaskToBack(true) // taaki user target app khol sake
                }
            }
        }
        btn("User Guide") { AlertDialog.Builder(this).setTitle("User Guide").setMessage(GUIDE).setPositiveButton("OK", null).show() }
        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true); col.addView(this) }
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    companion object {
        const val GUIDE = """SETUP (ek baar):
1. 'Accessibility settings kholo' dabao, Installed apps/Downloaded apps me 'Agent Pilot' chuno aur ON karo.
2. Agar toggle grey hai: Phone Settings > Apps > Agent Pilot > teen dot (⋮) > 'Allow restricted settings', phir wapas ON karo.
3. API key daalo (OpenAI-compatible). Base URL/Model optional.

USE:
1. Goal likho (chhota, ek kaam ka). Jaise: "YouTube kholo aur 'lofi music' search karo".
2. Run dabao. 5 sec me wo app kholo jahan kaam karwana hai (ya goal me app ka naam likho).
3. Screen par STOP button dikhega, kabhi bhi rok sakte ho. Max 20 steps.

SAFETY:
- Password/OTP/payment agent nahi bharta.
- Send/pay/delete jaise buttons default me block hain. Checkbox se allow kar sakte ho.
- Screen ka text tumhare AI provider ko jaata hai. Bank/private chat par use mat karo.
- AI galti kar sakta hai, screen dekhte raho."""
    }
}
