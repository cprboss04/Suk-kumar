package com.agentpilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Activity aur Service ke beech shared state (dono same process me hain). */
object Bus {
    val logs: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile var running = false
    @Volatile var stop = false
    @Volatile var service: AgentService? = null
    fun log(s: String) {
        logs.add(s)
        while (logs.size > 80) logs.removeAt(0)
    }
}

data class Cfg(val key: String, val base: String, val model: String, val allowRisky: Boolean)

class AgentService : AccessibilityService() {

    companion object {
        const val MAX_STEPS = 20 // safety limit
        // Inn words wale buttons ko default me block karte hain
        val RISKY = Regex("delete|remove|pay|buy|purchase|order|send|submit|post|publish|upload|confirm|logout|sign out", RegexOption.IGNORE_CASE)
        const val SYSTEM = """Tum ek Android screen automation agent ho. User ka goal poora karne ke liye har baar SIRF ek action do.
Reply sirf ek JSON object ho, koi extra text nahi. Allowed actions:
{"thought":"short reason","action":"click","id":<element id>}
{"thought":"..","action":"type","id":<id>,"text":"..","submit":false}
{"thought":"..","action":"scroll","direction":"down|up"}
{"thought":"..","action":"back"}
{"thought":"..","action":"home"}
{"thought":"..","action":"open_app","package":"com.example.app"}
{"thought":"..","action":"wait","ms":2000}
{"thought":"..","action":"done","summary":"kya hua"}
Rules: sirf SCREEN me diye gaye ids use karo. Password/OTP/payment kabhi khud mat bharo - "done" karke user ko batao.
Message bhejna, paisa dena, delete karna tabhi karo jab goal me saaf likha ho. Goal poora ho ya aage badhna impossible ho to "done" do."""
    }

    private val main = Handler(Looper.getMainLooper())
    private var stopBtn: Button? = null

    override fun onServiceConnected() {
        Bus.service = this
        Bus.log("Service connected")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        Bus.service = null
        Bus.stop = true
        return super.onUnbind(intent)
    }

    /** MainActivity yahan se agent start karti hai. */
    fun start(goal: String, cfg: Cfg) {
        if (Bus.running) return
        Bus.running = true
        Bus.stop = false
        Bus.logs.clear()
        showStop()
        Thread {
            try { loop(goal, cfg) }
            catch (e: Throwable) { Bus.log("Error: ${e.message}") }
            finally { Bus.running = false; hideStop() }
        }.start()
    }

    // ---------- Main agent loop: screen padho -> LLM -> action ----------
    private fun loop(goal: String, cfg: Cfg) {
        Bus.log("Start: $goal\n5 sec me target app kholo...")
        sleepStop(5000)
        val history = ArrayList<String>()
        for (step in 1..MAX_STEPS) {
            if (Bus.stop) { Bus.log("Stop dabaya, ruk gaya."); return }
            val nodes = HashMap<Int, AccessibilityNodeInfo>()
            val screen = snapshot(nodes)
            if (screen == null) { sleepStop(1500); continue }
            val user = "GOAL: $goal\n\nSCREEN:\n$screen\n\nPREVIOUS ACTIONS:\n" +
                (history.takeLast(8).joinToString("\n").ifEmpty { "none" }) + "\n\nAgla action (JSON only)."
            val a = parseJson(callLlm(cfg, user))
            val act = a.optString("action")
            Bus.log("#$step $act ${a.optString("thought")}")
            if (act == "done") { Bus.log("DONE: ${a.optString("summary")}"); return }
            history.add(a.toString())
            val err = perform(a, nodes, cfg)
            if (err != null) { history.add("ERROR: $err"); Bus.log("  ! $err") }
            sleepStop(1500)
        }
        Bus.log("Max steps ($MAX_STEPS) poore - ruk gaya.")
    }

