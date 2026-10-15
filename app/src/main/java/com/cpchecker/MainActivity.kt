package com.cpchecker

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Signature strings are stored reversed so the scanner never flags its own files
private fun r(s: String) = s.reversed()
private val STRONG = listOf("tsoobotalp", "rotucexeyfitnedi", "emanrotucexeteg").map { r(it) }
private val EXEC_API = listOf("vnegteg", "noitcnufkooh", "dohtematemkooh", "erusolccwen", "rellackcehc", "elbatatemwarteg", "ylnodaertes", "tseretnihcuoterif", "rotcetedkcilcerif", "tpmorpytimixorperif", "snoitcennocteg", "langiserif", "iuhteg", "draobpilctes", "tseuqer.nys", "secnatsnilinteg", "edocetybtpircsteg", "(elipmoced", "ecnatsnievas").map { r(it) }
private val LOADERS = listOf("tegptth:emag(gnirtsdaol", "tegptth.emag(gnirtsdaol", "tth(gnirtsdaol", "(tegptth:emag", "(cnysategptth:emag").map { r(it) }

private val EXEC_FOLDERS = setOf(
    "delta", "arceus x", "arceusx", "arceus", "fluxus", "hydrogen", "vegax", "vega x",
    "krnl", "codex", "cryptic", "ronix", "trigon", "autoexec", "evon", "roexec", "xeno"
)
private val PKG_KEYS = listOf(
    "arceus", "fluxus", "vegax", "hydrogen", "krnl", "deltaexec", "delta.exec", "delta executor",
    "codex", "roexec", "cryptic", "ronix", "trigon", "evonexec", "robloxexec", "executor", "exploit"
)
// Executor names. SPECIFIC = unique enough to match anywhere in a name.
// GENERIC = common words, so only matched when the name IS the word (wave.exe, "Wave 2.0")
// and not when it is just part of another word (e.g. "realme", "waves").
private val NAMES_SPECIFIC = listOf(
    "solara", "synapse", "krnl", "fluxus", "jjsploit", "arceus", "macrothird", "vexor"
)
private val NAMES_GENERIC = listOf("xeno", "swift", "wave", "electron", "celery", "velocity", "real")
private val FILE_EXTS = listOf(".exe", ".dll", ".msi", ".zip", ".rar", ".7z", ".apk", ".lua", ".luau")

private fun nameHit(raw: String): String? {
    val n = raw.lowercase(Locale.ROOT)
    val dot = n.lastIndexOf('.')
    val stem = if (dot > 0) n.substring(0, dot) else n
    NAMES_SPECIFIC.firstOrNull { stem.contains(it) }?.let { return it }
    for (g in NAMES_GENERIC) {
        if (stem == g) return g
        if (stem.startsWith(g) && !stem[g.length].isLetter()) return g
    }
    return null
}

private const val MAX_TEXT = 6L * 1024 * 1024
private const val MAX_HASH = 400L * 1024 * 1024
private const val READ = 400 * 1024

// Theme
private val BG = Color.parseColor("#0A0E14")
private val CARD = Color.parseColor("#121821")
private val STROKE = Color.parseColor("#1F2B3A")
private val INPUT = Color.parseColor("#0D131B")
private val GREEN = Color.parseColor("#00E676")
private val RED = Color.parseColor("#FF1744")
private val AMBER = Color.parseColor("#FFC400")
private val GRAY = Color.parseColor("#7D8A99")

data class Finding(val level: String, val why: String, val path: String)

class MainActivity : Activity() {
    private lateinit var pw: EditText
    private lateinit var startBtn: Button
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var bannerBox: LinearLayout
    private lateinit var bannerTitle: TextView
    private lateinit var bannerSub: TextView
    private lateinit var list: LinearLayout
    private var attempts = 0
    private var pendingStart = false
    private var running = false
    @Volatile private var scanned = 0
    private var hashes: Map<String, String> = emptyMap()

