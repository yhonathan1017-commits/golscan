package com.galzza.golscan

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.galzza.golscan.kwp.Kwp1281
import com.galzza.golscan.usb.UsbKLine
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var kline: UsbKLine
    private var kwp: Kwp1281? = null

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnReadFaults: Button
    private lateinit var btnClearFaults: Button
    private lateinit var btnReadGroup: Button
    private lateinit var groupInput: EditText

    private val ACTION_USB_PERMISSION = "com.galzza.golscan.USB_PERMISSION"
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) connectInternal()
                else log("Permiso USB denegado.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        kline = UsbKLine(usbManager)

        status = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnReadFaults = findViewById(R.id.btnReadFaults)
        btnClearFaults = findViewById(R.id.btnClearFaults)
        btnReadGroup = findViewById(R.id.btnReadGroup)
        groupInput = findViewById(R.id.groupInput)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        btnConnect.setOnClickListener { requestPermissionAndConnect() }
        btnDisconnect.setOnClickListener { doDisconnect() }
        btnReadFaults.setOnClickListener { doReadFaults() }
        btnClearFaults.setOnClickListener { doClearFaults() }
        btnReadGroup.setOnClickListener { doReadGroup() }

        log("Listo. Conecta el cable KKL por OTG y pulsa Conectar.")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        kline.close()
    }

    // ---------------- Conexion ----------------

    private fun requestPermissionAndConnect() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            log("No veo ningun cable KKL. Revisa el OTG.")
            return
        }
        val device: UsbDevice = drivers.first().device
        if (usbManager.hasPermission(device)) {
            connectInternal()
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
        )
        usbManager.requestPermission(device, pi)
    }

    private fun connectInternal() {
        setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    kline.open(Kwp1281.COMM_BAUD)
                    val k = Kwp1281(kline) { msg -> runOnUiThread { log(msg) } }
                    val info = k.connect(0x01)
                    kwp = k
                    runOnUiThread {
                        log("Conectado. Key: 0x%02X 0x%02X".format(info.keyWordLow, info.keyWordHigh))
                        info.idStrings.forEach { if (it.isNotBlank()) log("  $it") }
                    }
                }
                status.text = "Conectado (motor)"
                setConnectedUi(true)
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
                try { kline.close() } catch (_: Exception) {}
                setConnectedUi(false)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun doDisconnect() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try { kwp?.disconnect() } catch (_: Exception) {}
                kline.close()
            }
            kwp = null
            status.text = "Desconectado"
            setConnectedUi(false)
            log("Sesion cerrada.")
        }
    }

    // ---------------- Acciones ----------------

    private fun doReadFaults() {
        val k = kwp ?: return
        setBusy(true)
        lifecycleScope.launch {
            try {
                val faults = withContext(Dispatchers.IO) { k.readFaults() }
                if (faults.isEmpty()) log("Sin codigos de falla. ✔")
                else {
                    log("---- ${faults.size} falla(s) ----")
                    faults.forEach { log("  %05d  %s  [%s]".format(it.code, it.text, it.statusText)) }
                }
            } catch (e: Exception) {
                log("ERROR leyendo fallas: ${e.message}")
            } finally { setBusy(false) }
        }
    }

    private fun doClearFaults() {
        val k = kwp ?: return
        setBusy(true)
        lifecycleScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { k.clearFaults() }
                log(if (ok) "Fallas borradas. ✔" else "La ECU no confirmo el borrado.")
            } catch (e: Exception) {
                log("ERROR borrando: ${e.message}")
            } finally { setBusy(false) }
        }
    }

    private fun doReadGroup() {
        val k = kwp ?: return
        val g = groupInput.text.toString().toIntOrNull()
        if (g == null || g !in 1..255) { log("Grupo invalido (1-255)."); return }
        setBusy(true)
        lifecycleScope.launch {
            try {
                val m = withContext(Dispatchers.IO) { k.readGroup(g) }
                if (m.isEmpty()) log("Grupo $g: sin datos.")
                else {
                    log("---- Grupo $g ----")
                    m.forEachIndexed { i, v -> log("  [${i + 1}] ${v.value}") }
                }
            } catch (e: Exception) {
                log("ERROR grupo: ${e.message}")
            } finally { setBusy(false) }
        }
    }

    // ---------------- UI helpers ----------------

    private fun setConnectedUi(connected: Boolean) {
        btnConnect.isEnabled = !connected
        btnDisconnect.isEnabled = connected
        btnReadFaults.isEnabled = connected
        btnClearFaults.isEnabled = connected
        btnReadGroup.isEnabled = connected
    }

    private fun setBusy(busy: Boolean) {
        if (busy) {
            btnConnect.isEnabled = false
            btnReadFaults.isEnabled = false
            btnClearFaults.isEnabled = false
            btnReadGroup.isEnabled = false
        } else if (kwp != null) {
            setConnectedUi(true)
        } else {
            setConnectedUi(false)
        }
    }

    private fun log(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg\n"
        logView.append(line)
        (logView.parent as? android.view.View)?.post {
            findViewById<android.widget.ScrollView>(R.id.logScroll)
                .fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
