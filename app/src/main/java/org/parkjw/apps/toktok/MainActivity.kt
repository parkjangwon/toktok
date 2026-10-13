package org.parkjw.apps.toktok

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.parkjw.apps.toktok.databinding.ActivityMainBinding

/** Settings screen: API key, models, toggles, permissions. */
class MainActivity : AppCompatActivity() {

    companion object {
        const val GITHUB_URL = "https://github.com/parkjangwon/toktok"
    }

    private lateinit var binding: ActivityMainBinding
    private val scope = MainScope()
    private var lastBackPress = 0L

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val intent = Intent(this, CropActivity::class.java)
                .putExtra(CropActivity.EXTRA_URI, uri)
            cropImage.launch(intent)
        }
    }

    private val cropImage = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(CropActivity.RESULT_PATH)
            if (path != null) {
                Prefs.setButtonImagePath(this, path)
                refreshButtonPreview()
                notifyOverlayImageChanged()
                Toast.makeText(this, R.string.btn_image_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Double back-press to exit
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.uptimeMillis()
                if (now - lastBackPress < 2000) {
                    finish()
                } else {
                    lastBackPress = now
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.back_twice),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })

        binding.apiKey.setText(Prefs.getApiKey(this))
        binding.sttModel.setText(Prefs.getSttModel(this))
        binding.correctionModel.setText(Prefs.getCorrModel(this))
        binding.provider.setText(Prefs.getProvider(this))

        binding.apiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) Prefs.setApiKey(this, binding.apiKey.text.toString().trim())
        }
        binding.sttModel.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) Prefs.setSttModel(this, binding.sttModel.text.toString().trim())
        }
        binding.correctionModel.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) Prefs.setCorrModel(this, binding.correctionModel.text.toString().trim())
        }
        binding.provider.setText(Prefs.getProvider(this))
        binding.provider.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) Prefs.setProvider(this, binding.provider.text.toString().trim())
        }
        binding.skipCorrection.isChecked = Prefs.isSkipCorrection(this)
        binding.skipCorrection.setOnCheckedChangeListener { _, checked ->
            Prefs.setSkipCorrection(this, checked)
        }

        binding.toggleOverlay.setOnClickListener {
            if (ToktokOverlayService.running) {
                stopService(Intent(this, ToktokOverlayService::class.java))
            } else if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_SHORT).show()
                openOverlaySettings()
            } else {
                startForegroundService(Intent(this, ToktokOverlayService::class.java))
            }
            scope.launch {
                delay(300)
                refresh()
            }
        }

        binding.btnOverlay.setOnClickListener { openOverlaySettings() }
        binding.btnMic.setOnClickListener {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), 100
            )
        }
        binding.btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }

        binding.appVersion.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)
        binding.btnGithub.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
        }

        binding.changeImage.setOnClickListener { pickImage.launch("image/*") }
        binding.resetImage.setOnClickListener {
            Prefs.setButtonImagePath(this, null)
            refreshButtonPreview()
            notifyOverlayImageChanged()
            Toast.makeText(this, R.string.btn_image_reset_done, Toast.LENGTH_SHORT).show()
        }
        // Circular preview
        binding.btnPreview.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.btnPreview.clipToOutline = true
        refreshButtonPreview()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        Prefs.setApiKey(this, binding.apiKey.text.toString().trim())
        Prefs.setSttModel(this, binding.sttModel.text.toString().trim())
        Prefs.setCorrModel(this, binding.correctionModel.text.toString().trim())
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        binding.statusOverlay.text =
            statusLine(R.string.perm_overlay, Settings.canDrawOverlays(this))
        binding.statusMic.text = statusLine(R.string.perm_mic, hasMic())
        binding.statusA11y.text =
            statusLine(R.string.perm_a11y, ToktokAccessibilityService.isEnabled(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.statusNotif.text = statusLine(R.string.perm_notif, hasNotif())
            binding.btnNotif.isEnabled = true
        } else {
            binding.statusNotif.text = getString(R.string.perm_notif_na)
            binding.btnNotif.isEnabled = false
        }
        binding.toggleOverlay.text = getString(
            if (ToktokOverlayService.running) R.string.overlay_stop else R.string.overlay_start
        )
    }

    private fun statusLine(labelRes: Int, ok: Boolean) =
        getString(if (ok) R.string.perm_ok_fmt else R.string.perm_need_fmt, getString(labelRes))

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasNotif() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun refreshButtonPreview() {
        val custom = Prefs.getButtonImagePath(this)
        if (custom != null) {
            try {
                val bmp = BitmapFactory.decodeFile(custom)
                if (bmp != null) {
                    binding.btnPreview.setImageBitmap(bmp)
                    return
                }
            } catch (_: Exception) {
            }
        }
        binding.btnPreview.setImageResource(R.drawable.avatar)
    }

    private fun notifyOverlayImageChanged() {
        if (ToktokOverlayService.running) {
            startService(
                Intent(this, ToktokOverlayService::class.java)
                    .setAction(ToktokOverlayService.ACTION_IMAGE_CHANGED)
            )
        }
    }
}
