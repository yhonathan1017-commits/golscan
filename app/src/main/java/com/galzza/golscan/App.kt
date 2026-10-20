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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =====================================================================
//  GolScan - diagnostico KWP1281 para VW Gol (Magneti Marelli) por cable KKL
//  Todo en un solo archivo para facilitar la subida a GitHub.
//  Construido sobre documentacion publica de KWP1281. No usa software de terceros.
// =====================================================================

// ---------------------- Modelos de datos ----------------------

data class FaultCode(val code: Int, val elaboration: Int, val text: String) {
    val statusText: String
        get() = if (elaboration and 0x80 != 0) "intermitente" else "presente"
}

data class Measurement(val formula: Int, val a: Int, val b: Int, val value: String)

data class EcuInfo(val keyWordLow: Int, val keyWordHigh: Int, val idStrings: List<String>)

// ---------------------- Formulas de medicion ----------------------

object Formulas {
    fun apply(formula: Int, a: Int, b: Int): String = when (formula) {
        1 -> "%.0f rpm".format(0.2 * a * b)
        2 -> "%.1f %%".format(a * b / 128.0)
        3 -> "%.3f".format(0.002 * a * b)
        4 -> "%d gr".format((b - 127) * a / 128)
        5 -> "%.1f C".format(a * (b - 100) / 10.0)
        6 -> "%.3f V".format(0.001 * a * b)
        7 -> "%.0f km/h".format(0.01 * a * b)
        8 -> "%.1f".format(0.1 * a * b)
        9 -> "%.1f gr".format((b - 127) * a / 50.0)
        10 -> if (b == 0) "frio" else "caliente"
        12 -> "%.3f Ohm".format(0.001 * a * b)
        14 -> "%.3f bar".format(0.005 * a * b)
        15 -> "%.0f ms".format(0.01 * a * b)
        18 -> "%.1f A".format(0.1 * a * b)
        21 -> "%.3f V".format(a * b / 400.0)
        23 -> "%.3f %%".format(b * a / 256.0)
        25 -> "%.3f g/s".format(a * b / 182.04)
        else -> "f%d a=%d b=%d".format(formula, a, b)
    }
}

// ---------------------- Descripciones de codigos ----------------------

object FaultText {
    private val table = mapOf(
        0 to "Sin fallas",
        513 to "Sensor de regimen del motor (rpm) -G28-",
        514 to "Sensor Hall -G40-",
        516 to "Sensor de posicion de mariposa -G69-",
        517 to "Temperatura del refrigerante -G62-",
        519 to "Temperatura del aire de admision -G42-",
        520 to "Medidor de masa de aire / MAP -G71-",
        522 to "Temperatura del refrigerante -G62-",
        524 to "Sensor de detonacion 1 -G61-",
        525 to "Sonda lambda -G39-",
        533 to "Regulacion de ralenti (mezcla)",
        537 to "Regulacion lambda, limite alcanzado",
        553 to "Caudalimetro / medidor de aire",
        558 to "Inyector cil.1 -N30-",
        559 to "Inyector cil.2 -N31-",
        560 to "Inyector cil.3 -N32-",
        561 to "Mezcla adaptativa (limite)",
        562 to "Inyector cil.4 -N33-",
        624 to "Compresor del aire acondicionado",
        625 to "Senal de velocidad -G68-",
        750 to "Lampara de averia (MIL)"
    )
    fun describe(code: Int): String = table[code] ?: "Codigo VAG %05d (desconocido)".format(code)
}

// ---------------------- Capa fisica: cable KKL por USB ----------------------

class UsbKLine(private val usbManager: UsbManager) {
    private var port: UsbSerialPort? = null
    private var connection: android.hardware.usb.UsbDeviceConnection? = null

    fun open(initialBaud: Int) {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        require(drivers.isNotEmpty()) { "No se encontro el cable KKL. Conectalo por OTG." }
        val driver = drivers.first()
        val conn = usbManager.openDevice(driver.device)
            ?: throw IllegalStateException("No se pudo abrir el cable (falta permiso USB?).")
        val p = driver.ports.first()
        p.open(conn)
        p.setParameters(initialBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        try { p.dtr = true } catch (_: Exception) {}
        try { p.rts = true } catch (_: Exception) {}
        connection = conn
        port = p
    }

    fun setBaud(baud: Int) {
        port?.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    }

    fun setBreak(on: Boolean) {
        port?.setBreak(on)
    }

    fun write(byte: Int) {
        val p = port ?: throw IllegalStateException("Puerto cerrado")
        p.write(byteArrayOf(byte.toByte()), 400)
    }

    fun readByte(timeoutMs: Int): Int {
        val p = port ?: throw IllegalStateException("Puerto cerrado")
        val buf = ByteArray(1)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = p.read(buf, 50)
            if (n > 0) return buf[0].toInt() and 0xFF
        }
        return -1
    }

