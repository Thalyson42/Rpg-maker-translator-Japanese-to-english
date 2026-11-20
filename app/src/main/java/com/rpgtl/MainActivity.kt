package com.rpgtl

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var txtIn: TextView
    private lateinit var txtOut: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtLog: TextView
    private lateinit var etKeyDeepl: EditText
    private lateinit var etKeyGoogle: EditText
    private lateinit var cbFallback: CheckBox
    private lateinit var cbPlugins: CheckBox
    private lateinit var btnClear: Button
    private lateinit var etWidth: EditText
    private lateinit var progress: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var btnCancel: Button
    private lateinit var btnIn: Button
    private lateinit var btnOut: Button
    private lateinit var rbDeepl: RadioButton
    private lateinit var rbGoogle: RadioButton
    private lateinit var rbMlkit: RadioButton

    private var inUri: Uri? = null
    private var outUri: Uri? = null
    private var worker: Job? = null
    private val logBuffer = StringBuilder()

    private val pickIn = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persist(uri); inUri = uri
            prefs.edit().putString("in", uri.toString()).apply()
            updatePaths()
        }
    }
    private val pickOut = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persist(uri); outUri = uri
            prefs.edit().putString("out", uri.toString()).apply()
            updatePaths()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("rpgtl", MODE_PRIVATE)

        txtIn = findViewById(R.id.txtIn)
        txtOut = findViewById(R.id.txtOut)
        txtStatus = findViewById(R.id.txtStatus)
        txtLog = findViewById(R.id.txtLog)
        etKeyDeepl = findViewById(R.id.etKeyDeepl)
        etKeyGoogle = findViewById(R.id.etKeyGoogle)
        cbFallback = findViewById(R.id.cbFallback)
        cbPlugins = findViewById(R.id.cbPlugins)
        btnClear = findViewById(R.id.btnClear)
        etWidth = findViewById(R.id.etWidth)
        progress = findViewById(R.id.progress)
        btnStart = findViewById(R.id.btnStart)
        btnCancel = findViewById(R.id.btnCancel)
        btnIn = findViewById(R.id.btnIn)
        btnOut = findViewById(R.id.btnOut)
        rbDeepl = findViewById(R.id.rbDeepl)
        rbGoogle = findViewById(R.id.rbGoogle)
        rbMlkit = findViewById(R.id.rbMlkit)

        inUri = prefs.getString("in", null)?.let(Uri::parse)
        outUri = prefs.getString("out", null)?.let(Uri::parse)
        etWidth.setText(prefs.getInt("width", 50).toString())
        when (prefs.getString("provider", "mlkit")) {
            "google" -> rbGoogle.isChecked = true
            "deepl" -> rbDeepl.isChecked = true
            else -> rbMlkit.isChecked = true
        }
        etKeyDeepl.setText(prefs.getString("key_deepl", ""))
        etKeyGoogle.setText(prefs.getString("key_google", ""))
        cbFallback.isChecked = prefs.getBoolean("fallback", true)
        cbPlugins.isChecked = prefs.getBoolean("plugins", true)
        updatePaths()
        findViewById<TextView>(R.id.txtVersion).text = getString(R.string.version_fmt, appVersion())

        btnClear.setOnClickListener {
            File(filesDir, "cache.json").delete()
            toast(getString(R.string.cache_cleared))
        }
        btnIn.setOnClickListener { pickIn.launch(null) }
        btnOut.setOnClickListener { pickOut.launch(null) }
        btnStart.setOnClickListener { start() }
        btnCancel.setOnClickListener { worker?.cancel() }
    }

    private fun appVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    private fun persist(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun updatePaths() {
        txtIn.text = inUri?.lastPathSegment ?: getString(R.string.no_folder)
        txtOut.text = outUri?.lastPathSegment ?: getString(R.string.no_folder)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun appendLog(msg: String) {
        runOnUiThread {
            logBuffer.append(msg).append('\n')
            if (logBuffer.length > 12000) logBuffer.delete(0, logBuffer.length - 10000)
            txtLog.text = logBuffer
        }
    }

    private fun setRunning(running: Boolean) {
        btnStart.isEnabled = !running
        btnCancel.isEnabled = running
        btnIn.isEnabled = !running
        btnOut.isEnabled = !running
        btnClear.isEnabled = !running
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun start() {
        val inU = inUri
        val outU = outUri
        val kd = etKeyDeepl.text.toString().trim()
        val kg = etKeyGoogle.text.toString().trim()
        if (inU == null || outU == null) { toast(getString(R.string.choose_folders)); return }

        val inRoot = DocumentFile.fromTreeUri(this, inU)
        val outRoot = DocumentFile.fromTreeUri(this, outU)
        if (inRoot == null || outRoot == null) {
            toast(getString(R.string.folder_access))
            return
        }

        val mode = when {
            rbGoogle.isChecked -> "google"
            rbMlkit.isChecked -> "mlkit"
            else -> "deepl"
        }
        val useFallback = cbFallback.isChecked
        if (mode == "deepl" && kd.isEmpty()) { toast(getString(R.string.need_deepl)); return }
        if (mode == "google" && kg.isEmpty()) { toast(getString(R.string.need_google)); return }

        val width = (etWidth.text.toString().toIntOrNull() ?: 50).coerceIn(20, 120)
        prefs.edit()
            .putString("key_deepl", kd)
            .putString("key_google", kg)
            .putString("provider", mode)
            .putBoolean("fallback", useFallback)
            .putBoolean("plugins", cbPlugins.isChecked)
            .putInt("width", width)
            .apply()

        val list = ArrayList<Translator>()
        when (mode) {
            "deepl" -> list.add(DeepLTranslator(kd))
            "google" -> list.add(GoogleTranslator(kg))
            else -> list.add(MlKitTranslator(::appendLog))
        }
        if (mode != "mlkit" && useFallback) {
            if (mode != "deepl" && kd.isNotEmpty()) list.add(DeepLTranslator(kd))
            if (mode != "google" && kg.isNotEmpty()) list.add(GoogleTranslator(kg))
            list.add(MlKitTranslator(::appendLog))
        }
        val translator: Translator =
            if (list.size == 1) list[0] else FallbackTranslator(list, ::appendLog)
        val cache = File(filesDir, "cache.json")

        logBuffer.setLength(0)
        txtLog.text = ""
        setRunning(true)

        val gt = GameTranslator(
            resolver = contentResolver,
            translator = translator,
            cacheFile = cache,
            width = width,
            log = ::appendLog,
            progress = { done, total, status ->
                runOnUiThread {
                    progress.max = total
                    progress.progress = done
                    txtStatus.text = "$done/$total  $status"
                }
            }
        )

        worker = lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) { GameFolders.resolve(contentResolver, inRoot) }
                val srcs = res.sources
                if (srcs == null) {
                    appendLog(res.info)
                    toast(res.info)
                    return@launch
                }
                val complete = gt.run(srcs, outRoot, inRoot, cbPlugins.isChecked)
                toast(getString(if (complete) R.string.done_toast else R.string.partial_toast))
            } catch (e: CancellationException) {
                appendLog(getString(R.string.cancelled))
                throw e
            } catch (e: Exception) {
                appendLog("ERROR: ${e.message}")
            } finally {
                setRunning(false)
            }
        }
    }
}