    // ---------- Screen ko numbered elements ki list me badlo ----------
    private fun snapshot(map: HashMap<Int, AccessibilityNodeInfo>): String? {
        val root = rootInActiveWindow ?: run { Bus.log("Screen nahi padh paya"); return null }
        if (root.packageName == packageName) { Bus.log("Abhi Agent Pilot hi screen par hai - target app kholo"); return null }
        val arr = JSONArray()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (arr.length() >= 100 || depth > 40) return
            if (n.isVisibleToUser) {
                val text = (n.text ?: n.contentDescription ?: n.hintText ?: "").toString()
                    .replace(Regex("\\s+"), " ").take(80)
                val interactive = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
                if (text.isNotEmpty() || interactive) {
                    val id = map.size
                    map[id] = n
                    arr.put(JSONObject().apply {
                        put("id", id)
                        put("cls", n.className?.toString()?.substringAfterLast('.') ?: "")
                        // password ka text LLM ko kabhi nahi bhejte
                        put("text", if (n.isPassword) "[password field]" else text)
                        if (n.isClickable) put("clickable", true)
                        if (n.isEditable) put("editable", true)
                        if (n.isScrollable) put("scrollable", true)
                        if (n.isChecked) put("checked", true)
                    })
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return JSONObject().put("app", root.packageName.toString()).put("elements", arr).toString()
    }

    // ---------- Action execute karo. null = success, String = error ----------
    private fun perform(a: JSONObject, map: Map<Int, AccessibilityNodeInfo>, cfg: Cfg): String? {
        val id = a.optInt("id", -1)
        return when (a.optString("action")) {
            "click" -> {
                val n = map[id] ?: return "id nahi mila"
                val label = (n.text ?: n.contentDescription ?: "").toString()
                if (RISKY.containsMatchIn(label) && !cfg.allowRisky)
                    return "Risky button '$label' block hua (app me 'Risky allow' off hai)"
                if (clickNode(n)) null else "click fail"
            }
            "type" -> {
                val n = map[id] ?: return "id nahi mila"
                if (n.isPassword) return "Password field me agent nahi likhta"
                n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, a.optString("text"))
                }
                if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "set text fail"
                if (a.optBoolean("submit")) {
                    n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
                null
            }
            "scroll" -> {
                val n = map.values.firstOrNull { it.isScrollable } ?: return "scrollable element nahi mila"
                val act = if (a.optString("direction") == "up")
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                if (n.performAction(act)) null else "scroll fail"
            }
            "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); null }
            "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); null }
            "open_app" -> {
                val pkg = a.optString("package")
                val i = packageManager.getLaunchIntentForPackage(pkg) ?: return "app nahi mila: $pkg"
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                null
            }
            "wait" -> { sleepStop(a.optLong("ms", 1500).coerceIn(300, 10000)); null }
            else -> "unknown action"
        }
    }

    // Pehle clickable parent par ACTION_CLICK, fail ho to center par gesture tap
    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            depth++
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    // ---------- LLM call (OpenAI-compatible /chat/completions) ----------
    private fun callLlm(cfg: Cfg, user: String): String {
        val body = JSONObject()
            .put("model", cfg.model)
            .put("temperature", 0)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM))
                .put(JSONObject().put("role", "user").put("content", user)))
        val c = URL(cfg.base.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer ${cfg.key}")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("LLM error $code: ${text.take(200)}")
        return JSONObject(text).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }

    private fun parseJson(raw: String): JSONObject {
        val m = Regex("\\{[\\s\\S]*\\}").find(raw) ?: throw RuntimeException("LLM ne JSON nahi diya: ${raw.take(100)}")
        return JSONObject(m.value)
    }

    // Sleep jo Stop dabane par turant toot jaye
    private fun sleepStop(ms: Long) {
        var left = ms
        while (left > 0 && !Bus.stop) { Thread.sleep(minOf(200, left)); left -= 200 }
    }

    // ---------- Floating STOP button (kisi bhi app ke upar) ----------
    private fun showStop() = main.post {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = Button(this).apply { text = "STOP"; setOnClickListener { Bus.stop = true } }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 250 }
        wm.addView(b, lp)
        stopBtn = b
    }
    private fun hideStop() = main.post {
        stopBtn?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        stopBtn = null
    }
}