    fun drain(timeoutMs: Int = 60) {
        val p = port ?: return
        val buf = ByteArray(64)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (p.read(buf, 20) <= 0) break
        }
    }

    fun close() {
        try { port?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        port = null
        connection = null
    }
}

// ---------------------- Protocolo KWP1281 ----------------------

class Kwp1281(private val kline: UsbKLine, private val log: (String) -> Unit) {
    private var counter = 0
    @Volatile var connected = false
        private set

    private object T {
        const val ACK = 0x09
        const val READ_FAULTS = 0x07
        const val FAULTS_RESPONSE = 0xFC
        const val CLEAR_FAULTS = 0x05
        const val READ_GROUP = 0x29
        const val GROUP_RESPONSE = 0xE7
        const val ASCII = 0xF6
        const val END_OUTPUT = 0x06
    }

    private class Block(val counter: Int, val title: Int, val data: IntArray)

    // Comunicacion continua (keep-alive): KWP1281 corta la sesion si el tester
    // no contesta cada bloque en ~1s. Por eso mantenemos un hilo que intercambia
    // bloques sin parar, y las peticiones se montan sobre ese flujo.
    @Volatile private var running = false
    private var commThread: Thread? = null
    private val lock = Object()
    private var pendingTitle = -1
    private var pendingData = ByteArray(0)
    private var reqState = 0   // 0=idle, 1=pendiente, 2=enviada, 3=lista
    private val collected = ArrayList<Block>()
    @Volatile private var fatalError: String? = null

    fun connect(address: Int = 0x01): EcuInfo {
        var lastSync = -1
        for (attempt in 1..4) {
            kline.drain()
            slowInit(address)
            kline.setBaud(COMM_BAUD)
            val sync = kline.readByte(2500)
            lastSync = sync
            log("Sync: 0x%02X".format(sync))
            if (sync == 0x55) {
                val kb1 = kline.readByte(300)
                val kb2 = kline.readByte(300)
                require(kb1 >= 0 && kb2 >= 0) { "No llegaron los key bytes." }
                log("Key bytes: 0x%02X 0x%02X".format(kb1, kb2))
                Thread.sleep(W4_MS.toLong())
                sendByteRaw(kb2.inv() and 0xFF)
                counter = 1
                connected = true
                running = true
                fatalError = null
                commThread = Thread { commLoop() }.apply { isDaemon = true; start() }
                return EcuInfo(kb1, kb2, emptyList())
            }
            log("Reintentando init (intento %d)...".format(attempt))
            Thread.sleep(400)
        }
        throw IllegalStateException("No llego 0x55 (ultimo 0x%02X) tras varios intentos. Revisa contacto o prueba 9600 baud.".format(lastSync))
    }

    private fun commLoop() {
        try {
            while (running) {
                val block = receiveBlock() ?: throw IllegalStateException("La ECU dejo de responder")
                synchronized(lock) {
                    if (reqState == 2) {
                        if (block.title == T.ACK) {
                            reqState = 3
                            lock.notifyAll()
                        } else {
                            collected.add(block)
                        }
                    } else if (block.title == T.ASCII) {
                        val s = String(block.data.map { (it and 0xFF).toByte() }.toByteArray(), Charsets.ISO_8859_1)
                        log("ID: " + s.trim())
                    }
                }
                var toSend = -1
                var dataToSend = ByteArray(0)
                synchronized(lock) {
                    if (reqState == 1) {
                        toSend = pendingTitle
                        dataToSend = pendingData
                        reqState = 2
                    }
                }
                Thread.sleep(W4_MS.toLong())
                if (toSend != -1) sendBlock(toSend, dataToSend) else sendAck()
            }
        } catch (e: Exception) {
            fatalError = e.message
            log("Sesion caida: " + e.message)
            connected = false
            running = false
            synchronized(lock) { reqState = 3; lock.notifyAll() }
        }
    }

    private fun request(title: Int, data: ByteArray, timeoutMs: Long): List<Block> {
        check(connected) { "No conectado" }
        synchronized(lock) {
            collected.clear()
            pendingTitle = title
            pendingData = data
            reqState = 1
            val deadline = System.currentTimeMillis() + timeoutMs
            while (reqState != 3 && System.currentTimeMillis() < deadline) {
                lock.wait(200)
            }
            val result = ArrayList(collected)
            reqState = 0
            pendingTitle = -1
            fatalError?.let { throw IllegalStateException(it) }
            return result
        }
    }

    fun disconnect() {
        running = false
        try { commThread?.join(1500) } catch (_: Exception) {}
        connected = false
    }

