package com.tvinstaller

import android.app.Activity
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Link de descarga de la app de TV
const val APK_URL = "https://github.com/mac-donal/apk/releases/download/tvplus2/tvplus2-2026.apk"
private val C_BG = Color.parseColor("#170A2B")
private val C_SURFACE = Color.parseColor("#221043")
private val C_INPUT = Color.parseColor("#2E1858")
private val C_BORDER = Color.parseColor("#47298A")
private val C_ACCENT = Color.parseColor("#9B5CFF")
private val C_ACCENT_DEEP = Color.parseColor("#7131E8")
private val C_TEXT = Color.parseColor("#F6F1FF")
private val C_MUTED = Color.parseColor("#B9A7DE")
private val C_HINT = Color.parseColor("#8C74BC")

const val DEFAULT_SEQ = "TAB,USER,ENTER,PASS,ENTER,OK"

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var email: EditText
    private lateinit var pass: EditText
    private lateinit var logView: TextView
    private lateinit var go: Button
    private lateinit var choose: LinearLayout
    private lateinit var advanced: LinearLayout
    // Ajustes ocultos (mantener apretado el titulo para verlos)
    private lateinit var ipManual: EditText
    private lateinit var pkgManual: EditText
    private lateinit var wait: EditText
    private lateinit var delay: EditText
    private lateinit var seq: EditText
    private lateinit var seq2: EditText

    private val bg = Executors.newCachedThreadPool()
    @Volatile private var busy = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        // Migracion: reemplaza la secuencia anterior que terminaba en "olvide contrasena"
        if (prefs.getString("seq", null) == "TAB,USER,ENTER,PASS,ENTER,DOWN,OK") {
            prefs.edit().remove("seq").apply()
        }
        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(20))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(C_BG)
            isFillViewport = true
            addView(root)
        })

        // ----- Cabecera -----
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnLongClickListener {
                advanced.visibility = if (advanced.visibility == View.GONE) View.VISIBLE else View.GONE
                true
            }
        }
        head.addView(TextView(this).apply {
            text = "\u25B6"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(C_ACCENT, C_ACCENT_DEEP))
                .apply { cornerRadius = dp(16).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        })
        head.addView(TextView(this).apply {
            text = "TVA Instalador"
            textSize = 28f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            letterSpacing = -0.02f
            setTextColor(C_TEXT)
            setPadding(dp(14), 0, 0, 0)
        })
        root.addView(head)
        root.addView(TextView(this).apply {
            text = "Instala la app en el televisor e inicia sesion con la cuenta del cliente."
            textSize = 15f
            setTextColor(C_MUTED)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(16), dp(8), dp(22))
        })

        // ----- Panel con los dos campos y el boton -----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(22))
            background = shape(C_SURFACE, 24, C_BORDER)
        }
        root.addView(card)

        email = field(card, "Correo", "usuario@correo.com", "user", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        pass = field(card, "Contrasena", "Tu contrasena", "pass", "", plainText())

        go = primaryButton("Instalar y entrar") { start(null) }
        card.addView(go)

        choose = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(choose)

        logView = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 13.5f
            typeface = Typeface.MONOSPACE
            setTextColor(C_MUTED)
            setLineSpacing(0f, 1.2f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = shape(Color.parseColor("#120822"), 16, C_BORDER)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        }
        root.addView(logView)

        // ----- Ajustes ocultos (mantener apretada la cabecera) -----
        advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(20), dp(8), dp(20), dp(20))
            background = shape(C_SURFACE, 24, C_BORDER)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        }
        ipManual = field(advanced, "IP del TV (vacio = buscar solo)", "", "ipManual", "", InputType.TYPE_CLASS_PHONE)
        pkgManual = field(advanced, "Paquete de la app (vacio = detectar solo)", "", "pkgManual", "", plainText())
        wait = field(advanced, "Espera tras abrir la app (seg)", "", "wait", "6", InputType.TYPE_CLASS_NUMBER)
        delay = field(advanced, "Pausa entre pasos (ms)", "", "delay", "600", InputType.TYPE_CLASS_NUMBER)
        seq = field(advanced, "Secuencia de login (USER, PASS, TAB, ENTER, OK, UP, DOWN, LEFT, RIGHT, BACK, HOME, w2, L:OK)",
            "", "seq", DEFAULT_SEQ, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        seq2 = field(advanced, "Secuencia extra al final (experimental)",
            "", "seq2", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        advanced.addView(ghostButton("Re-descargar APK la proxima vez") {
            File(cacheDir, "tv.apk").delete(); log("Se descargara de nuevo.")
        })
        root.addView(advanced)

        // ----- Pie -----
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val credit = "Hecha por DKN Estudio"
        root.addView(TextView(this).apply {
            text = SpannableString(credit).apply {
                val i = credit.indexOf("DKN")
                setSpan(StyleSpan(Typeface.BOLD), i, credit.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(C_ACCENT), i, credit.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 13f
            setTextColor(C_HINT)
            gravity = Gravity.CENTER
            setPadding(0, dp(36), 0, dp(4))
        })
    }

    // ---------- Estilos ----------
    private fun shape(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun plainText() =
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

    private fun field(parent: LinearLayout, label: String, hint: String, key: String, def: String, type: Int): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(C_MUTED)
            setPadding(dp(2), dp(16), 0, dp(7))
        })
        val e = EditText(this).apply {
            this.hint = hint
            inputType = type
            setText(prefs.getString(key, def))
            tag = key
            textSize = 16.5f
            setTextColor(C_TEXT)
            setHintTextColor(C_HINT)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = shape(C_INPUT, 14, C_BORDER)
            setOnFocusChangeListener { v, focused ->
                v.background = shape(C_INPUT, 14, if (focused) C_ACCENT else C_BORDER)
            }
        }
        parent.addView(e, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return e
    }

    private fun styledButton(text: String, bg: android.graphics.drawable.Drawable, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            stateListAnimator = null
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(22) }
        }

    private fun primaryButton(text: String, onClick: () -> Unit): Button =
        styledButton(text, GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C_ACCENT_DEEP, C_ACCENT)
        ).apply { cornerRadius = dp(16).toFloat() }, onClick)

    private fun ghostButton(text: String, onClick: () -> Unit): Button =
        styledButton(text, shape(Color.TRANSPARENT, 16, C_ACCENT), onClick).apply { textSize = 16f }

    private fun setBusyUi(on: Boolean) = runOnUiThread {
        go.isEnabled = !on
        go.text = if (on) "Trabajando..." else "Instalar y entrar"
        go.alpha = if (on) 0.65f else 1f
    }

    private fun setLog(msg: String) {
        logView.visibility = View.VISIBLE
        logView.text = msg
    }

    private fun log(msg: String) = runOnUiThread {
        logView.visibility = View.VISIBLE
        if (logView.text.isEmpty()) logView.text = msg else logView.append("\n$msg")
    }

    private fun saveAll() {
        val e = prefs.edit()
        listOf(email, pass, ipManual, pkgManual, wait, delay, seq, seq2).forEach {
            e.putString(it.tag as String, it.text.toString())
        }
        e.apply()
    }

    // ---------- Inicio: busca el TV solo y hace todo ----------
    private fun start(host: String?) {
        if (busy) return
        if (email.text.isBlank() || pass.text.isBlank()) {
            setLog("Escribi el correo y la contrasena.")
            return
        }
        busy = true
        saveAll()
        choose.removeAllViews()
        setBusyUi(true)
        setLog(if (host == null) "Buscando el TV en la red..." else "Conectando...")
        bg.execute {
            try {
                val manual = ipManual.text.toString().trim()
                val target = host ?: if (manual.isNotEmpty()) manual else null
                if (target != null) {
                    doFlow(target)
                } else {
                    val tvs = findTvs()
                    when {
                        tvs.isEmpty() -> log(
                            "No encontre ningun TV. Revisa que este conectado a tu hotspot/Wi-Fi " +
                            "y que tenga la depuracion ADB activada."
                        )
                        tvs.size == 1 -> { log("TV encontrado: ${tvs[0]}"); doFlow(tvs[0]) }
                        else -> {
                            log("Encontre ${tvs.size} TVs. Elegi cual:")
                            runOnUiThread {
                                tvs.forEach { h -> choose.addView(ghostButton("TV $h") { start(h) }) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log("Error: ${e.message ?: e.toString()}")
            }
            busy = false
            setBusyUi(false)
        }
    }

    private fun hostPort(s: String): Pair<String, Int> {
        val t = s.trim()
        val i = t.lastIndexOf(':')
        val port = if (i > 0) t.substring(i + 1).toIntOrNull() else null
        return if (port != null) t.substring(0, i) to port else t to 5555
    }

    private fun canConnect(host: String, port: Int = 5555, timeout: Int = 600): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeout); true }
    } catch (_: Exception) { false }

    private fun toIp(v: Long) = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

    // Busca TVs con ADB en la red: primero el ultimo usado, despues escanea la red local
    private fun findTvs(): List<String> {
        val last = prefs.getString("lastip", null)
        if (last != null) {
            val (h, p) = hostPort(last)
            if (canConnect(h, p, 900)) return listOf(last)
        }

        val targets = linkedSetOf<String>()
        val own = HashSet<String>()
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    if (!a.isSiteLocalAddress) continue
                    own.add(a.hostAddress!!)
                    // Redes grandes (/8, /16): se escanean hasta 1024 equipos alrededor de tu IP
                    val prefix = ia.networkPrefixLength.toInt().coerceIn(22, 24)
                    val ip = a.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                    val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                    val base = ip and mask
                    val count = 1L shl (32 - prefix)
                    for (i in 1 until count - 1) targets.add(toIp(base + i))
                }
            }
        } catch (_: Exception) {}
        targets.removeAll(own)

        val hits = ConcurrentLinkedQueue<String>()
        val pool = Executors.newFixedThreadPool(128)
        for (h in targets) pool.execute { if (canConnect(h, 5555, 600)) hits.add("$h:5555") }
        pool.shutdown()
        pool.awaitTermination(45, TimeUnit.SECONDS)
        return hits.sorted()
    }

    // ---------- Flujo principal ----------
    private fun doFlow(target: String) {
        val (host, port) = hostPort(target)
        log("Conectando a $host... (la primera vez, acepta el cartel en el TV)")
        val dadb = Dadb.create(host, port, keyPair())
        try {
            // Si el TV muestra el cartel de permiso, esperamos hasta 90 s a que lo aceptes
            val fut = bg.submit(java.util.concurrent.Callable {
                val m = dadb.shell("getprop ro.product.model").output.trim()
                val v = dadb.shell("getprop ro.build.version.release").output.trim()
                val sdk = dadb.shell("getprop ro.build.version.sdk").output.trim()
                "$m - Android $v (API $sdk)"
            })
            val info = try {
                fut.get(90, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                log("El TV no respondio. Acepta el cartel 'Permitir depuracion' en la pantalla del TV y vuelve a intentar.")
                return
            } catch (e: java.util.concurrent.ExecutionException) {
                log("No se pudo conectar: ${e.cause?.message ?: "sin respuesta"}")
                return
            }
            log("Conectado: $info")
            prefs.edit().putString("lastip", "$host:$port").apply()

            val apk = ensureApk()
            val apkPkg = packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName ?: ""
            val before = packages(dadb)
            log("Instalando la app en el TV...")
            val err = installApk(dadb, apk, apkPkg)
            if (err != null) { log("Fallo la instalacion: $err"); return }
            log("Instalada.")

            // Paquete: manual > leido del propio APK > diferencia de apps > recordado
            var p = pkgManual.text.toString().trim()
            if (p.isEmpty()) p = apkPkg
            if (p.isEmpty()) (packages(dadb) - before).singleOrNull()?.let { p = it }
            if (p.isEmpty()) p = prefs.getString("pkg", "") ?: ""
            if (p.isEmpty()) { log("No pude detectar el paquete de la app."); return }
            prefs.edit().putString("pkg", p).apply()

            log("Abriendo la app...")
            dadb.shell("input keyevent 224")   // despertar pantalla
            Thread.sleep(500)
            dadb.shell("am force-stop $p")
            Thread.sleep(1000)
            if (!launch(dadb, p)) { log("No pude abrir la app en este TV."); return }

            val u = email.text.toString()
            val pw = pass.text.toString()
            val pause = delay.text.toString().toLongOrNull() ?: 600L
            val secs = wait.text.toString().toLongOrNull() ?: 6L
            log("Esperando a que cargue el login...")
            Thread.sleep(secs * 1000)
            runSeq(dadb, seq.text.toString().ifBlank { DEFAULT_SEQ }, u, pw, pause)

            val extra = seq2.text.toString()
            if (extra.isNotBlank()) { log("Secuencia extra..."); runSeq(dadb, extra, u, pw, pause) }
            log("Listo.")
        } finally {
            try { dadb.close() } catch (_: Exception) {}
        }
    }

    // Instala con reintentos para los casos tipicos de cada version de Android
    private fun installApk(d: Dadb, apk: File, pkgName: String): String? {
        val tmp = "/data/local/tmp/tvapp.apk"
        d.push(apk, tmp)
        try { d.shell("settings put global verifier_verify_adb_installs 0") } catch (_: Exception) {}
        fun ok(o: String) = o.contains("Success")
        var out = d.shell("pm install -r $tmp").allOutput
        if (!ok(out) && (out.contains("DEPRECATED_SDK") || out.contains("low target", true))) {
            log("Android nuevo: instalando con compatibilidad para apps antiguas...")
            out = d.shell("pm install -r --bypass-low-target-sdk-block $tmp").allOutput
        }
        if (!ok(out) && pkgName.isNotEmpty() &&
            (out.contains("UPDATE_INCOMPATIBLE") || out.contains("VERSION_DOWNGRADE") || out.contains("SIGNATURE"))) {
            log("Habia otra version instalada: la reemplazo...")
            d.shell("pm uninstall $pkgName")
            out = d.shell("pm install -r $tmp").allOutput
        }
        d.shell("rm $tmp")
        if (ok(out)) return null
        return when {
            out.contains("INSUFFICIENT_STORAGE") -> "No hay espacio libre en el TV."
            out.contains("NO_MATCHING_ABIS") -> "El APK no es compatible con el procesador de este TV."
            out.contains("OLDER_SDK") -> "El Android de este TV es demasiado viejo para esa app."
            else -> out.trim()
        }
    }

    // Abre la app: Android TV / Google TV (Leanback) y, si no, el lanzador comun
    private fun launch(d: Dadb, p: String): Boolean {
        val cats = listOf("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER")
        for (c in cats) {
            if (d.shell("monkey -p $p -c $c 1").allOutput.contains("Events injected: 1")) return true
        }
        for (c in cats) {
            val comp = d.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c $c $p")
                .output.lines().map { it.trim() }.lastOrNull { it.contains("/") }
            if (comp != null) { d.shell("am start -n $comp"); return true }
        }
        return false
    }

    private fun packages(d: Dadb): Set<String> =
        d.shell("pm list packages -3").output.lines()
            .map { it.trim().removePrefix("package:") }.filter { it.isNotEmpty() }.toSet()

    private val keyNames = mapOf(
        "OK" to 23, "ENTER" to 66, "TAB" to 61, "UP" to 19, "DOWN" to 20,
        "LEFT" to 21, "RIGHT" to 22, "BACK" to 4, "HOME" to 3, "MENU" to 82, "DEL" to 67
    )

    private fun runSeq(d: Dadb, sequence: String, u: String, pw: String, pause: Long) {
        for (raw in sequence.split(",")) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val up = t.uppercase()
            val secs = if (up.startsWith("W")) up.drop(1).toDoubleOrNull() else null
            when {
                up == "USER" || up == "{USER}" -> { log("> correo"); sendText(d, u) }
                up == "PASS" || up == "{PASS}" -> { log("> contrasena"); sendText(d, pw) }
                secs != null -> { log("> esperar ${secs}s"); Thread.sleep((secs * 1000).toLong()) }
                else -> {
                    val long = up.startsWith("L:")
                    val name = if (long) up.substring(2) else up
                    val code = keyNames[name] ?: name.toIntOrNull()
                    if (code == null) { log("> no entiendo el paso: $t"); continue }
                    log("> tecla $name" + if (long) " (larga)" else "")
                    d.shell("input keyevent " + (if (long) "--longpress " else "") + code)
                }
            }
            Thread.sleep(pause)
        }
    }

    // Escribe de a pocos caracteres para que los TV lentos no pierdan letras
    private fun sendText(d: Dadb, text: String) {
        for (chunk in text.chunked(3)) {
            val quoted = "'" + chunk.replace(" ", "%s").replace("'", "'\\''") + "'"
            d.shell("input text $quoted")
            Thread.sleep(120)
        }
        Thread.sleep(200)
    }

    private fun keyPair(): AdbKeyPair {
        val priv = File(filesDir, "adbkey")
        val pub = File(filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private fun ensureApk(): File {
        val f = File(cacheDir, "tv.apk")
        if (f.exists() && f.length() > 0) { log("Usando APK guardado."); return f }
        log("Descargando APK...")
        val tmp = File(cacheDir, "tv.apk.part")
        val c = URL(APK_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        if (c.responseCode != 200) throw RuntimeException("Descarga fallo (HTTP ${c.responseCode})")
        c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        tmp.renameTo(f)
        log("APK descargado (${f.length() / 1024 / 1024} MB).")
        return f
    }
}
