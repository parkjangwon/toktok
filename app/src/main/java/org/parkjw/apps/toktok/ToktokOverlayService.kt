package org.parkjw.apps.toktok

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground service owning the floating voice-dictation button.
 *
 * Gesture arbitration: move > 10px = drag, press > 500ms = record until
 * release (button follows finger while held), tap (< 300ms) = record until
 * stop-button tap.
 */
class ToktokOverlayService : Service() {

    companion object {
        const val ACTION_STOP = "org.parkjw.apps.toktok.STOP"
        const val ACTION_RESET_POS = "org.parkjw.apps.toktok.RESET_POS"
        const val ACTION_IMAGE_CHANGED = "org.parkjw.apps.toktok.IMAGE_CHANGED"
        private const val CHANNEL_ID = "toktok_rec"
        private const val NOTIF_ID = 1
        private const val DRAG_PX = 24f // touch slop: ignore micro-movements
        private const val LONG_PRESS_MS = 500L

        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private var buttonWrap: FrameLayout? = null
    private var buttonImg: ImageView? = null
    private var btnParams: WindowManager.LayoutParams? = null

    private var recPill: View? = null
    private var recPillParams: WindowManager.LayoutParams? = null
    private var recTimer: TextView? = null
    private var waveform: WaveformView? = null
    private var recDot: View? = null

    private var procView: View? = null
    private var procJob: kotlinx.coroutines.Job? = null
    private var procParams: WindowManager.LayoutParams? = null
    private var procText: TextView? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var recording = false
    private var tapArmed = false
    private var holdRecording = false
    private var startTime = 0L
    private var recorder: AudioWavRecorder? = null
    private var recordFile: File? = null

    private var downX = 0f
    private var downY = 0f
    private var origX = 0
    private var origY = 0
    private var moved = false
    private var pulse: ObjectAnimator? = null
    private var waveTick: Runnable? = null

    private val longPress = Runnable {
        if (!moved && !tapArmed && !recording) {
            holdRecording = true
            buttonWrap?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startRecording()
        }
    }
    private val timerTick = object : Runnable {
        override fun run() {
            if (!recording) return
            updateRecPill()
            handler.postDelayed(this, 250)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        running = true
        startFg()
        try {
            addButton()
            addRecPill()
            addProcView()
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        ToktokAccessibilityService.keyboardListener = { visible ->
            scope.launch { setVisible(visible) }
        }
        setVisible(ToktokAccessibilityService.isKeyboardVisible)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESET_POS -> moveButtonTo(Prefs.getXRatio(this), Prefs.getYRatio(this))
            ACTION_IMAGE_CHANGED -> refreshButtonImage()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (recording) {
            recorder?.stop()
            recordFile?.delete()
        }
        recording = false
        pulse?.cancel()
        listOf(buttonWrap, recPill, procView).forEach {
            try {
                it?.let { v -> wm.removeView(v) }
            } catch (_: Exception) {
            }
        }
        buttonWrap = null
        buttonImg = null
        recPill = null
        procView = null
        ToktokAccessibilityService.keyboardListener = null
        scope.cancel()
        running = false
        super.onDestroy()
    }

    // ---- foreground ----

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        // Recreate channel as MIN importance: no status-bar icon, collapsed.
        // (Importance only applies on first creation, so use a new channel id.)
        val minChannelId = "${CHANNEL_ID}_min"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.deleteNotificationChannel(CHANNEL_ID)
            nm.createNotificationChannel(
                NotificationChannel(
                    minChannelId,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
        val notif = Notification.Builder(this, minChannelId)
            .setContentTitle(getString(R.string.notif_title))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ---- overlay views ----

    private fun addButton() {
        val density = resources.displayMetrics.density
        val size = (72 * density).toInt()

        // Avatar image (circular crop)
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = getString(R.string.app_name)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
        // Convex 3D overlay on top of avatar
        val convex = View(this).apply {
            background = getDrawable(R.drawable.convex_overlay)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            isClickable = false
            isFocusable = false
        }
        val wrap = FrameLayout(this).apply {
            addView(img, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(convex, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            elevation = 12f
            visibility = View.GONE
            setOnTouchListener { _, ev ->
                handleTouch(ev)
                true
            }
        }
        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(wrap, p)
        buttonWrap = wrap
        buttonImg = img
        btnParams = p
        refreshButtonImage()
        moveButtonTo(Prefs.getXRatio(this), Prefs.getYRatio(this))
    }

    private fun refreshButtonImage() {
        val img = buttonImg ?: return
        val custom = Prefs.getButtonImagePath(this)
        if (custom != null) {
            try {
                val bmp = BitmapFactory.decodeFile(custom)
                if (bmp != null) {
                    img.setImageBitmap(bmp)
                    return
                }
            } catch (_: Exception) {
            }
        }
        img.setImageResource(R.drawable.avatar)
    }

    private fun addRecPill() {
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_recording, null)
        recTimer = v.findViewById(R.id.recTimer)
        waveform = v.findViewById(R.id.waveform)
        recDot = v.findViewById(R.id.recDot)
        v.findViewById<ImageButton>(R.id.recStop).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (recording) finishRecording()
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        p.y = (120 * resources.displayMetrics.density).toInt()
        v.visibility = View.GONE
        wm.addView(v, p)
        recPill = v
        recPillParams = p
    }

    private fun addProcView() {
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_processing, null)
        procText = v.findViewById(R.id.procText)
        v.findViewById<Button>(R.id.procCancel)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            procJob?.cancel()
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        v.visibility = View.GONE
        wm.addView(v, p)
        procView = v
        procParams = p
    }

    private fun moveButtonTo(rx: Float, ry: Float) {
        val p = btnParams ?: return
        val m = resources.displayMetrics
        p.x = (rx * m.widthPixels).toInt().coerceIn(0, m.widthPixels)
        p.y = (ry * m.heightPixels).toInt().coerceIn(0, m.heightPixels)
        try {
            buttonWrap?.let { wm.updateViewLayout(it, p) }
        } catch (_: Exception) {
        }
    }

    private fun persistPosition() {
        val p = btnParams ?: return
        val m = resources.displayMetrics
        Prefs.setXRatio(this, (p.x.toFloat() / m.widthPixels).coerceIn(0f, 1f))
        Prefs.setYRatio(this, (p.y.toFloat() / m.heightPixels).coerceIn(0f, 1f))
    }

    private fun setVisible(visible: Boolean) {
        // Hidden only during processing overlay; during recording the button
        // stays visible (hiding it mid-gesture triggers ACTION_CANCEL).
        if (procView?.visibility == View.VISIBLE) {
            buttonWrap?.visibility = View.GONE
            return
        }
        buttonWrap?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ---- gestures ----
    //
    // DOWN: start 500ms long-press timer.
    // MOVE beyond touch slop before timer fires: pure drag (reposition, no record).
    // 500ms elapse: long-press -> start recording; button stays fixed while held.
    // UP before timer, no move: tap -> tap-mode recording (stop via pill button).
    // UP after long-press: stop recording.

    private fun handleTouch(ev: MotionEvent) {
        val p = btnParams ?: return
        val b = buttonWrap ?: return
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                origX = p.x
                origY = p.y
                moved = false
                b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                b.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start()
                if (!tapArmed && !recording) handler.postDelayed(longPress, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                // During long-press recording the button stays put.
                if (holdRecording) return
                if (!moved && (dx * dx + dy * dy) > DRAG_PX * DRAG_PX) {
                    moved = true
                    // Pure drag: cancel long-press, reposition only.
                    handler.removeCallbacks(longPress)
                }
                if (moved) {
                    p.x = origX + dx.toInt()
                    p.y = origY + dy.toInt()
                    try {
                        wm.updateViewLayout(b, p)
                    } catch (_: Exception) {
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                b.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                val cancelled = ev.action == MotionEvent.ACTION_CANCEL
                when {
                    holdRecording -> {
                        holdRecording = false
                        b.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        persistPosition()
                        finishRecording()
                    }
                    tapArmed && !moved && !cancelled -> {
                        b.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        finishRecording()
                    }
                    !moved && !cancelled && !recording && !tapArmed -> {
                        // Released before long-press fired: it's a tap.
                        tapArmed = true
                        startRecording()
                    }
                    moved -> persistPosition()
                    cancelled -> {
                        holdRecording = false
                        // Mark gesture dead so the following UP doesn't
                        // start a fresh tap recording.
                        moved = true
                        if (recording) finishRecording()
                    }
                }
            }
        }
    }

    // ---- recording pipeline ----

    private fun startRecording() {
        if (recording) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.need_mic, Toast.LENGTH_SHORT).show()
            resetRecordUi()
            return
        }
        if (Prefs.getApiKey(this).isBlank()) {
            Toast.makeText(this, R.string.need_api_key, Toast.LENGTH_SHORT).show()
            resetRecordUi()
            return
        }
        val file = File(cacheDir, "toktok_${System.currentTimeMillis()}.wav")
        val rec = AudioWavRecorder(file)
        if (!rec.start()) {
            Toast.makeText(this, R.string.rec_fail, Toast.LENGTH_SHORT).show()
            resetRecordUi()
            return
        }
        recorder = rec
        recordFile = file
        recording = true
        startTime = SystemClock.elapsedRealtime()
        // Keep the button visible: hiding it mid-gesture makes the system
        // send ACTION_CANCEL, which would kill the recording instantly.
        // For hold (long-press) recording, skip the pill: the finger is on
        // the button and haptic already confirmed the start.
        if (!holdRecording) {
            // Tap mode: show a compact pill to the left/right of the button
            // (Wispr-style), choosing the side with more space.
            recPillParams?.let { p ->
                btnParams?.let { bp ->
                    val dm = resources.displayMetrics
                    val screenW = dm.widthPixels
                    val btnSize = (56 * dm.density).toInt()
                    val btnCenterX = bp.x + btnSize / 2
                    val gap = (8 * dm.density).toInt()
                    p.gravity = Gravity.TOP or Gravity.START
                    // Pill width is unknown until laid out; estimate ~200dp.
                    val pillW = (200 * dm.density).toInt()
                    p.y = bp.y - (4 * dm.density).toInt()
                    if (btnCenterX < screenW / 2) {
                        // Button on left: pill to the right
                        p.x = (bp.x + btnSize + gap).coerceAtMost(screenW - pillW)
                    } else {
                        // Button on right: pill to the left
                        p.x = (bp.x - gap - pillW).coerceAtLeast(0)
                    }
                    try {
                        recPill?.let { wm.updateViewLayout(it, p) }
                    } catch (_: Exception) {
                    }
                }
            }
            recPill?.visibility = View.VISIBLE
        }
        updateRecPill()
        pulse = ObjectAnimator.ofFloat(recDot, "alpha", 1f, 0.3f).apply {
            duration = 600
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
        val wt = object : Runnable {
            override fun run() {
                if (!recording) return
                waveform?.amplitude = recorder?.amplitude ?: 0f
                handler.postDelayed(this, 80)
            }
        }
        waveTick = wt
        handler.post(wt)
        handler.post(timerTick)
    }

    private fun updateRecPill() {
        val sec = (SystemClock.elapsedRealtime() - startTime) / 1000
        recTimer?.text = getString(R.string.recording_fmt, sec / 60, sec % 60)
    }

    private fun finishRecording() {
        handler.removeCallbacks(timerTick)
        waveTick?.let { handler.removeCallbacks(it) }
        waveTick = null
        pulse?.cancel()
        pulse = null
        recDot?.alpha = 1f
        recPill?.visibility = View.GONE
        resetRecordUi()
        if (!recording) return
        recording = false
        recorder?.stop()
        recorder = null
        val file = recordFile
        recordFile = null
        if (file == null || !file.exists() || file.length() <= 44) {
            file?.delete()
            Toast.makeText(this, R.string.empty_rec, Toast.LENGTH_SHORT).show()
            setVisible(ToktokAccessibilityService.isKeyboardVisible)
            return
        }
        scope.launch { processFile(file) }.also { procJob = it }
    }

    private fun resetRecordUi() {
        tapArmed = false
    }

    private fun showProcessing(textRes: Int) {
        procText?.setText(textRes)
        procView?.visibility = View.VISIBLE
        buttonWrap?.visibility = View.GONE
    }

    private fun hideProcessing() {
        procView?.visibility = View.GONE
        setVisible(ToktokAccessibilityService.isKeyboardVisible)
    }

    private suspend fun processFile(file: File) {
        try {
            showProcessing(R.string.step_stt)
            val key = Prefs.getApiKey(this)
            val provider = Prefs.getProvider(this).takeIf { it.isNotBlank() }
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            val raw = OpenRouterClient.transcribe(
                key, Prefs.getSttModel(this).ifBlank { Prefs.DEFAULT_STT_MODEL },
                bytes, null, provider
            )
            if (raw.isBlank()) {
                Toast.makeText(this, R.string.empty_result, Toast.LENGTH_SHORT).show()
                return
            }
            val text = if (Prefs.isSkipCorrection(this)) {
                raw
            } else {
                showProcessing(R.string.step_correct)
                OpenRouterClient.correct(
                    key, Prefs.getCorrModel(this).ifBlank { Prefs.DEFAULT_CORR_MODEL },
                    raw, provider
                ).ifBlank { raw }
            }
            if (ToktokAccessibilityService.insertText(text)) {
                Toast.makeText(this, R.string.done, Toast.LENGTH_SHORT).show()
            } else {
                copyToClipboard(text)
                Toast.makeText(this, R.string.paste_manual, Toast.LENGTH_LONG).show()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // User cancelled: silent cleanup, no toast.
            throw e
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_fmt, e.message ?: ""), Toast.LENGTH_LONG)
                .show()
        } finally {
            procJob = null
            hideProcessing()
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("toktok", text))
    }
}