    fun readFaults(): List<FaultCode> {
        val blocks = request(T.READ_FAULTS, ByteArray(0), 4000)
        val faults = ArrayList<FaultCode>()
        for (block in blocks) {
            if (block.title != T.FAULTS_RESPONSE) continue
            var i = 0
            while (i + 2 < block.data.size) {
                val hi = block.data[i] and 0xFF
                val lo = block.data[i + 1] and 0xFF
                val elab = block.data[i + 2] and 0xFF
                val code = (hi shl 8) or lo
                if (code != 0xFFFF && code != 0x0000) faults.add(FaultCode(code, elab, FaultText.describe(code)))
                i += 3
            }
        }
        return faults
    }

    fun clearFaults(): Boolean {
        request(T.CLEAR_FAULTS, ByteArray(0), 4000)
        return fatalError == null
    }

    fun readGroup(group: Int): List<Measurement> {
        val blocks = request(T.READ_GROUP, byteArrayOf((group and 0xFF).toByte()), 4000)
        val out = ArrayList<Measurement>()
        for (block in blocks) {
            if (block.title != T.GROUP_RESPONSE) continue
            var i = 0
            while (i + 2 < block.data.size) {
                val f = block.data[i] and 0xFF
                val a = block.data[i + 1] and 0xFF
                val b = block.data[i + 2] and 0xFF
                out.add(Measurement(f, a, b, Formulas.apply(f, a, b)))
                i += 3
            }
        }
        return out
    }

    private fun slowInit(address: Int) {
        log("Init lento 5 baud (bit-bang), dir 0x%02X ...".format(address))
        val bitMs = 200L
        val dataBits = IntArray(7) { (address shr it) and 1 }
        val ones = dataBits.sum()
        val parity = if (ones % 2 == 0) 1 else 0
        try {
            kline.setBreak(false)
            Thread.sleep(300)
            kline.setBreak(true)
            Thread.sleep(bitMs)
            for (b in dataBits) {
                kline.setBreak(b == 0)
                Thread.sleep(bitMs)
            }
            kline.setBreak(parity == 0)
            Thread.sleep(bitMs)
            kline.setBreak(false)
            Thread.sleep(bitMs)
        } catch (e: Exception) {
            throw IllegalStateException("El cable no soporta break para el init: " + e.message)
        }
        kline.drain(40)
    }

    private fun sendAck() = sendBlock(T.ACK, ByteArray(0))

    private fun sendBlock(title: Int, data: ByteArray) {
        counter = (counter + 1) and 0xFF
        val len = data.size + 3
        val bytes = ArrayList<Int>()
        bytes.add(len); bytes.add(counter); bytes.add(title)
        data.forEach { bytes.add(it.toInt() and 0xFF) }
        for (b in bytes) sendByteHandshaked(b)
        sendByteRaw(0x03)
    }

    private fun receiveBlock(): Block? {
        val len = recvByteHandshaked() ?: return null
        val cnt = recvByteHandshaked() ?: return null
        counter = cnt
        val title = recvByteHandshaked() ?: return null
        val dataLen = len - 3
        val data = IntArray(if (dataLen > 0) dataLen else 0)
        for (i in data.indices) data[i] = recvByteHandshaked() ?: return null
        val etx = kline.readByte(1200)
        if (etx != 0x03) log("Aviso: fin de bloque 0x%02X".format(etx))
        return Block(cnt, title, data)
    }

    private fun sendByteHandshaked(b: Int) {
        sendByteRaw(b)
        val comp = kline.readByte(1200)
        val expected = b.inv() and 0xFF
        if (comp != expected)
            log("HS TX: envie 0x%02X, esperaba 0x%02X, llego 0x%02X".format(b, expected, comp))
    }

    private fun sendByteRaw(b: Int) {
        kline.write(b)
        Thread.sleep(5)
    }

    private fun recvByteHandshaked(): Int? {
        val b = kline.readByte(1200)
        if (b < 0) return null
        Thread.sleep(5)
        sendByteRaw(b.inv() and 0xFF)
        return b
    }

    companion object {
        const val COMM_BAUD = 10400
        const val W4_MS = 55
    }
}