    // ---------------- UI helpers ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(fill: Int, stroke: Int = 0, radius: Int = 14) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun mono(bold: Boolean = false) =
        Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = box(CARD, STROKE)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(GREEN)
        typeface = mono(true); letterSpacing = 0.15f
    }

    private fun findingCard(f: Finding) = card().apply {
        val c = when (f.level) { "STRONG" -> RED; "SUSPICIOUS" -> AMBER; else -> GRAY }
        background = box(CARD, c)
        addView(TextView(context).apply {
            text = f.level; setTextColor(c); textSize = 12f; typeface = mono(true)
        })
        addView(TextView(context).apply {
            text = f.why; setTextColor(Color.WHITE); textSize = 14f
            setPadding(0, dp(4), 0, dp(4))
        })
        addView(TextView(context).apply {
            text = f.path; setTextColor(GRAY); textSize = 11f; typeface = mono(); setTextIsSelectable(true)
        })
    }

    // ---------------- Screen ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(16))
        }

        // Title
        val full = "CP CHECKER TOOLS -JHER"
        val sp = SpannableString(full)
        sp.setSpan(
            ForegroundColorSpan(GREEN), full.indexOf("-JHER"), full.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        root.addView(TextView(this).apply {
            text = sp; textSize = 24f; setTextColor(Color.WHITE)
            typeface = mono(true); gravity = Gravity.CENTER; letterSpacing = 0.05f
        })
        root.addView(TextView(this).apply {
            text = "ROBLOX MOBILE CHEAT SCANNER"; textSize = 11f; setTextColor(GRAY)
            typeface = mono(); gravity = Gravity.CENTER; letterSpacing = 0.2f
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(View(this).apply { setBackgroundColor(GREEN) },
            LinearLayout.LayoutParams(dp(60), dp(3)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16); bottomMargin = dp(24)
            })

        // Access card
        val access = card()
        access.addView(label("ACCESS KEY"))
        pw = EditText(this).apply {
            hint = "Enter password"; setHintTextColor(GRAY); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(); typeface = mono()
            background = box(INPUT, STROKE, 10)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        access.addView(pw, lp(10))
        startBtn = Button(this).apply {
            text = "START SCAN"; textSize = 16f; setTextColor(Color.BLACK)
            typeface = mono(true); background = box(GREEN, radius = 12)
            stateListAnimator = null
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { onStartClicked() }
        }
        access.addView(startBtn, lp(14))
        root.addView(access, lp())

        // Status
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(GREEN)
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
        ).apply { topMargin = dp(18) })
        status = TextView(this).apply {
            setTextColor(GRAY); textSize = 13f; typeface = mono(); gravity = Gravity.CENTER
        }
        root.addView(status, lp(10))

        // Result banner
        bannerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(28), dp(16), dp(28))
            visibility = View.GONE
        }
        bannerTitle = TextView(this).apply {
            textSize = 30f; typeface = mono(true); gravity = Gravity.CENTER
        }
        bannerSub = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        }
        bannerBox.addView(bannerTitle)
        bannerBox.addView(bannerSub)
        root.addView(bannerBox, lp(14))

        // Findings
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, lp())

        // Push footer to the bottom
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "CP CHECKER BY JHER"; textSize = 12f; setTextColor(GRAY)
            typeface = mono(true); gravity = Gravity.CENTER; letterSpacing = 0.2f
            setPadding(0, dp(28), 0, dp(8))
        }, lp())

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(root)
        }

        // Loading screen shown first, then fades out to reveal the password screen
        val splash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(BG)
            isClickable = true
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        splash.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        splash.addView(TextView(this).apply {
            text = sp; textSize = 24f; setTextColor(Color.WHITE)
            typeface = mono(true); gravity = Gravity.CENTER; letterSpacing = 0.05f
        })
        splash.addView(ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(GREEN)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            topMargin = dp(32); gravity = Gravity.CENTER_HORIZONTAL
        })
        splash.addView(TextView(this).apply {
            text = "LOADING..."; textSize = 12f; setTextColor(GRAY)
            typeface = mono(true); gravity = Gravity.CENTER; letterSpacing = 0.3f
            setPadding(0, dp(14), 0, 0)
        })
        splash.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        splash.addView(TextView(this).apply {
            text = "CP CHECKER BY JHER"; textSize = 12f; setTextColor(GRAY)
            typeface = mono(true); gravity = Gravity.CENTER; letterSpacing = 0.2f
            setPadding(0, 0, 0, dp(8))
        })

        val frame = android.widget.FrameLayout(this)
        val match = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        frame.addView(scroll, android.view.ViewGroup.LayoutParams(match, match))
        frame.addView(splash, android.view.ViewGroup.LayoutParams(match, match))
        setContentView(frame)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            splash.animate().alpha(0f).setDuration(400)
                .withEndAction { splash.visibility = View.GONE }.start()
        }, 2200)

        if (BuildConfig.WEBHOOK_URL.isNotBlank()) {
            status.text = "Notice: the result will be sent to whoever is checking you."
        }
    }

    // ---------------- Flow ----------------

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun lockButton(locked: Boolean) {
        startBtn.isEnabled = !locked
        startBtn.alpha = if (locked) 0.4f else 1f
    }

    private fun onStartClicked() {
        if (running) return
        if (sha256Hex(pw.text.toString().trim()) != BuildConfig.PASSWORD_HASH.lowercase()) {
            attempts++
            pw.text.clear()
            if (attempts >= 3) {
                lockButton(true); pw.isEnabled = false
                status.setTextColor(RED)
                status.text = "Too many wrong attempts. Scan locked."
            } else {
                status.setTextColor(RED)
                status.text = "Wrong password. ${3 - attempts} attempt(s) left."
            }
            return
        }
        pw.isEnabled = false
        lockButton(true)
        status.setTextColor(GRAY)
        if (hasStorage()) beginScan() else askStorage()
    }

    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED

    private fun askStorage() {
        pendingStart = true
        status.text = "Allow storage access for this app, then come back."
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingStart && hasStorage()) { pendingStart = false; beginScan() }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            pendingStart = false; beginScan()
        } else {
            status.setTextColor(RED)
            status.text = "Storage permission is required. Restart the app and allow it."
        }
    }

    private fun beginScan() {
        running = true; scanned = 0
        progress.visibility = View.VISIBLE
        status.text = "Scanning..."
        Thread {
            val found = try { scan() } catch (e: Exception) {
                listOf(Finding("ERROR", "Scan error: ${e.message}", ""))
            }
            val positive = found.any { it.level != "ERROR" }
            val verdict = if (positive) "POSITIVE" else "NO CHEATS FOUND"
            runOnUiThread { showResult(found) }
            val sent = sendReport(verdict, found)
            runOnUiThread {
                if (sent == true) status.text = "Result sent to the checker."
                else if (sent == false) status.text = "Could not send the result. Please screenshot this screen."
            }
        }.start()
    }

    private fun showResult(found: List<Finding>) {
        running = false
        progress.visibility = View.GONE
        status.setTextColor(GRAY)
        status.text = "Scan complete. $scanned files checked."

        val real = found.filter { it.level != "ERROR" }
        val err = found.filter { it.level == "ERROR" }
        val color: Int; val title: String; val sub: String
        when {
            real.isNotEmpty() -> { color = RED; title = "POSITIVE"; sub = "Cheat or executor detected" }
            err.isNotEmpty() -> { color = AMBER; title = "SCAN ERROR"; sub = "The scan could not finish" }
            else -> { color = GREEN; title = "NO CHEATS FOUND"; sub = "Nothing suspicious on this device" }
        }
        val fg = if (color == RED) Color.WHITE else Color.BLACK
        bannerBox.background = box(color, radius = 18)
        bannerTitle.text = title; bannerTitle.setTextColor(fg)
        bannerSub.text = sub; bannerSub.setTextColor(fg)
        bannerBox.visibility = View.VISIBLE

        list.removeAllViews()
        if (found.isNotEmpty()) {
            list.addView(label("FINDINGS (${found.size})"), lp(22))
            for (f in found) list.addView(findingCard(f), lp(10))
        }
    }

    // ---------------- Scanner ----------------

    private fun scan(): List<Finding> {
        val out = mutableListOf<Finding>()
        hashes = loadHashes()
        scanPackages(out)
        val roots = mutableListOf(Environment.getExternalStorageDirectory())
        File("/storage").listFiles()?.forEach {
            if (it.isDirectory && it.name != "emulated" && it.name != "self") roots.add(it)
        }
        val stack = java.util.ArrayDeque<File>()
        roots.forEach { stack.push(it) }
        while (stack.isNotEmpty()) {
            val dir = stack.pop()
            val list = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
            for (c in list) {
                if (c.isDirectory) {
                    val p = c.path
                    if (p.endsWith("/Android/data") || p.endsWith("/Android/obb")) continue
                    val folderHit = nameHit(c.name)
                    if (c.name.lowercase(Locale.ROOT) in EXEC_FOLDERS)
                        out.add(Finding("SUSPICIOUS", "Executor folder: ${c.name}", p))
                    else if (folderHit != null)
                        out.add(Finding("SUSPICIOUS", "Folder name matches: $folderHit", p))
                    val sameAsReal = try { c.canonicalPath == c.absolutePath } catch (e: Exception) { false }
                    if (sameAsReal) stack.push(c)
                } else {
                    checkFile(c, out)
                    if (scanned % 100 == 0) {
                        val n = scanned
                        runOnUiThread { status.text = "Scanning... $n files" }
                    }
                }
            }
        }
        // Show the SHA-256 of every flagged file so it can be copied into assets/hashes.txt
        return out.map { f ->
            val file = File(f.path)
            if (f.level != "ERROR" && file.isFile && file.length() <= MAX_HASH)
                sha256File(file)?.let { h -> f.copy(path = f.path + "\nSHA-256: " + h) } ?: f
            else f
        }
    }

    private fun loadHashes(): Map<String, String> {
        val m = HashMap<String, String>()
        try {
            assets.open("hashes.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("#")) continue
                    val parts = t.split(Regex("\\s+"), 2)
                    if (parts[0].length == 64) m[parts[0].lowercase(Locale.ROOT)] =
                        if (parts.size > 1) parts[1] else "known cheat"
                }
            }
        } catch (e: Exception) { /* no list */ }
        return m
    }

    private fun sha256File(f: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val b = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(b)
                if (n < 0) break
                md.update(b, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }

    // Hash match works on the file CONTENT, so renamed files are still caught.
    // Only archive/executable-type files (APK/ZIP, EXE, RAR, 7z, ELF) are hashed.
    private fun hashMatch(f: File, size: Long, out: MutableList<Finding>): Boolean {
        if (hashes.isEmpty() || size < 1024 || size > MAX_HASH) return false
        val h4 = ByteArray(4)
        try { f.inputStream().use { if (it.read(h4) < 4) return false } } catch (e: Exception) { return false }
        val b0 = h4[0].toInt() and 0xFF; val b1 = h4[1].toInt() and 0xFF
        val b2 = h4[2].toInt() and 0xFF; val b3 = h4[3].toInt() and 0xFF
        val magic = (b0 == 0x50 && b1 == 0x4B) || (b0 == 0x4D && b1 == 0x5A) ||
                (b0 == 0x52 && b1 == 0x61 && b2 == 0x72 && b3 == 0x21) ||
                (b0 == 0x37 && b1 == 0x7A && b2 == 0xBC && b3 == 0xAF) ||
                (b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46)
        if (!magic) return false
        val h = sha256File(f) ?: return false
        val name = hashes[h] ?: return false
        out.add(Finding("STRONG", "Known cheat file: $name (hash match, even if renamed)",
            f.path + "\nSHA-256: " + h))
        return true
    }

    private fun scanPackages(out: MutableList<Finding>) {
        try {
            val pm = packageManager
            for (p in pm.getInstalledPackages(0)) {
                if (p.packageName == packageName) continue
                val ai = p.applicationInfo
                val label = try { ai?.loadLabel(pm)?.toString() ?: "" } catch (e: Exception) { "" }
                val hay = (p.packageName + " " + label).lowercase(Locale.ROOT)
                val lbl = label.lowercase(Locale.ROOT)
                val pkg = p.packageName.lowercase(Locale.ROOT)
                val isSystem = ((ai?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val apk = ai?.sourceDir?.let { File(it) }
                val nameFlag = PKG_KEYS.any { hay.contains(it) } ||
                        NAMES_SPECIFIC.any { hay.contains(it) } ||
                        NAMES_GENERIC.any { lbl == it || pkg.endsWith(".$it") }
                var h: String? = null
                if (apk != null && !isSystem && apk.length() <= MAX_HASH && (nameFlag || hashes.isNotEmpty()))
                    h = sha256File(apk)
                val suffix = if (h != null) "\nSHA-256: $h" else ""
                val known = h?.let { hashes[it] }
                if (known != null)
                    out.add(Finding("STRONG", "Known cheat app installed: $known ($label)", p.packageName + suffix))
                else if (nameFlag)
                    out.add(Finding("STRONG", "Executor app installed ($label)", p.packageName + suffix))
            }
        } catch (e: Exception) { /* ignore */ }
    }

    // Reads the file CONTENT, not the name, so renamed or hidden files are still caught.
    private fun checkFile(f: File, out: MutableList<Finding>) {
        val low = f.name.lowercase(Locale.ROOT)
        if (low.endsWith(".apk") && PKG_KEYS.any { low.contains(it) })
            out.add(Finding("STRONG", "Executor APK file", f.path))
        else if (FILE_EXTS.any { low.endsWith(it) }) {
            nameHit(f.name)?.let { k ->
                out.add(Finding(if (k in NAMES_SPECIFIC) "STRONG" else "SUSPICIOUS",
                    "File name matches: $k", f.path))
            }
        }

        val size = f.length()
        if (hashMatch(f, size, out)) return
        if (size < 20 || size > MAX_TEXT) return
        val want = minOf(size, READ.toLong()).toInt()
        val buf = ByteArray(want)
        var n = 0
        try {
            f.inputStream().use { ins ->
                while (n < want) {
                    val k = ins.read(buf, n, want - n)
                    if (k < 0) break
                    n += k
                }
            }
        } catch (e: Exception) { return }
        if (n < 20) return

        val esc = 0x1b.toByte()
        val bytecode = buf[0] == esc && buf[1] == 'L'.code.toByte() &&
                ((buf[2] == 'u'.code.toByte() && buf[3] == 'a'.code.toByte()) || buf[2] == 'J'.code.toByte())
        if (bytecode) { out.add(Finding("STRONG", "Compiled Lua bytecode", f.path)); return }

        for (i in 0 until minOf(n, 1024)) if (buf[i].toInt() == 0) return // binary file: skip
        scanned++

        val data = String(buf, 0, n, Charsets.ISO_8859_1).lowercase(Locale.ROOT)
        STRONG.firstOrNull { data.contains(it) }?.let {
            out.add(Finding("STRONG", "Found inside file: '$it'", f.path)); return
        }
        val hits = EXEC_API.filter { data.contains(it) }
        if (hits.size >= 2) {
            out.add(Finding("STRONG", "Executor functions: " + hits.take(4).joinToString(", "), f.path)); return
        }
        if (data.contains("loadstring") && LOADERS.any { data.contains(it) })
            out.add(Finding("STRONG", "Script loader (loadstring + HttpGet)", f.path))
    }

    // Sends ONLY the verdict, device name and flagged files. Returns null if no webhook is set.
    private fun sendReport(verdict: String, found: List<Finding>): Boolean? {
        val url = BuildConfig.WEBHOOK_URL
        if (url.isBlank()) return null
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val lines = mutableListOf(
            "**CP CHECK: $verdict**", "Device: ${Build.MANUFACTURER} ${Build.MODEL}",
            "Time: $time", "Files scanned: $scanned"
        )
        found.take(15).forEach { lines.add("[${it.level}] ${it.why} - ${it.path}") }
        val body = JSONObject().put("content", lines.joinToString("\n").take(1900)).toString()
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.connectTimeout = 15000; conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "cp-checker")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect(); ok
        } catch (e: Exception) { false }
    }
}
