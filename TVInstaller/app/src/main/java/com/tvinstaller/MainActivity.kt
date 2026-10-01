package com.tvinstaller

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
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
const val DEFAULT_SEQ = "TAB,USER,ENTER,PASS,ENTER,OK"

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var email: EditText
    private lateinit var pass: EditText
    private lateinit var logView: TextView
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply {
            text = "TVA Instalador"
            textSize = 26f
            setOnLongClickListener {
                advanced.visibility = if (advanced.visibility == View.GONE) View.VISIBLE else View.GONE
                true
            }
        })

        email = field(root, "Correo", "usuario@correo.com", "user", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        pass = field(root, "Contrasena", "", "pass", "", plainText())

        root.addView(button("Instalar y entrar", Color.parseColor("#0A6CFF")) { start(null) })

        choose = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(choose)

        logView = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 14f
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(logView)

        // ----- Ajustes ocultos -----
        advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        ipManual = field(advanced, "IP del TV (vacio = buscar solo)", "", "ipManual", "", InputType.TYPE_CLASS_PHONE)
        pkgManual = field(advanced, "Paquete de la app (vacio = detectar solo)", "", "pkgManual", "", plainText())
        wait = field(advanced, "Espera tras abrir la app (seg)", "", "wait", "6", InputType.TYPE_CLASS_NUMBER)
        delay = field(advanced, "Pausa entre pasos (ms)", "", "delay", "600", InputType.TYPE_CLASS_NUMBER)
        seq = field(advanced, "Secuencia de login (USER, PASS, TAB, ENTER, OK, UP, DOWN, LEFT, RIGHT, BACK, HOME, w2, L:OK)",
            "", "seq", DEFAULT_SEQ, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        seq2 = field(advanced, "Secuencia extra al final (experimental)",
            "", "seq2", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        advanced.addView(button("Re-descargar APK la proxima vez") {
            File(cacheDir, "tv.apk").delete(); log("Se descargara de nuevo.")
        })
        root.addView(advanced)
    }

    private fun plainText() =
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

    private fun field(parent: LinearLayout, label: String, hint: String, key: String, def: String, type: Int): EditText {
        parent.addView(TextView(this).apply { text = label; textSize = 13f; setPadding(0, dp(14), 0, 0) })
        val e = EditText(this).apply {
            this.hint = hint
            inputType = type
            setText(prefs.getString(key, def))
            tag = key
        }
        parent.addView(e)
        return e
    }

    private fun button(text: String, color: Int? = null, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 17f
            if (color != null) { setBackgroundColor(color); setTextColor(Color.WHITE) }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }

    private fun log(msg: String) = runOnUiThread { logView.append("\n$msg") }

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
            logView.text = "Escribi el correo y la contrasena."
            return
        }
        busy = true
        saveAll()
        choose.removeAllViews()
        logView.text = if (host == null) "Buscando el TV en la red..." else "Conectando..."
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
                                tvs.forEach { h -> choose.addView(button("TV $h") { start(h) }) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log("Error: ${e.message ?: e.toString()}")
            }
            busy = false
        }
    }

    private fun canConnect(host: String, timeout: Int = 500): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, 5555), timeout); true }
    } catch (_: Exception) { false }

    private fun findTvs(): List<String> {
        // Primero prueba el ultimo TV usado
        val last = prefs.getString("lastip", null)
        if (last != null && canConnect(last, 800)) return listOf(last)

        val hits = ConcurrentLinkedQueue<String>()
        val subnets = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress!!.substringBeforeLast('.') }
                .distinct()
        } catch (e: Exception) { emptyList() }

        val pool = Executors.newFixedThreadPool(64)
        for (s in subnets) for (i in 1..254) pool.execute {
            val h = "$s.$i"
            if (canConnect(h)) hits.add(h)
        }
        pool.shutdown()
        pool.awaitTermination(40, TimeUnit.SECONDS)
        return hits.sorted()
    }

    // ---------- Flujo principal ----------
    private fun doFlow(host: String) {
        log("Conectando a $host... (la primera vez, acepta el cartel en el TV)")
        val dadb = Dadb.create(host, 5555, keyPair())
        try {
            log("Conectado: " + dadb.shell("getprop ro.product.model").output.trim())
            prefs.edit().putString("lastip", host).apply()

            val apk = ensureApk()
            val before = packages(dadb)
            log("Instalando la app en el TV...")
            dadb.push(apk, "/data/local/tmp/tvapp.apk")
            val r = dadb.shell("pm install -r /data/local/tmp/tvapp.apk")
            dadb.shell("rm /data/local/tmp/tvapp.apk")
            if (!r.allOutput.contains("Success")) { log("Fallo la instalacion: ${r.allOutput.trim()}"); return }
            log("Instalada.")

            // Paquete: manual > leido del propio APK > diferencia de apps > recordado
            var p = pkgManual.text.toString().trim()
            if (p.isEmpty()) p = packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName ?: ""
            if (p.isEmpty()) (packages(dadb) - before).singleOrNull()?.let { p = it }
            if (p.isEmpty()) p = prefs.getString("pkg", "") ?: ""
            if (p.isEmpty()) { log("No pude detectar el paquete de la app."); return }
            prefs.edit().putString("pkg", p).apply()

            log("Abriendo la app...")
            dadb.shell("am force-stop $p")
            Thread.sleep(1000)
            var out = dadb.shell("monkey -p $p -c android.intent.category.LEANBACK_LAUNCHER 1").allOutput
            if (out.contains("No activities found") || out.contains("aborted")) {
                dadb.shell("monkey -p $p -c android.intent.category.LAUNCHER 1")
            }

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

    private fun sendText(d: Dadb, text: String) {
        val quoted = "'" + text.replace(" ", "%s").replace("'", "'\\''") + "'"
        d.shell("input text $quoted")
        Thread.sleep(300)
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