// ---------------------- Interfaz (construida en codigo) ----------------------

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var kline: UsbKLine
    private var kwp: Kwp1281? = null

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnFaults: Button
    private lateinit var btnClear: Button
    private lateinit var btnGroup: Button
    private lateinit var groupInput: EditText

    private val ACTION_USB = "com.galzza.golscan.USB_PERMISSION"
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) connectInternal() else log("Permiso USB denegado.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        kline = UsbKLine(usbManager)
        setContentView(buildUi())

        val filter = IntentFilter(ACTION_USB)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
        log("Listo. Conecta el cable KKL por OTG y pulsa Conectar.")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        kline.close()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val title = TextView(this).apply {
            text = "GolScan - KWP1281"
            textSize = 20f
        }
        statusView = TextView(this).apply {
            text = "Desconectado"
            setPadding(0, dp(4), 0, dp(8))
        }
        btnConnect = Button(this).apply { text = "Conectar"; setOnClickListener { requestPermissionAndConnect() } }
        btnDisconnect = Button(this).apply { text = "Desconectar"; isEnabled = false; setOnClickListener { doDisconnect() } }
        btnFaults = Button(this).apply { text = "Leer fallas"; isEnabled = false; setOnClickListener { doReadFaults() } }
        btnClear = Button(this).apply { text = "Borrar fallas"; isEnabled = false; setOnClickListener { doClearFaults() } }

        val rowConn = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowConn.addView(btnConnect, lpWeight())
        rowConn.addView(btnDisconnect, lpWeight())

        val rowFault = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowFault.addView(btnFaults, lpWeight())
        rowFault.addView(btnClear, lpWeight())

        groupInput = EditText(this).apply {
            hint = "Grupo (1-255)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        btnGroup = Button(this).apply { text = "Leer grupo"; isEnabled = false; setOnClickListener { doReadGroup() } }
        val rowGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rowGroup.addView(groupInput, lpWeight())
        rowGroup.addView(btnGroup, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        logView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { setBackgroundColor(0x11000000) }
        logScroll.addView(logView)

        root.addView(title)
        root.addView(statusView)
        root.addView(rowConn)
        root.addView(rowFault)
        root.addView(rowGroup)
        root.addView(logScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun lpWeight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun requestPermissionAndConnect() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) { log("No veo ningun cable KKL. Revisa el OTG."); return }
        val device: UsbDevice = drivers.first().device
        if (usbManager.hasPermission(device)) { connectInternal(); return }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB).setPackage(packageName), flags)
        usbManager.requestPermission(device, pi)
    }

    private fun connectInternal() {
        setBusy(true)
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    kline.open(Kwp1281.COMM_BAUD)
                    val k = Kwp1281(kline) { m -> runOnUiThread { log(m) } }
                    val i = k.connect(0x01)
                    kwp = k
                    i
                }
                log("Conectado. Key: 0x%02X 0x%02X".format(info.keyWordLow, info.keyWordHigh))
                info.idStrings.forEach { if (it.isNotBlank()) log("  $it") }
                statusView.text = "Conectado (motor)"
                setConnectedUi(true)
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
                try { kline.close() } catch (_: Exception) {}
                setConnectedUi(false)
            } finally { setBusy(false) }
        }
    }

    private fun doDisconnect() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try { kwp?.disconnect() } catch (_: Exception) {}
                kline.close()
            }
            kwp = null
            statusView.text = "Desconectado"
            setConnectedUi(false)
            log("Sesion cerrada.")
        }
    }

    private fun doReadFaults() {
        val k = kwp ?: return
        setBusy(true)
        lifecycleScope.launch {
            try {
                val faults = withContext(Dispatchers.IO) { k.readFaults() }
                if (faults.isEmpty()) log("Sin codigos de falla. OK")
                else {
                    log("---- ${faults.size} falla(s) ----")
                    faults.forEach { log("  %05d  %s  [%s]".format(it.code, it.text, it.statusText)) }
                }
            } catch (e: Exception) { log("ERROR leyendo fallas: ${e.message}") }
            finally { setBusy(false) }
        }
    }

    private fun doClearFaults() {
        val k = kwp ?: return
        setBusy(true)
        lifecycleScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { k.clearFaults() }
                log(if (ok) "Fallas borradas. OK" else "La ECU no confirmo el borrado.")
            } catch (e: Exception) { log("ERROR borrando: ${e.message}") }
            finally { setBusy(false) }
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
            } catch (e: Exception) { log("ERROR grupo: ${e.message}") }
            finally { setBusy(false) }
        }
    }

    private fun setConnectedUi(connected: Boolean) {
        btnConnect.isEnabled = !connected
        btnDisconnect.isEnabled = connected
        btnFaults.isEnabled = connected
        btnClear.isEnabled = connected
        btnGroup.isEnabled = connected
    }

    private fun setBusy(busy: Boolean) {
        if (busy) {
            btnConnect.isEnabled = false
            btnFaults.isEnabled = false
            btnClear.isEnabled = false
            btnGroup.isEnabled = false
        } else if (kwp != null) setConnectedUi(true) else setConnectedUi(false)
    }

    private fun log(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg\n"
        logView.append(line)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
