package com.example.sliwriter

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.sliwriter.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null

    private var pendingWriteDump: FlipperNfcData? = null
    private var pendingReadData: FlipperNfcData? = null
    private var pendingMode: Mode = Mode.IDLE

    // Special (factory) UID — stored LSB-first, persisted in SharedPreferences
    private var specialUidLsb: ByteArray? = null
    private val PREFS_NAME = "sli_writer_prefs"
    private val PREFS_KEY_SPECIAL_UID = "special_uid_hex"

    private enum class Mode {
        IDLE,
        WAITING_FOR_WRITE_TAG,
        WAITING_FOR_READ_TAG,
        WAITING_FOR_SAVE_SPECIAL_UID,
        WAITING_FOR_WRITE_SPECIAL_TAG,
    }

    // -------------------------------------------------------------------------
    // File pickers
    // -------------------------------------------------------------------------

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            appLog("File selection canceled")
            setStatus("Ready")
            pendingMode = Mode.IDLE
            pendingWriteDump = null
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}

        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readAllText() }
                ?: error("Unable to open selected file")
            val parsed = FlipperNfcParser.parse(text)
            pendingWriteDump = parsed
            // Mode set by caller (WAITING_FOR_WRITE_TAG or WAITING_FOR_WRITE_SPECIAL_TAG)
            appLog("Parsed: blocks=${parsed.blockCount} size=${parsed.blockSize} uid=${parsed.uid.toHexCompact()}")
            appLog("File: ${queryDisplayName(uri) ?: uri}")
            setStatus("Approach card...")
        } catch (t: Throwable) {
            pendingWriteDump = null
            pendingMode = Mode.IDLE
            setStatus("Error")
            appLog("Parse failed: ${t.message}")
        }
    }

    private val filePickerForSpecialWrite = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            appLog("File selection canceled")
            setStatus("Ready")
            pendingMode = Mode.IDLE
            pendingWriteDump = null
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}

        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readAllText() }
                ?: error("Unable to open selected file")
            val parsed = FlipperNfcParser.parse(text)
            pendingWriteDump = parsed
            pendingMode = Mode.WAITING_FOR_WRITE_SPECIAL_TAG
            appLog("Parsed: blocks=${parsed.blockCount} size=${parsed.blockSize} uid=${parsed.uid.toHexCompact()}")
            appLog("File: ${queryDisplayName(uri) ?: uri}")
            setStatus("Approach card (special write)...")
        } catch (t: Throwable) {
            pendingWriteDump = null
            pendingMode = Mode.IDLE
            setStatus("Error")
            appLog("Parse failed: ${t.message}")
        }
    }

    private val createReadFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val readData = pendingReadData
        pendingReadData = null
        if (uri == null) { appLog("Save canceled"); setStatus("Ready"); return@registerForActivityResult }
        if (readData == null) { appLog("Nothing to save"); setStatus("Error"); return@registerForActivityResult }
        try {
            val text = FlipperNfcSerializer.serialize(
                uid = readData.uid, data = readData.data,
                blockCount = readData.blockCount, blockSize = readData.blockSize,
            )
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: error("Unable to open destination")
            appLog("Saved: $uri")
            setStatus("Read success")
        } catch (t: Throwable) {
            appLog("Save failed: ${t.message}")
            setStatus("Save error")
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            setStatus("NFC not available")
            appLog("This device does not support NFC")
        } else {
            setStatus("Ready")
        }

        // Load persisted special UID
        loadSpecialUid()
        updateSpecialUidDisplay()

        // --- Normal Write ---
        binding.btnWrite.setOnClickListener {
            pendingWriteDump = null
            pendingMode = Mode.WAITING_FOR_WRITE_TAG
            filePicker.launch(arrayOf("*/*"))
        }

        // --- Read ---
        binding.btnRead.setOnClickListener {
            pendingMode = Mode.WAITING_FOR_READ_TAG
            setStatus("Read mode")
            appLog("Read mode — approach a readable tag")
        }

        // --- Save Special UID ---
        binding.btnSaveSpecialUid.setOnClickListener {
            pendingMode = Mode.WAITING_FOR_SAVE_SPECIAL_UID
            setStatus("Approach card to save UID...")
            appLog("Save Special UID — approach card")
        }

        // --- Write Special ---
        binding.btnWriteSpecial.setOnClickListener {
            if (specialUidLsb == null) {
                Toast.makeText(this, "No special UID saved — use Save Special UID first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            pendingWriteDump = null
            filePickerForSpecialWrite.launch(arrayOf("*/*"))
        }

        // --- Edit special UID (pencil icon) ---
        binding.btnEditSpecialUid.setOnClickListener { showEditSpecialUidDialog() }

        // --- Copy special UID (clipboard icon) ---
        binding.btnCopySpecialUid.setOnClickListener {
            val uid = specialUidLsb ?: return@setOnClickListener
            val uidMsb = uid.reversedArray()
            val text = uidMsb.toHexSpaced()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Special UID", text))
            Toast.makeText(this, "UID copied: $text", Toast.LENGTH_SHORT).show()
        }

        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        enableForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        disableForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // -------------------------------------------------------------------------
    // NFC intent dispatch
    // -------------------------------------------------------------------------

    private fun handleIntent(intent: Intent?) {
        val tag: Tag = intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return
        if (!tag.techList.orEmpty().contains(NfcV::class.java.name)) {
            appLog("Detected tag is not ISO15693 / NfcV")
            return
        }
        when (pendingMode) {
            Mode.WAITING_FOR_WRITE_TAG -> {
                val dump = pendingWriteDump ?: run {
                    appLog("Internal error: no pending write dump")
                    pendingMode = Mode.IDLE; return
                }
                doWrite(tag, dump)
            }
            Mode.WAITING_FOR_READ_TAG -> doRead(tag)
            Mode.WAITING_FOR_SAVE_SPECIAL_UID -> doSaveSpecialUid(tag)
            Mode.WAITING_FOR_WRITE_SPECIAL_TAG -> {
                val dump = pendingWriteDump ?: run {
                    appLog("Internal error: no pending write dump")
                    pendingMode = Mode.IDLE; return
                }
                doWriteSpecial(tag, dump)
            }
            Mode.IDLE -> appLog("Tag detected but no pending action")
        }
    }

    // -------------------------------------------------------------------------
    // Normal Write
    // -------------------------------------------------------------------------

    private fun doWrite(tag: Tag, nfcData: FlipperNfcData) {
        setStatus("Writing...")
        try {
            Iso15693Transport(tag).use { transport ->
                transport.connect()
                val currentUidMsb = tag.id.reversedArray()
                appLog("UID from Android: ${currentUidMsb.toHexCompact()}")

                appLog("SELECT...")
                transport.selectCard(currentUidMsb)
                Thread.sleep(5)

                appLog("Step 1: write ${nfcData.blockCount} blocks")
                transport.writeBlocks(nfcData.data, nfcData.blockCount, nfcData.blockSize) { b, frame ->
                    appLog("WRBL ${b.toString().padStart(2, '0')} -> ${frame.toHexSpaced()}")
                }
                appLog("Blocks written OK")

                val zeroUid = ByteArray(8)
                if (!nfcData.uid.contentEquals(zeroUid) && !nfcData.uid.contentEquals(currentUidMsb)) {
                    appLog("Step 2: write UID")
                    transport.writeUid(nfcData.uid) { appLog(it) }
                    appLog("UID written OK")
                } else {
                    appLog("Step 2: UID skip")
                }
            }
            setStatus("Write success")
            appLog("WRITE OK")
        } catch (t: Throwable) {
            setStatus("Write error")
            appLog("WRITE FAILED: ${t.message}")
        } finally {
            pendingMode = Mode.IDLE
            pendingWriteDump = null
        }
    }

    // -------------------------------------------------------------------------
    // Save Special UID
    // -------------------------------------------------------------------------

    private fun doSaveSpecialUid(tag: Tag) {
        setStatus("Reading UID...")
        try {
            // tag.id gives UID LSB-first (wire order) — store as-is
            val uidLsb = tag.id
            specialUidLsb = uidLsb
            saveSpecialUid()
            updateSpecialUidDisplay()
            val uidMsb = uidLsb.reversedArray()
            appLog("Special UID saved: ${uidMsb.toHexSpaced()} (MSB-first)")
            setStatus("Special UID saved")
        } catch (t: Throwable) {
            setStatus("Error")
            appLog("Save UID failed: ${t.message}")
        } finally {
            pendingMode = Mode.IDLE
        }
    }

    // -------------------------------------------------------------------------
    // Special Write
    // -------------------------------------------------------------------------

    private fun doWriteSpecial(tag: Tag, nfcData: FlipperNfcData) {
        val factoryUidLsb = specialUidLsb ?: run {
            appLog("No special UID saved")
            pendingMode = Mode.IDLE; return
        }
        setStatus("Writing (special)...")
        try {
            Iso15693Transport(tag).use { transport ->
                transport.connect()
                val currentUidMsb = tag.id.reversedArray()
                appLog("UID from Android: ${currentUidMsb.toHexCompact()}")
                FlipperNfcWriter.writeSpecial(
                    transport = transport,
                    currentUid = currentUidMsb,
                    factoryUidLsb = factoryUidLsb,
                    nfcData = nfcData,
                ) { appLog(it) }
            }
            setStatus("Write special success")
            appLog("WRITE SPECIAL OK")
        } catch (t: Throwable) {
            setStatus("Write special error")
            appLog("WRITE SPECIAL FAILED: ${t.message}")
        } finally {
            pendingMode = Mode.IDLE
            pendingWriteDump = null
        }
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    private fun doRead(tag: Tag) {
        setStatus("Reading...")
        val currentUidMsb = tag.id.reversedArray()
        appLog("UID from Android: ${currentUidMsb.toHexCompact()}")
        try {
            val data = Iso15693Transport(tag).use { transport ->
                transport.connect()
                FlipperNfcWriter.readWithUid(transport, currentUidMsb) { appLog(it) }
            }
            onReadCompleted(data)
        } catch (t: Throwable) {
            setStatus("Read error")
            appLog("READ FAILED: ${t.message}")
        } finally {
            pendingMode = Mode.IDLE
        }
    }

    private fun onReadCompleted(data: FlipperNfcData) {
        appLog("UID: ${data.uid.toHexSpaced()}")
        appLog("Data: ${data.data.toHexSpaced()}")
        pendingReadData = data
        val defaultName = "card_${data.uid.toHexCompact()}_${timestamp()}.nfc"
        createReadFile.launch(defaultName)
        setStatus("Choose save location")
    }

    // -------------------------------------------------------------------------
    // Special UID persistence & UI
    // -------------------------------------------------------------------------

    private fun saveSpecialUid() {
        val uid = specialUidLsb ?: return
        val hex = uid.toHexCompact()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREFS_KEY_SPECIAL_UID, hex).apply()
    }

    private fun loadSpecialUid() {
        val hex = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_KEY_SPECIAL_UID, null) ?: return
        if (hex.length == 16) {
            specialUidLsb = ByteArray(8) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }

    private fun updateSpecialUidDisplay() {
        val uid = specialUidLsb
        if (uid == null) {
            binding.layoutSpecialUid.visibility = View.GONE
            binding.txtSpecialUid.visibility = View.GONE
            binding.btnEditSpecialUid.visibility = View.GONE
            binding.btnCopySpecialUid.visibility = View.GONE
            binding.btnWriteSpecial.isEnabled = false
            binding.btnWriteSpecial.alpha = 0.4f
        } else {
            val uidMsb = uid.reversedArray()
            binding.txtSpecialUid.text = "Special UID: ${uidMsb.toHexSpaced()}"
            binding.layoutSpecialUid.visibility = View.VISIBLE
            binding.txtSpecialUid.visibility = View.VISIBLE
            binding.btnEditSpecialUid.visibility = View.VISIBLE
            binding.btnCopySpecialUid.visibility = View.VISIBLE
            binding.btnWriteSpecial.isEnabled = true
            binding.btnWriteSpecial.alpha = 1.0f
        }
    }

    private fun showEditSpecialUidDialog() {
        val current = specialUidLsb?.reversedArray()?.toHexCompact() ?: ""
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(current)
            hint = "E0040350AABBCCDD"
            filters = arrayOf(InputFilter.LengthFilter(16))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(editText)
            addView(TextView(this@MainActivity).apply {
                text = "Enter 16 hex chars (MSB-first, no spaces)"
                textSize = 11f
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Edit Special UID")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val hex = editText.text.toString().trim().uppercase()
                if (hex.length == 16 && hex.all { it.isDigit() || it in 'A'..'F' }) {
                    // Input is MSB-first — store LSB-first
                    val msbBytes = ByteArray(8) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                    specialUidLsb = msbBytes.reversedArray()
                    saveSpecialUid()
                    updateSpecialUidDisplay()
                    appLog("Special UID updated manually: $hex")
                } else {
                    Toast.makeText(this, "Invalid UID — must be 16 hex characters", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // NFC foreground dispatch
    // -------------------------------------------------------------------------

    private fun enableForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )
        val filters = arrayOf(android.content.IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED))
        val techLists = arrayOf(arrayOf(NfcV::class.java.name))
        adapter.enableForegroundDispatch(this, pendingIntent, filters, techLists)
    }

    private fun disableForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(this)
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return null
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun appLog(s: String) {
        binding.txtLog.append("[SLI_Writer] $s\n")
        binding.txtLog.post {
            (binding.txtLog.parent as? android.widget.ScrollView)?.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun setStatus(s: String) {
        binding.txtStatus.text = "Status: $s"
    }
}
