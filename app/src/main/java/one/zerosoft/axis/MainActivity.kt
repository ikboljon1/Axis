package one.zerosoft.axis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import one.zerosoft.axis.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val svc = Intent(this, ScreenAssistantService::class.java)
                .putExtra(ScreenAssistantService.EXTRA_RESULT_CODE, res.resultCode)
                .putExtra(ScreenAssistantService.EXTRA_DATA, res.data)
            ContextCompat.startForegroundService(this, svc)
            Toast.makeText(this, "Готово! Откройте нужное приложение и нажмите на кнопку Axis", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Без разрешения на запись экрана анализ невозможен", Toast.LENGTH_LONG).show()
        }
    }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        start() // works without notifications too; continue either way
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.apiKey.setText(prefs.apiKey)
        b.apiKey.doAfterTextChanged { prefs.apiKey = it?.toString().orEmpty() }

        val modes = AnalysisMode.entries
        b.modeSpinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, modes.map { it.title }))
        fun applyMode(m: AnalysisMode) {
            b.modeSpinner.setText(m.title, false)
            b.modeDescription.text = m.description
            b.customPromptLayout.visibility = if (m == AnalysisMode.CUSTOM) View.VISIBLE else View.GONE
            b.chessNote.visibility = if (m == AnalysisMode.CHESS) View.VISIBLE else View.GONE
        }
        applyMode(prefs.mode)
        b.modeSpinner.setOnItemClickListener { _, _, pos, _ ->
            prefs.mode = modes[pos]
            applyMode(modes[pos])
        }

        b.customPrompt.setText(prefs.customPrompt)
        b.customPrompt.doAfterTextChanged { prefs.customPrompt = it?.toString().orEmpty() }

        b.preciseSwitch.isChecked = prefs.precise
        b.preciseSwitch.setOnCheckedChangeListener { _, checked -> prefs.precise = checked }

        b.startButton.setOnClickListener { onStartClicked() }
        b.stopButton.setOnClickListener {
            startService(Intent(this, ScreenAssistantService::class.java).setAction(ScreenAssistantService.ACTION_STOP))
            refreshButtons(running = false)
        }
    }

    override fun onResume() {
        super.onResume()
        // Mode may have been changed from the bubble (long-press).
        b.modeSpinner.setText(prefs.mode.title, false)
        b.modeDescription.text = prefs.mode.description
        refreshButtons(ScreenAssistantService.isRunning)
    }

    private fun refreshButtons(running: Boolean) {
        b.startButton.visibility = if (running) View.GONE else View.VISIBLE
        b.stopButton.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun onStartClicked() {
        if (prefs.apiKey.isBlank()) {
            b.apiKeyLayout.error = "Введите API-ключ"
            return
        }
        b.apiKeyLayout.error = null

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разрешите Axis показываться поверх других приложений", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        start()
    }

    private fun start() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
