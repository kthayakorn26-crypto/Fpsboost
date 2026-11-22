package com.example.fpsboost

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var pkgInput: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(t: String) = TextView(this).apply { text = t; textSize = 15f }.also { root.addView(it) }
        fun button(t: String, onClick: () -> Unit) =
            Button(this).apply { text = t; setOnClickListener { onClick() } }.also { root.addView(it) }

        label("FPS Boost").textSize = 24f
        status = label("")

        label("\nชื่อแพ็กเกจของเกม (เช่น com.tencent.ig, com.garena.game.codm)")
        pkgInput = EditText(this).apply {
            setText(prefs.getString("pkg", ""))
            hint = "com.xxx.xxx"
            setSingleLine()
        }
        root.addView(pkgInput)

        button("1) อนุญาตแสดงทับแอปอื่น") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        button("เริ่มแสดง FPS") { startOverlay(save(prefs)) }
        button("หยุดแสดง FPS") { stopService(Intent(this, OverlayService::class.java)) }
        button("ล้างแรมพื้นหลัง") { clearRam(save(prefs)) }
        button("บูสแล้วเล่นเลย (ล้างแรม + เปิด FPS + เปิดเกม)") {
            val game = save(prefs)
            clearRam(game)
            startOverlay(game)
            val launch = packageManager.getLaunchIntentForPackage(game)
            if (launch != null) startActivity(launch)
            else Toast.makeText(this, "ไม่พบเกมแพ็กเกจนี้", Toast.LENGTH_SHORT).show()
        }

        label("\nขั้นตอนครั้งแรก: ให้สิทธิ์อ่าน FPS ผ่าน ADB ด้วยคำสั่งนี้ (ทำครั้งเดียว):")
        TextView(this).apply {
            text = "adb shell pm grant $packageName android.permission.DUMP"
            setTextIsSelectable(true)
            textSize = 13f
        }.also { root.addView(it) }

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        val dump = checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED
        status.text = "แสดงทับแอปอื่น: ${if (overlay) "พร้อม" else "ยังไม่อนุญาต"}\n" +
            "สิทธิ์อ่าน FPS (DUMP): ${if (dump) "พร้อม" else "ยังไม่ได้ให้ผ่าน ADB"}"
    }

    private fun save(prefs: android.content.SharedPreferences): String {
        val p = pkgInput.text.toString().trim()
        prefs.edit().putString("pkg", p).apply()
        return p
    }

    private fun startOverlay(game: String) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "กดปุ่ม 1) อนุญาตแสดงทับแอปอื่นก่อน", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, OverlayService::class.java).putExtra("pkg", game)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun freeMb(): Long {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.availMem / 1048576
    }

    private fun clearRam(game: String) {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val before = freeMb()
        packageManager.getInstalledApplications(0).forEach { a ->
            val isSystem = (a.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!isSystem && a.packageName != packageName && a.packageName != game) {
                am.killBackgroundProcesses(a.packageName)
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            Toast.makeText(this, "แรมว่าง: $before → ${freeMb()} MB", Toast.LENGTH_LONG).show()
        }, 800)
    }
}

class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var tv: TextView? = null
    private var worker: Thread? = null
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var pkg = ""

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pkg = intent?.getStringExtra("pkg") ?: ""
        FpsReader.reset()
        startAsForeground()

        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (tv == null) addOverlay()

        if (!running) {
            running = true
            worker = Thread { loop() }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("fps", "FPS Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "fps")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("FPS Boost")
            .setContentText("กำลังแสดง FPS บนหน้าจอ")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun addOverlay() {
        val v = TextView(this).apply {
            text = "FPS --"
            textSize = 14f
            setTextColor(Color.GREEN)
            setPadding(20, 10, 20, 10)
            setBackgroundColor(0x99000000.toInt())
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 48
        }
        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm!!.addView(v, lp)
            tv = v
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun batteryTemp(): Float {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    }

    private fun loop() {
        while (running) {
            val fps = FpsReader.read(pkg)
            val t = batteryTemp()
            val text = when {
                pkg.isBlank() -> "ยังไม่ได้ใส่แพ็กเกจเกม"
                FpsReader.denied -> "ต้อง adb grant สิทธิ์ DUMP"
                fps == null -> "รอเกม... | ${t}°C"
                else -> "FPS $fps | ${t}°C"
            }
            val color = when {
                fps == null -> Color.WHITE
                fps >= 50 -> Color.GREEN
                fps >= 30 -> Color.YELLOW
                else -> Color.RED
            }
            ui.post { tv?.let { it.text = text; it.setTextColor(color) } }
            try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
        }
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        tv?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        tv = null
        super.onDestroy()
    }
}

object FpsReader {
    @Volatile var denied = false
    private var layer: String? = null

    fun reset() { layer = null; denied = false }

    private fun sh(vararg cmd: String): List<String>? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        if (lines.any { it.contains("Permission Denial") }) {
            denied = true
            null
        } else {
            denied = false
            lines
        }
    } catch (e: Exception) {
        null
    }

    private fun findLayer(pkg: String): String? {
        val all = sh("dumpsys", "SurfaceFlinger", "--list") ?: return null
        val m = all.filter { it.contains(pkg) && !it.startsWith("Background") }
        return m.firstOrNull { it.startsWith("SurfaceView") } ?: m.firstOrNull()
    }

    fun read(pkg: String): Int? {
        if (pkg.isBlank()) return null
        val l = layer ?: findLayer(pkg).also { layer = it } ?: return null
        val out = sh("dumpsys", "SurfaceFlinger", "--latency", l) ?: return null

        val ts = out.drop(1).mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size == 3) p[1].toLongOrNull() else null
        }.filter { it > 0 && it != Long.MAX_VALUE }.sorted()

        if (ts.size < 2) { layer = null; return null }

        val last = ts.last()
        val win = ts.filter { last - it <= 1_000_000_000L }
        if (win.size < 2) return 0
        val span = win.last() - win.first()
        return if (span > 0) Math.round((win.size - 1) * 1e9 / span).toInt() else 0
    }
}
