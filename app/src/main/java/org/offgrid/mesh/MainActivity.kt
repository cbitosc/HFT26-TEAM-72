package org.offgrid.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.Locale
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import android.speech.tts.TextToSpeech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), RadioMeshManager.Listener {
    private lateinit var mesh: RadioMeshManager
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var peersBox: LinearLayout
    private lateinit var feed: LinearLayout
    private lateinit var message: TextInputEditText
    private lateinit var priority: Spinner
    private lateinit var topology: TopologyView
    private lateinit var localDeviceId: String
    private lateinit var statsContainer: LinearLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var messageCount = 0
    private val deliveryLabels = mutableMapOf<String, TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var selectedVoiceLanguage = "English"
    private var speechRecognizer: SpeechRecognizer? = null
    private var pendingMicrophoneAction: (() -> Unit)? = null
    private var pendingListenButton: MaterialButton? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var whisperModel: WhisperModel? = null

    private enum class Status { OK, WARNING, ERROR, ACTIVE }

    private val enableBtLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        mesh.start()
        mesh.scan()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val bluetoothGranted = if (Build.VERSION.SDK_INT >= 31) {
            result[Manifest.permission.BLUETOOTH_SCAN] == true && result[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else true
        if (bluetoothGranted) {
            checkBluetoothAndStart()
            if (Build.VERSION.SDK_INT >= 33 && result[Manifest.permission.POST_NOTIFICATIONS] == false) {
                showStatus("Mesh active — enable notifications for SOS alerts", Status.WARNING)
            }
        } else {
            showStatus("Bluetooth permissions required — enable Nearby Devices in Settings", Status.ERROR)
        }
    }
    private val microphonePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingMicrophoneAction?.invoke() else Toast.makeText(this, "Microphone permission is required for voice features", Toast.LENGTH_LONG).show()
        pendingMicrophoneAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = deviceId()
        localDeviceId = id
        mesh = RadioMeshManager.get(this, id).also { it.addListener(this) }
        setContentView(buildScreen(id))
        MeshService.start(this)
        initTextToSpeech()
        requestPermissionsAndStart()
        handleSendIntent(intent)
    }

    private fun handleSendIntent(intent: Intent?) {
        val action = intent?.action
        val body = intent?.getStringExtra("body")
        if (action == "org.offgrid.mesh.SEND_TEST" && !body.isNullOrBlank()) {
            mesh.send(intent.getStringExtra("priority") ?: "GENERAL", body)
        }
    }

    private fun deviceId(): String {
        val prefs: SharedPreferences = getSharedPreferences("offgrid", MODE_PRIVATE)
        return prefs.getString("node", null) ?: "NODE-" + Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).takeLast(4).uppercase().also { prefs.edit().putString("node", "NODE-$it").apply() }
    }

    private fun buildScreen(id: String): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(243, 238, 223))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(243, 238, 223))
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(80))
        }
        scroll.addView(page)

        // ── Header ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(12))
        }
        header.addView(TextView(this).apply {
            text = "OFFGRID"
            textSize = 30f
            setTextColor(Color.rgb(21, 21, 21))
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        header.addView(View(this).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.dot_green)
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(6) }
        })
        header.addView(TextView(this).apply {
            text = "ACTIVE"
            textSize = 11f
            setTextColor(Color.rgb(23, 114, 69))
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.08f
        })
        page.addView(header)
        page.addView(TextView(this).apply {
            text = "EMERGENCY MESH INTELLIGENCE · LOCAL RADIOS ONLY"
            textSize = 9f
            setTextColor(Color.rgb(119, 115, 106))
            letterSpacing = 0.16f
            setPadding(0, 0, 0, dp(16))
        })

        // ── Stats Row ──
        statsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }
        statsContainer.addView(statCard("ONLINE", "0", Color.rgb(23, 114, 69)), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        statsContainer.addView(statCard("CONNECTED", "0", Color.rgb(21, 94, 239)), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        statsContainer.addView(statCard("AVG RSSI", "—", Color.rgb(167, 100, 0)), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        statsContainer.addView(statCard("MESSAGES", "0", Color.rgb(21, 21, 21)), LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(statsContainer)

        // ── Hero Card ──
        val heroCard = card().apply {
            val content = getChildAt(0) as LinearLayout
            content.addView(TextView(this@MainActivity).apply {
                text = "Connected when the world isn't"
                textSize = 24f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.rgb(21, 21, 21))
                setLineSpacing(4f, 1f)
            })
            content.addView(TextView(this@MainActivity).apply {
                text = "Real local messages over Bluetooth RFCOMM and Wi‑Fi Direct.\nNo internet, cellular, cloud, or location tracking."
                setTextColor(Color.rgb(75, 74, 69))
                setPadding(0, dp(10), 0, dp(14))
                setLineSpacing(4f, 1f)
            })
            val statusRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            statusDot = View(this@MainActivity).apply {
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.dot_green)
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) }
            }
            statusText = TextView(this@MainActivity).apply {
                text = "Initializing local mesh…"
                setTextColor(Color.rgb(75, 74, 69))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
            }
            statusRow.addView(statusDot)
            statusRow.addView(statusText)
            content.addView(statusRow)
        }
        page.addView(heroCard)

        // ── Device Bar ──
        page.addView(sectionLabel("DEVICE", "$id · relay enabled · TTL 5"))

        // ── Action Buttons ──
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(button("SCAN PEERS", R.drawable.bg_button_primary, Color.WHITE) { mesh.scan() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        actions.addView(button("PAIR OFFGRID", R.drawable.bg_button_outline, Color.rgb(21, 21, 21)) { openBluetoothSettings() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        actions.addView(button("DISCOVERABLE", R.drawable.bg_button_outline, Color.rgb(21, 21, 21)) { requestDiscoverable() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        page.addView(actions)
        page.addView(button("BROADCAST SOS", R.drawable.bg_button_danger, Color.WHITE) { showSosSheet() },
            LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })

        // ── Secure group and operating role ──
        page.addView(sectionLabel("RESCUE GROUP", "Every participating phone must use the same group code"))
        val groupCode = TextInputEditText(this).apply {
            hint = if (MeshSecurity.hasCustomCode()) "Custom rescue-group code saved" else "Enter a shared rescue-group code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.rgb(21, 21, 21)); setHintTextColor(Color.rgb(119, 115, 106))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        page.addView(groupCode, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) })
        page.addView(button("SAVE GROUP CODE", R.drawable.bg_button_outline, Color.rgb(21, 21, 21)) {
            val code = groupCode.text?.toString().orEmpty()
            if (code.length < 8) { showStatus("Use at least 8 characters for the rescue-group code", Status.WARNING); return@button }
            MeshSecurity.setGroupCode(this, code)
            groupCode.setText("")
            groupCode.hint = "Custom rescue-group code saved"
            showStatus("Rescue-group code saved — set the same code on every phone", Status.OK)
        }, LinearLayout.LayoutParams(-1, dp(44)))

        page.addView(sectionLabel("OPERATING MODE", "Controls battery use and whether this phone confirms responder receipt"))
        val powerButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        powerButtons.addView(button("NORMAL", R.drawable.bg_button_outline, Color.rgb(21, 21, 21)) { mesh.setPowerMode(RadioMeshManager.POWER_NORMAL) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        powerButtons.addView(button("EMERGENCY", R.drawable.bg_button_primary, Color.WHITE) { mesh.setPowerMode(RadioMeshManager.POWER_EMERGENCY) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        powerButtons.addView(button("RELAY", R.drawable.bg_button_outline, Color.rgb(21, 21, 21)) { mesh.setPowerMode(RadioMeshManager.POWER_RELAY) }, LinearLayout.LayoutParams(0, dp(44), 1f))
        page.addView(powerButtons)
        val responderButton = button(if (mesh.isResponder()) "RESPONDER GATEWAY: ON" else "ENABLE RESPONDER GATEWAY", R.drawable.bg_button_outline, Color.rgb(21, 21, 21)) { }
        responderButton.setOnClickListener {
            mesh.setResponder(!mesh.isResponder())
            responderButton.text = if (mesh.isResponder()) "RESPONDER GATEWAY: ON" else "ENABLE RESPONDER GATEWAY"
        }
        page.addView(responderButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })

        // ── Topology with zoom controls ──
        val topoHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(6))
        }
        topoHeader.addView(TextView(this).apply {
            text = "LIVE TOPOLOGY"
            textSize = 11f
            setTextColor(Color.rgb(21, 21, 21))
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        topoHeader.addView(zoomBtn("−") { topology.adjustZoom(0.8f) })
        topoHeader.addView(zoomBtn("+") { topology.adjustZoom(1.25f) })
        topoHeader.addView(zoomBtn("⟲") { topology.resetZoom() })
        page.addView(topoHeader)
        page.addView(TextView(this).apply {
            text = "Pinch to zoom · drag to pan · double-tap to reset"
            textSize = 9f
            setTextColor(Color.rgb(119, 115, 106))
            setPadding(0, 0, 0, dp(4))
        })
        topology = TopologyView(this).apply { update(id, emptyList()) }
        page.addView(topology, LinearLayout.LayoutParams(-1, dp(280)))
        page.addView(TextView(this).apply {
            text = "■ this device    ━ connected    ─ discovered    · range is RSSI estimate only"
            textSize = 9f
            setTextColor(Color.rgb(119, 115, 106))
            setPadding(0, dp(6), 0, 0)
            letterSpacing = 0.04f
        })

        // ── Nearby Devices with SwipeRefresh ──
        page.addView(sectionLabel("NEARBY OFFGRID PEERS", "Tap to connect · pull down to rescan"))
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.rgb(21, 94, 239))
            layoutParams = LinearLayout.LayoutParams(-1, dp(480))
        }
        peersBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        swipeRefresh.addView(peersBox)
        page.addView(swipeRefresh)
        swipeRefresh.setOnRefreshListener {
            mesh.scan()
            handler.postDelayed({ swipeRefresh.isRefreshing = false }, 1500)
        }

        // ── Send Message (inline) ──
        page.addView(sectionLabel("SEND MESSAGE", "Relayed across connected local radios"))
        val inlinePriority = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("SOS", "MEDICAL", "GENERAL"))
            minimumHeight = dp(44)
        }
        val inlineInput = TextInputEditText(this).apply {
            minLines = 2
            setTextColor(Color.rgb(21, 21, 21))
            setHintTextColor(Color.rgb(119, 115, 106))
            hint = "Message for nearby devices"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val inlineSend = button("TRANSMIT OVER LOCAL MESH", R.drawable.bg_button_danger, Color.WHITE) {
            val body = inlineInput.text?.toString()?.trim().orEmpty()
            if (body.isEmpty()) { showStatus("Enter a message before transmitting.", Status.WARNING); return@button }
            val prio = inlinePriority.selectedItem?.toString() ?: "GENERAL"
            mesh.send(prio, body)
            inlineInput.setText("")
            messageCount++
            updateStat("MESSAGES", messageCount.toString())
            showStatus("Packet $body sent (${prio})", Status.ACTIVE)
        }
        page.addView(inlinePriority, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) })
        page.addView(inlineInput, LinearLayout.LayoutParams(-1, dp(80)).apply { bottomMargin = dp(8) })
        page.addView(inlineSend, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) })
        page.addView(button("RECORD & SEND VOICE MESSAGE", R.drawable.bg_button_primary, Color.WHITE) { showVoiceSheet() },
            LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(12) })
        page.addView(button("VOICE TO TEXT MESSAGE", R.drawable.bg_button_outline, Color.rgb(21, 21, 21)) { showSpeechToTextSheet() },
            LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(12) })
        page.addView(button("CHECK TEXT-TO-SPEECH LANGUAGES", R.drawable.bg_button_outline, Color.rgb(21, 21, 21)) { showTtsLanguages() },
            LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(12) })

        // ── Activity Feed ──
        page.addView(sectionLabel("MESSAGE ACTIVITY", "Packet IDs, hops, and delivery observations"))
        feed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(feed)

        // ── Footer ──
        page.addView(TextView(this).apply {
            text = "LOCAL-ONLY · No INTERNET permission · Wi‑Fi Direct may temporarily affect ordinary Wi‑Fi on some models · Delivery confirmed only when receiving device reports it"
            textSize = 9f
            setTextColor(Color.rgb(119, 115, 106))
            setPadding(0, dp(28), 0, 0)
            setLineSpacing(3f, 1f)
            letterSpacing = 0.02f
        })

        root.addView(scroll)

        // ── FAB ──
        val fab = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_dialog_email)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(240, 68, 46))
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            elevation = dp(6).toFloat()
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(dp(16), dp(16), dp(16), dp(16))
            }
            setOnClickListener { showMessageSheet() }
        }
        root.addView(fab)

        return root
    }

    private fun showMessageSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_message, null)
        sheet.setContentView(view)

        val spinner = view.findViewById<Spinner>(R.id.sheet_priority)
        val et = view.findViewById<TextInputEditText>(R.id.sheet_message)
        val sendBtn = view.findViewById<MaterialButton>(R.id.sheet_send)

        val priorities = arrayOf("SOS", "MEDICAL", "GENERAL")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, priorities)
        spinner.adapter = adapter

        sendBtn.setOnClickListener {
            val body = et.text?.toString()?.trim().orEmpty()
            if (body.isEmpty()) {
                Toast.makeText(this, "Enter a message before transmitting", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val prio = spinner.selectedItem?.toString() ?: "GENERAL"
            mesh.send(prio, body)
            messageCount++
            updateStat("MESSAGES", messageCount.toString())
            Status.ACTIVE.let { }
            et.setText("")
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun showVoiceSheet() {
        val dialog = BottomSheetDialog(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(28)) }
        content.addView(TextView(this).apply { text = "OFFLINE VOICE MESSAGE"; textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(Color.rgb(21, 21, 21)) })
        content.addView(TextView(this).apply { text = "Records up to 30 seconds as WAV and relays encrypted audio through the local mesh."; setTextColor(Color.rgb(75, 74, 69)); setPadding(0, dp(6), 0, dp(12)) })
        val language = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, VoiceMessage.languages) }
        content.addView(language, LinearLayout.LayoutParams(-1, dp(48)))
        val record = button("START RECORDING", R.drawable.bg_button_danger, Color.WHITE) { }
        val send = button("SEND VOICE MESSAGE", R.drawable.bg_button_primary, Color.WHITE) { }
        send.isEnabled = false
        record.setOnClickListener {
            selectedVoiceLanguage = language.selectedItem?.toString() ?: "English"
            if (recorder == null) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoiceRecording()
                else { pendingMicrophoneAction = { startVoiceRecording() }; microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                record.text = "STOP RECORDING"
                send.isEnabled = false
            } else {
                stopVoiceRecording()
                record.text = "RECORD AGAIN"
                send.isEnabled = voiceFile?.exists() == true
            }
        }
        send.setOnClickListener {
            val file = voiceFile ?: return@setOnClickListener
            try {
                mesh.send("GENERAL", VoiceMessage.encode(file, selectedVoiceLanguage))
                messageCount++
                updateStat("MESSAGES", messageCount.toString())
                dialog.dismiss()
            } catch (e: Exception) { Toast.makeText(this, e.message ?: "Could not send voice message", Toast.LENGTH_LONG).show() }
        }
        content.addView(record, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        content.addView(send, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        dialog.setOnDismissListener { stopVoiceRecording() }
        dialog.setContentView(content)
        dialog.show()
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false

    private fun startVoiceRecording() {
        stopVoiceRecording()
        val sampleRate = 16000
        val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
        val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf <= 0) { Toast.makeText(this, "Audio recording not supported", Toast.LENGTH_LONG).show(); return }
        try {
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, minBuf * 2)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) { Toast.makeText(this, "Could not initialize recorder", Toast.LENGTH_LONG).show(); audioRecord?.release(); audioRecord = null; return }
            voiceFile = File(cacheDir, "offgrid-voice-${System.currentTimeMillis()}.wav")
            isRecording = true
            audioRecord?.startRecording()
            recordingThread = Thread {
                try {
                    val out = java.io.FileOutputStream(voiceFile!!)
                    val header = ByteArray(44)
                    out.write(header)
                    val buffer = ShortArray(minBuf / 2)
                    var totalBytes = 0
                    while (isRecording) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            val byteBuf = java.nio.ByteBuffer.allocate(read * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until read) byteBuf.putShort(buffer[i])
                            out.write(byteBuf.array())
                            totalBytes += read * 2
                        }
                    }
                    out.close()
                    writeWavHeader(voiceFile!!, sampleRate, 1, 16, totalBytes)
                } catch (_: Exception) { }
            }.apply { start() }
            showStatus("Recording voice message — tap stop within 30 seconds", Status.ACTIVE)
        } catch (e: Exception) {
            audioRecord?.release(); audioRecord = null
            Toast.makeText(this, "Could not start recording: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopVoiceRecording() {
        isRecording = false
        try { recordingThread?.join(5000) } catch (_: Exception) { }
        recordingThread = null
        try { audioRecord?.stop() } catch (_: Exception) { }
        audioRecord?.release()
        audioRecord = null
    }

    private fun writeWavHeader(file: File, sampleRate: Int, channels: Int, bitsPerSample: Int, pcmDataSize: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalSize = pcmDataSize + 36
        val header = ByteArray(44)
        val bb = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        // RIFF header
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        bb.position(4); bb.putInt(totalSize)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        // fmt chunk
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        bb.position(16); bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort(blockAlign.toShort())
        bb.putShort(bitsPerSample.toShort())
        // data chunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        bb.position(40); bb.putInt(pcmDataSize)
        // write header to file (overwrite first 44 bytes)
        val raf = java.io.RandomAccessFile(file, "rw")
        raf.seek(0)
        raf.write(header)
        raf.close()
    }

    private fun showSpeechToTextSheet() {
        val dialog = BottomSheetDialog(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(28)) }
        content.addView(TextView(this).apply { text = "VOICE TO TEXT"; textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(Color.rgb(21, 21, 21)) })
        content.addView(TextView(this).apply { text = "Uses the phone's speech model and requests offline recognition. Choose a downloaded language pack for offline use."; setTextColor(Color.rgb(75, 74, 69)); setPadding(0, dp(6), 0, dp(12)) })
        val language = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, VoiceMessage.languages) }
        val transcript = TextInputEditText(this).apply { hint = "Recognized text appears here"; minLines = 4; setTextColor(Color.rgb(21, 21, 21)); background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        val status = TextView(this).apply { setTextColor(Color.rgb(119, 115, 106)); textSize = 12f; setPadding(0, dp(8), 0, dp(4)) }
        val listen = button("START RECORDING (Whisper)", R.drawable.bg_button_danger, Color.WHITE) { }
        val listenAndroid = button("START LISTENING (Android)", R.drawable.bg_button_outline, Color.rgb(21, 21, 21)) { }
        val send = button("SEND TRANSCRIBED TEXT", R.drawable.bg_button_primary, Color.WHITE) { }
        send.isEnabled = false
        listen.setOnClickListener {
            val selected = language.selectedItem?.toString() ?: "English"
            if (recorder == null && audioRecord == null) {
                val start = { startVoiceRecording(); listen.text = "STOP & TRANSCRIBE"; status.text = "Recording $selected for local transcription…" }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                else { pendingMicrophoneAction = start; microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            } else {
                stopVoiceRecording()
                val file = voiceFile
                if (file == null || !file.exists()) { status.text = "Recording failed. Try again."; return@setOnClickListener }
                listen.isEnabled = false
                status.text = "Transcribing locally with the multilingual model…"
                Toast.makeText(this, "Audio file: ${file.length()} bytes", Toast.LENGTH_SHORT).show()
                transcribeWithWhisper(file, selected, transcript, status, send, listen)
            }
        }
        listenAndroid.setOnClickListener {
            val selected = language.selectedItem?.toString() ?: "English"
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingMicrophoneAction = { pendingListenButton = listenAndroid; listenAndroid.isEnabled = false; listenAndroid.text = "LISTENING…"; startSpeechRecognition(selected, transcript, status, send) }
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return@setOnClickListener
            }
            speechRecognizer?.destroy()
            listenAndroid.isEnabled = false
            listenAndroid.text = "LISTENING…"
            status.text = "Listening in $selected…"
            pendingListenButton = listenAndroid
            startSpeechRecognition(selected, transcript, status, send)
        }
        send.setOnClickListener {
            val text = transcript.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                val name = language.selectedItem?.toString() ?: "English"
                mesh.send("GENERAL", "[$name voice-to-text]\n$text")
                messageCount++; updateStat("MESSAGES", messageCount.toString()); dialog.dismiss()
            }
        }
        content.addView(language, LinearLayout.LayoutParams(-1, dp(48)))
        content.addView(transcript, LinearLayout.LayoutParams(-1, dp(116)).apply { topMargin = dp(8) })
        content.addView(status)
        content.addView(listen, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        content.addView(listenAndroid, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        content.addView(send, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        dialog.setOnDismissListener { speechRecognizer?.destroy(); speechRecognizer = null }
        dialog.setContentView(content)
        dialog.show()
    }

    private fun startSpeechRecognition(language: String, transcript: TextInputEditText, status: TextView, send: MaterialButton) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { status.text = "Speech recognition is unavailable on this phone"; return }
        val hasOfflineRecognizer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        speechRecognizer?.destroy()
        val recognizer = if (hasOfflineRecognizer) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status.text = if (hasOfflineRecognizer) "Listening offline in $language…" else "Listening in $language…" }
            override fun onBeginningOfSpeech() { status.text = "Listening…" }
            override fun onRmsChanged(rmsdB: Float) { }
            override fun onBufferReceived(buffer: ByteArray?) { }
            override fun onEndOfSpeech() { status.text = "Processing speech…" }
            override fun onError(error: Int) {
                Log.e("OFFGRID-SPEECH", "Recognition failed language=$language tag=${VoiceMessage.recognizerLanguageTag(language)} error=$error offline=$hasOfflineRecognizer")
                pendingListenButton?.isEnabled = true
            pendingListenButton?.text = "START LISTENING"
            pendingListenButton = null
            status.text = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio input failed. Check microphone permission and try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Offline speech model unavailable for $language. Download its language pack in Android Settings."
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized. Speak clearly and try again."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Wait a moment and try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Tap Start Listening and speak."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "$language is not available in this phone's offline speech recognizer."
                    else -> "Could not transcribe. Install the $language offline speech pack and try again."
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                Log.d("OFFGRID-SPEECH", "Recognition result language=$language length=${text.length}")
                transcript.setText(text); send.isEnabled = text.isNotBlank(); status.text = if (text.isBlank()) "No speech recognized" else "Text ready to review and send"
                pendingListenButton?.isEnabled = true
                pendingListenButton?.text = "START LISTENING"
                pendingListenButton = null
            }
            override fun onPartialResults(partialResults: Bundle?) { }
            override fun onEvent(eventType: Int, params: Bundle?) { }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, VoiceMessage.recognizerLanguageTag(language))
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
    }

    private fun showSosSheet() {
        val dialog = BottomSheetDialog(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(28)) }
        body.addView(TextView(this).apply { text = "BROADCAST SOS"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.rgb(194, 42, 21)) })
        body.addView(TextView(this).apply { text = "This alert is encrypted and relayed to nearby devices."; setPadding(0, dp(6), 0, dp(12)); setTextColor(Color.rgb(75, 74, 69)) })
        fun field(hint: String) = TextInputEditText(this).apply {
            this.hint = hint; background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
            setTextColor(Color.rgb(21, 21, 21)); setHintTextColor(Color.rgb(119, 115, 106)); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val need = field("Need help with: medical, fire, trapped…")
        val people = field("People affected (optional)")
        val details = field("Injuries or critical details (optional)").apply { minLines = 2 }
        val shareLocation = CheckBox(this).apply { text = "Include my last known location"; isChecked = true }
        body.addView(need, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        body.addView(people, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        body.addView(details, LinearLayout.LayoutParams(-1, dp(72)).apply { bottomMargin = dp(4) })
        body.addView(shareLocation)
        body.addView(button("SEND SOS NOW", R.drawable.bg_button_danger, Color.WHITE) {
            if (need.text?.toString()?.trim().isNullOrEmpty()) { Toast.makeText(this, "Describe the help needed", Toast.LENGTH_SHORT).show(); return@button }
            broadcastSos(need.text.toString().trim(), people.text?.toString()?.trim().orEmpty(), details.text?.toString()?.trim().orEmpty(), shareLocation.isChecked)
            dialog.dismiss()
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        dialog.setContentView(body)
        dialog.show()
    }

    private fun broadcastSos(need: String, people: String, details: String, includeLocation: Boolean) {
        val location = if (includeLocation && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val lm = getSystemService(LocationManager::class.java)
                (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                    ?.let { "\nLocation (shared by you): %.5f, %.5f".format(it.latitude, it.longitude) }.orEmpty()
            } catch (_: Exception) { "" }
        } else ""
        val peopleLine = people.takeIf { it.isNotBlank() }?.let { "\nPeople affected: $it" }.orEmpty()
        val detailsLine = details.takeIf { it.isNotBlank() }?.let { "\nDetails: $it" }.orEmpty()
        mesh.send("SOS", "SOS — Help needed: $need$peopleLine$detailsLine$location\nPlease acknowledge and relay to a responder.")
        messageCount++
        updateStat("MESSAGES", messageCount.toString())
        showStatus("SOS queued — searching for nearby relays", Status.ACTIVE)
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                val result = tts?.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    showStatus("Text-to-speech language data missing — tap CHECK TTS LANGUAGES", Status.WARNING)
                } else showStatus("Text-to-speech ready", Status.OK)
            } else showStatus("Text-to-speech unavailable on this phone", Status.ERROR)
        }
    }

    private fun speakText(text: String, language: String? = null) {
        if (!ttsReady) { Toast.makeText(this, "Text-to-speech is not ready", Toast.LENGTH_SHORT).show(); return }
        if (language != null) {
            val locale = try { Locale.forLanguageTag(VoiceMessage.recognizerLanguageTag(language)) } catch (_: Exception) { Locale.getDefault() }
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "TTS data missing for $language — install language pack", Toast.LENGTH_LONG).show()
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                return
            }
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "offgrid-tts")
    }

    private fun transcribeWithWhisper(file: File, language: String, transcript: TextInputEditText, status: TextView, send: MaterialButton, recordButton: MaterialButton) {
        if (file.length() <= 44) { status.text = "Recording failed or empty. Try again."; recordButton.isEnabled = true; recordButton.text = "RECORD AGAIN"; return }
        Log.d("OFFGRID-WHISPER", "transcribing ${file.absolutePath} size=${file.length()}")
        try {
            val headerBytes = file.readBytes().copyOfRange(0, 44.coerceAtMost(file.length().toInt()))
            Log.d("OFFGRID-WHISPER", "header=${headerBytes.joinToString(" ") { "%02x".format(it) }}")
        } catch (_: Exception) { }
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    if (whisperModel == null) whisperModel = Whisper.loadModelFromAsset(this@MainActivity, "models/ggml-tiny.bin")
                    val model = whisperModel!!
                    Whisper.transcribe(model, file.absolutePath, WhisperConfig(language = VoiceMessage.whisperLanguageCode(language), threads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2), translate = false))
                }
                transcript.setText(result.text.trim())
                send.isEnabled = result.text.isNotBlank()
                status.text = if (result.text.isBlank()) "No speech recognized. Record again and speak clearly." else "Local transcript ready to review and send"
            } catch (e: Exception) {
                Log.e("OFFGRID-WHISPER", "Local transcription failed", e)
                status.text = "Local transcription failed: ${e.message ?: "unsupported audio"}"
            } finally {
                recordButton.isEnabled = true
                recordButton.text = "RECORD AGAIN"
            }
        }
    }

    private fun showTtsLanguages() {
        if (!ttsReady) { Toast.makeText(this, "Text-to-speech is not ready", Toast.LENGTH_SHORT).show(); return }
        val supported = mutableListOf<String>()
        val missing = mutableListOf<String>()
        VoiceMessage.languages.forEach { lang ->
            val locale = try { Locale.forLanguageTag(VoiceMessage.recognizerLanguageTag(lang)) } catch (_: Exception) { Locale.getDefault() }
            val result = tts?.isLanguageAvailable(locale)
            if (result == TextToSpeech.LANG_AVAILABLE || result == TextToSpeech.LANG_COUNTRY_AVAILABLE || result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) supported += lang
            else missing += lang
        }
        val msg = "Supported (${supported.size}): ${supported.joinToString(", ")}\n\nMissing data (${missing.size}): ${missing.joinToString(", ")}"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Text-to-speech languages")
            .setMessage(msg)
            .setPositiveButton("Install TTS data") { _, _ -> startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun statCard(label: String, value: String, color: Int): MaterialCardView {
        return MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            setCardBackgroundColor(Color.rgb(251, 248, 239))
            strokeColor = Color.rgb(201, 193, 176)
            strokeWidth = dp(1)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            val inner = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                gravity = Gravity.CENTER
            }
            val valueText = TextView(this@MainActivity).apply {
                text = value
                textSize = 20f
                setTextColor(color)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            val labelText = TextView(this@MainActivity).apply {
                text = label
                textSize = 9f
                setTextColor(Color.rgb(119, 115, 106))
                letterSpacing = 0.1f
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            }
            inner.addView(valueText)
            inner.addView(labelText)
            addView(inner)
            tag = label
        }
    }

    private fun updateStat(label: String, value: String) {
        for (i in 0 until statsContainer.childCount) {
            val card = statsContainer.getChildAt(i) as? MaterialCardView ?: continue
            if (card.tag == label) {
                val inner = card.getChildAt(0) as LinearLayout
                (inner.getChildAt(0) as TextView).text = value
                return
            }
        }
    }

    private fun sectionLabel(title: String, sub: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(20), 0, dp(8))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 11f
            setTextColor(Color.rgb(21, 21, 21))
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.12f
        })
        addView(TextView(this@MainActivity).apply {
            text = sub
            textSize = 11f
            setTextColor(Color.rgb(119, 115, 106))
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(12).toFloat()
        setCardBackgroundColor(Color.rgb(251, 248, 239))
        strokeColor = Color.rgb(201, 193, 176)
        strokeWidth = dp(1)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        val inner = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        addView(inner)
    }

    private fun button(label: String, bgRes: Int, textColor: Int, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        setTextColor(textColor)
        background = ContextCompat.getDrawable(this@MainActivity, bgRes)
        textSize = 12f
        isAllCaps = false
        letterSpacing = 0.04f
        setOnClickListener { onClick() }
    }

    private fun zoomBtn(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        setTextColor(Color.rgb(75, 74, 69))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_outline)
        textSize = 14f
        isAllCaps = false
        minWidth = dp(36)
        minimumWidth = dp(36)
        insetTop = 0
        insetBottom = 0
        setPadding(dp(4), 0, dp(4), 0)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun showStatus(text: String, status: Status) {
        statusText.text = text
        val dotRes = when (status) {
            Status.OK -> R.drawable.dot_green
            Status.WARNING -> R.drawable.dot_amber
            Status.ERROR -> R.drawable.dot_red
            Status.ACTIVE -> R.drawable.dot_green
        }
        val textColor = when (status) {
            Status.OK -> Color.rgb(23, 114, 69)
            Status.WARNING -> Color.rgb(167, 100, 0)
            Status.ERROR -> Color.rgb(194, 42, 21)
            Status.ACTIVE -> Color.rgb(21, 94, 239)
        }
        statusDot.background = ContextCompat.getDrawable(this, dotRes)
        statusText.setTextColor(textColor)
    }

    private fun checkBluetoothAndStart() {
        val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (btAdapter != null && !btAdapter.isEnabled) {
            showStatus("Bluetooth is turned off — please turn on Bluetooth", Status.WARNING)
            try {
                enableBtLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
                mesh.start()
                mesh.scan()
            }
        } else {
            mesh.start()
            mesh.scan()
        }
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) permissions += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        permissions += Manifest.permission.ACCESS_FINE_LOCATION
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) checkBluetoothAndStart() else permissionLauncher.launch(missing.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun requestDiscoverable() {
        startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
    }

    private fun openBluetoothSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    override fun status(text: String) {
        // Determine actual connection state from peer list
        val connectedPeers = (0 until peersBox.childCount).count { i ->
            (peersBox.getChildAt(i) as? LinearLayout)?.let { row ->
                (row.getChildAt(0) as? TextView)?.text?.contains("CONNECTED") == true
            } ?: false
        }
        val displayText = when {
            text.contains("connected", true) && connectedPeers > 0 -> "Connected to OFFGRID peer"
            text.contains("connected", true) -> "Connected to OFFGRID peer"
            text.contains("unavailable", true) || text.contains("failed", true) || text.contains("busy", true) -> text
            text.contains("Scanning", true) -> "Scanning for OFFGRID peers…"
            else -> text
        }
        showStatus(displayText, when {
            text.contains("unavailable", true) || text.contains("failed", true) || text.contains("busy", true) -> Status.ERROR
            text.contains("Scanning", true) -> Status.ACTIVE
            text.contains("connected", true) -> Status.OK
            else -> Status.WARNING
        })
    }

    override fun peers(items: List<RadioMeshManager.Peer>) {
        topology.update(localDeviceId, items)
        val online = items.size
        val connected = items.count { it.connected }
        val avgRssi = (items.mapNotNull { it.rssiDbm }.average()).toInt()
        updateStat("ONLINE", online.toString())
        updateStat("CONNECTED", connected.toString())
        updateStat("AVG RSSI", if (avgRssi > 0) "$avgRssi" else "—")
        peersBox.removeAllViews()
        if (items.isEmpty()) {
            peersBox.addView(TextView(this).apply {
                text = "No OFFGRID peers found yet.\n\nTap PAIR OFFGRID to open Bluetooth settings,\npair another OFFGRID phone, then tap SCAN PEERS."
                setTextColor(Color.rgb(119, 115, 106))
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_card)
                textSize = 13f
                gravity = Gravity.CENTER
                setLineSpacing(4f, 1f)
            })
        }
        items.sortedByDescending { it.rssiDbm ?: Int.MIN_VALUE }.forEach { p ->
            val rssi = p.rssiDbm?.let { "  $it dBm" }.orEmpty()
            val isConn = p.connected
            val statusLabel = if (isConn) "● CONNECTED" else "CONNECT  "
            val bgRes = if (isConn) R.drawable.bg_peer_connected else R.drawable.bg_peer_available
            val txtColor = if (isConn) Color.rgb(21, 94, 239) else Color.rgb(21, 21, 21)
            val peerView = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@MainActivity, bgRes)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                minimumHeight = dp(40)
            }
            peerView.addView(TextView(this@MainActivity).apply {
                text = statusLabel
                setTextColor(txtColor)
                setTypeface(null, Typeface.BOLD)
                textSize = 10f
                letterSpacing = 0.04f
            })
            peerView.addView(TextView(this@MainActivity).apply {
                text = "  ${p.label} · ${p.transport}$rssi"
                setTextColor(txtColor)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            peerView.setOnClickListener {
                if (!isConn) {
                    mesh.connect(p)
                    showStatus("Connecting to ${p.label}…", Status.ACTIVE)
                }
            }
            peersBox.addView(peerView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
        }
    }

    override fun packet(packet: MeshPacket, local: Boolean) {
        val incoming = if (local) "SENT" else "RECEIVED · RELAYED"
        val voiceClip = VoiceMessage.decode(packet.body)
        val bubbleBg = when (packet.priority) {
            "SOS" -> R.drawable.bg_message_sos
            "MEDICAL" -> R.drawable.bg_message_medical
            else -> R.drawable.bg_message_general
        }
        val badgeBg = when (packet.priority) {
            "SOS" -> R.drawable.bg_badge_sos
            "MEDICAL" -> R.drawable.bg_badge_medical
            else -> R.drawable.bg_badge_general
        }
        val bubbleText = Color.rgb(21, 21, 21)
        val bubbleSecondary = Color.rgb(75, 74, 69)
        val bubble = card().apply {
            val content = getChildAt(0) as LinearLayout
            content.background = ContextCompat.getDrawable(this@MainActivity, bubbleBg)
            content.setPadding(dp(14), dp(12), dp(14), dp(12))
            content.addView(TextView(this@MainActivity).apply {
                text = "$incoming · ${packet.priority}"
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                textSize = 11f
                letterSpacing = 0.06f
                background = ContextCompat.getDrawable(this@MainActivity, badgeBg)
                setPadding(dp(8), dp(4), dp(8), dp(4))
            })
            content.addView(TextView(this@MainActivity).apply {
                text = if (local) MessageStore.PENDING else "RECEIVED — receipt sent"
                setTextColor(bubbleSecondary)
                textSize = 10f
                setPadding(0, dp(5), 0, 0)
                deliveryLabels[packet.id] = this
            })
            if (voiceClip != null) {
                content.addView(TextView(this@MainActivity).apply {
                    text = "VOICE MESSAGE · ${voiceClip.language}"
                    setTextColor(bubbleText); setTypeface(null, Typeface.BOLD); textSize = 15f
                    setPadding(0, dp(10), 0, dp(6))
                })
                content.addView(button("PLAY VOICE MESSAGE", R.drawable.bg_button_primary, Color.WHITE) {
                    VoiceMessage.play(this@MainActivity, packet.id, packet.body)
                    showStatus("Playing ${voiceClip.language} voice message", Status.OK)
                }, LinearLayout.LayoutParams(-1, dp(42)))
            } else content.addView(TextView(this@MainActivity).apply {
                text = packet.body
                setTextColor(bubbleText)
                textSize = 15f
                setPadding(0, dp(6), 0, 0)
            })
            val btnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            btnRow.addView(button("SPEAK", R.drawable.bg_button_outline, Color.rgb(21, 21, 21)) {
                speakText(packet.body)
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) })
            if (!local) {
                btnRow.addView(button("REPLY", R.drawable.bg_button_primary, Color.WHITE) {
                    message.setText("@${packet.sender} ")
                    message.setSelection(message.text?.length ?: 0)
                    message.requestFocus()
                    showStatus("Replying to ${packet.sender}", Status.ACTIVE)
                }, LinearLayout.LayoutParams(0, dp(36), 1f))
            }
            content.addView(btnRow, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(6) })
            content.addView(TextView(this@MainActivity).apply {
                text = "${packet.sender}  ·  ${packet.id.take(8)}  ·  hops: ${packet.hops.joinToString(" → ").ifBlank { "origin" }}"
                textSize = 9f
                setTextColor(bubbleSecondary)
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(2f, 1f)
            })
        }
        feed.addView(bubble, 0)
        showStatus(if (local) "Packet ${packet.id.take(8)} sent" else "Packet ${packet.id.take(8)} received via ${packet.hops.size} hops", Status.OK)
    }

    override fun delivery(id: String, state: String) {
        deliveryLabels[id]?.text = state
        val shortId = id.take(8)
        showStatus("Packet $shortId: $state", if (state == MessageStore.RELAYED) Status.OK else Status.ACTIVE)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleSendIntent(intent)
    }

    // The foreground service owns the radio lifecycle; closing the activity must not stop relaying.
    override fun onDestroy() { stopVoiceRecording(); speechRecognizer?.destroy(); tts?.stop(); tts?.shutdown(); whisperModel?.let { Whisper.releaseModel(it) }; whisperModel = null; mesh.removeListener(this); super.onDestroy() }
}
