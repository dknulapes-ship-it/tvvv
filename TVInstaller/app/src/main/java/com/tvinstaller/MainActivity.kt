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

// Link de descarga de tu app de TV
const val APK_URL = "https://github.com/mac-donal/apk/releases/download/tvplus2/tvplus2-2026.apk"

const val DEFAULT_SEQ = "TAB,USER,ENTER,PASS,ENTER,DOWN,OK"

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var ip: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var pkg: EditText
    private lateinit var wait: EditText
    private lateinit var delay: EditText
    private lateinit var seq: EditText
    private lateinit var seq2: EditText
    private lateinit var logView: TextView
    private lateinit var found: LinearLayout
    private lateinit var settings: LinearLayout

    private val bg = Executors.newCachedThreadPool()
    @Volatile private var busy = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply { text = "TV Installer"; textSize = 24f })

        // IP + buscar
        ip = field(root, "IP del TV", "192.168.43.xx", "ip", "", InputType.TYPE_CLASS_PHONE)
        root.addView(button("Buscar TVs en la red") { scan() })
        found = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(found)

        // Cuenta
        user = field(root, "Usuario", "", "user", "", plainText())
        pass = field(root, "Clave", "", "pass", "", plainText())

        root.addView(button("Instalar y entrar", Color.parseColor("#0A6CFF")) { flow(true) })
        root.addView(button("Solo cargar cuenta (app ya instalada)") { flow(false) })

        // Ajustes
        settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        pkg = field(settings, "Paquete de la app (se detecta solo)", "", "pkg", "", plainText())
        wait = field(settings, "Espera tras abrir la app (seg)", "", "wait", "6", InputType.TYPE_CLASS_NUMBER)
        delay = field(settings, "Pausa entre pasos (milisegundos)", "", "delay", "600", InputType.TYPE_CLASS_NUMBER)
        seq = field(settings, "Secuencia de login. Pasos separados por coma: USER, PASS, TAB, ENTER, OK, UP, DOWN, LEFT, RIGHT, BACK, HOME, w2 (esperar 2s), L:OK (OK largo), o un numero de tecla",
            "", "seq", DEFAULT_SEQ, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        seq2 = field(settings, "Secuencia extra al final (experimental, ej. para acomodar la app en el inicio)",
            "", "seq2", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        settings.addView(button("Re-descargar APK la proxima vez") {
            File(cacheDir, "tv.apk").delete(); log("Se descargara de nuevo en la proxima instalacion.")
        })
        root.addView(button("Ajustes") {
            settings.visibility = if (settings.visibility == View.GONE) View.VISIBLE else View.GONE
        })
        root.addView(settings)

        logView = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 14f
            setPadding(0, dp(16), 0, 0)
            text = "Listo."
        }
        root.addView(logView)
    }

    private fun plainText() =
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

    private fun field(parent: LinearLayout, label: String, hint: String, key: String, def: String, type: Int): EditText {
        parent.addView(TextView(this).apply { text = label; textSize = 13f; setPadding(0, dp(12), 0, 0) })
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
            textSize = 16f
            if (color != null) { setBackgroundColor(color); setTextColor(Color.WHITE) }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

    private fun log(msg: String) = runOnUiThread { logView.append("\n$msg") }

    private fun saveAll() {
        val e = prefs.edit()
        listOf(ip, user, pass, pkg, wait, delay, seq, seq2).forEach {
            e.putString(it.tag as String, it.text.toString())
        }
        e.apply()
    }

    // ---------- Buscar TVs ----------
    private fun scan() {
        found.removeAllViews()
        found.addView(TextView(this).apply { text = "Buscando..." })
        bg.execute {
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
                val host = "$s.$i"
                try {
                    Socket().use { it.connect(InetSocketAddress(host, 5555), 500); hits.add(host) }
                } catch (_: Exception) {}
            }
            pool.shutdown()
            pool.awaitTermination(40, TimeUnit.SECONDS)

            runOnUiThread {
                found.removeAllViews()
                if (hits.isEmpty()) {
                    found.addView(TextView(this).apply {
                        text = "No encontre TVs con depuracion ADB. Revisa que este activada y en la misma red."
                    })
                } else hits.sorted().forEach { h ->
                    found.addView(button("Usar $h") { ip.setText(h) })
                }
            }
        }
    }

    // ---------- Flujo principal ----------
    private fun flow(install: Boolean) {
        if (busy) return
        busy = true
        saveAll()
        logView.text = "Iniciando..."
        bg.execute {
            try { doFlow(install) } catch (e: Exception) { log("Error: ${e.message ?: e.toString()}") }
            busy = false
        }
    }

    private fun doFlow(install: Boolean) {
        val host = ip.text.toString().trim()
        if (host.isEmpty()) { log("Falta la IP del TV."); return }

        log("Conectando a $host... (la primera vez, acepta el cartel en el TV)")
        val dadb = Dadb.create(host, 5555, keyPair())
        try {
            log("Conectado: " + dadb.shell("getprop ro.product.model").output.trim())

            if (install) {
                val apk = ensureApk()
                val before = packages(dadb)
                log("Instalando en el TV...")
                dadb.push(apk, "/data/local/tmp/tvapp.apk")
                val r = dadb.shell("pm install -r /data/local/tmp/tvapp.apk")
                dadb.shell("rm /data/local/tmp/tvapp.apk")
                if (!r.allOutput.contains("Success")) { log("Fallo la instalacion: ${r.allOutput.trim()}"); return }
                log("Instalada.")
                val diff = packages(dadb) - before
                if (diff.size == 1) {
                    val p = diff.first()
                    prefs.edit().putString("pkg", p).apply()
                    runOnUiThread { pkg.setText(p) }
                    log("Paquete detectado: $p")
                }
            }

            val p = pkg.text.toString().trim()
            if (p.isEmpty()) {
                log("No se el paquete de la app. Abri Ajustes y escribilo (o desinstala la app del TV y volve a instalar).")
                return
            }

            log("Abriendo la app...")
            dadb.shell("am force-stop $p")
            Thread.sleep(1000)
            var out = dadb.shell("monkey -p $p -c android.intent.category.LEANBACK_LAUNCHER 1").allOutput
            if (out.contains("No activities found") || out.contains("aborted")) {
                out = dadb.shell("monkey -p $p -c android.intent.category.LAUNCHER 1").allOutput
            }

            val u = user.text.toString()
            val pw = pass.text.toString()
            val pause = delay.text.toString().toLongOrNull() ?: 600L
            if (u.isNotEmpty()) {
                val secs = wait.text.toString().toLongOrNull() ?: 6L
                log("Esperando ${secs}s a que cargue el login...")
                Thread.sleep(secs * 1000)
                runSeq(dadb, seq.text.toString().ifBlank { DEFAULT_SEQ }, u, pw, pause)
            }
            val extra = seq2.text.toString()
            if (extra.isNotBlank()) {
                log("Secuencia extra...")
                runSeq(dadb, extra, u, pw, pause)
            }
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
                up == "USER" || up == "{USER}" -> { log("> usuario"); sendText(d, u) }
                up == "PASS" || up == "{PASS}" -> { log("> clave"); sendText(d, pw) }
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
