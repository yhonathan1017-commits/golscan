package com.galzza.golscan.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Capa fisica de la linea K sobre un cable KKL 409.1 conectado por USB-OTG.
 *
 * El cable KKL es una interfaz "tonta": solo convierte la linea K del auto a un
 * puerto serie. Todo el protocolo (init lento, bloques, etc.) lo hacemos nosotros
 * por software, igual que lo hacia el programa del PC.
 *
 * Esta clase expone lo minimo que necesita el protocolo:
 *  - abrir/cerrar el puerto
 *  - cambiar la velocidad (para el init lento a 5 baudios y luego a 10400)
 *  - escribir y leer byte a byte
 *
 * OJO con el "eco local": como la linea K es un solo cable, todo lo que
 * transmitimos vuelve a aparecer en recepcion. El protocolo KWP1281 se encarga
 * de descartar ese eco; aqui solo movemos bytes.
 */
class UsbKLine(private val usbManager: UsbManager) {

    private var port: UsbSerialPort? = null
    private var connection: UsbDeviceConnection? = null

    val isOpen: Boolean get() = port != null

    /** Devuelve true si hay un cable KKL (CH340/FTDI/PL2303/CP210x) reconocido. */
    fun hasDevice(): Boolean =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).isNotEmpty()

    /**
     * Abre el primer cable serial que encuentre. La app ya pidio el permiso USB
     * antes de llamar aqui.
     */
    fun open(initialBaud: Int = 10400) {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        require(drivers.isNotEmpty()) { "No se encontro el cable KKL. Conectalo por OTG." }

        val driver = drivers.first()
        val conn = usbManager.openDevice(driver.device)
            ?: throw IllegalStateException("No se pudo abrir el cable (falta permiso USB?).")

        val p = driver.ports.first()
        p.open(conn)
        p.setParameters(initialBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // DTR/RTS activos: muchos cables alimentan el conversor con esto.
        try { p.dtr = true } catch (_: Exception) {}
        try { p.rts = true } catch (_: Exception) {}

        connection = conn
        port = p
    }

    fun setBaud(baud: Int) {
        port?.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    }

    fun write(byte: Int) {
        val p = port ?: throw IllegalStateException("Puerto cerrado")
        p.write(byteArrayOf(byte.toByte()), WRITE_TIMEOUT_MS)
    }

    fun write(bytes: ByteArray) {
        val p = port ?: throw IllegalStateException("Puerto cerrado")
        p.write(bytes, WRITE_TIMEOUT_MS)
    }

    /**
     * Lee un byte con tiempo limite. Devuelve -1 si no llego nada a tiempo.
     * Internamente reintenta porque port.read() puede devolver 0 bytes.
     */
    fun readByte(timeoutMs: Int): Int {
        val p = port ?: throw IllegalStateException("Puerto cerrado")
        val buf = ByteArray(1)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = p.read(buf, READ_CHUNK_TIMEOUT_MS)
            if (n > 0) return buf[0].toInt() and 0xFF
        }
        return -1
    }

    /** Vacia cualquier byte pendiente (ruido / ecos sobrantes). */
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

    companion object {
        const val WRITE_TIMEOUT_MS = 400
        const val READ_CHUNK_TIMEOUT_MS = 50
    }
}
