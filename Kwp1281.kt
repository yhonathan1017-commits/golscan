package com.galzza.golscan.kwp

import com.galzza.golscan.usb.UsbKLine

/**
 * Implementacion del protocolo KWP1281 (KW1281) de VAG sobre linea K.
 *
 * KWP1281 es el protocolo viejo que usan las ECU VAG anteriores al OBD-II real,
 * como la Magneti Marelli del Gol 2002. Esta documentado publicamente desde hace
 * anios por proyectos abiertos; esta implementacion parte de esa documentacion,
 * no del codigo de ningun programa comercial.
 *
 * Resumen del protocolo:
 *  1) Init lento (5 baudios): se envia la direccion del modulo (0x01 = motor).
 *  2) La ECU responde a 10400 baud con: 0x55, key byte 1, key byte 2.
 *  3) Respondemos con el complemento (NOT) del key byte 2.
 *  4) A partir de ahi, todo son "bloques" intercambiados por turnos.
 *
 * Cada bloque:   [LEN][CONTADOR][TITULO][DATOS...][0x03]
 *   - LEN cuenta todo lo que viene despues del propio LEN (contador+titulo+datos+ETX).
 *   - El contador se incrementa en cada bloque.
 *   - Cada byte se confirma con su complemento (0xFF - byte), byte a byte,
 *     EXCEPTO el 0x03 final, que marca fin de bloque y no se confirma.
 *   - Como la linea K es un solo cable, al transmitir recibimos el eco de lo
 *     que mandamos: hay que descartarlo antes de leer la confirmacion.
 *
 * NOTA IMPORTANTE: los tiempos (W1..W4) y algunos detalles del init lento suelen
 * necesitar ajuste fino contra el auto real. Los valores aqui son los tipicos.
 */
class Kwp1281(
    private val kline: UsbKLine,
    private val log: (String) -> Unit
) {
    private var counter: Int = 0
    var connected: Boolean = false
        private set

    // ---- Titulos de bloque (los que usamos) ----
    private object Title {
        const val ACK = 0x09              // bloque vacio para mantener la sesion
        const val READ_FAULTS = 0x07      // pedir codigos de falla
        const val FAULTS_RESPONSE = 0xFC  // respuesta con codigos de falla
        const val CLEAR_FAULTS = 0x05     // borrar codigos de falla
        const val READ_GROUP = 0x29       // pedir grupo de medicion
        const val GROUP_RESPONSE = 0xE7   // respuesta con 4 mediciones
        const val ASCII = 0xF6            // datos ASCII (identificacion de la ECU)
        const val END_OUTPUT = 0x06       // despedida / cerrar sesion
        const val NO_ACK = 0x0A           // "no reconocido"
    }

    // =====================================================================
    //  CONEXION
    // =====================================================================

    /**
     * Conecta con un modulo. address 0x01 = motor (lo habitual en el Gol).
     * Devuelve la info de identificacion que manda la ECU al conectar.
     */
    fun connect(address: Int = 0x01): EcuInfo {
        kline.drain()
        slowInit(address)

        // La ECU contesta a la velocidad de comunicacion.
        kline.setBaud(COMM_BAUD)

        val sync = kline.readByte(W_SYNC_TIMEOUT)
        log("Sync recibido: 0x%02X".format(sync))
        require(sync == 0x55) { "No llego el byte de sincronizacion 0x55 (llego 0x%02X). Revisa cable/contacto.".format(sync) }

        val kb1 = kline.readByte(300)
        val kb2 = kline.readByte(300)
        require(kb1 >= 0 && kb2 >= 0) { "No llegaron los key bytes." }
        log("Key bytes: 0x%02X 0x%02X".format(kb1, kb2))

        // W4: esperar y responder con el complemento del key byte 2.
        Thread.sleep(W4_MS.toLong())
        sendByteRaw(kb2.inv() and 0xFF)

        counter = 1
        connected = true

        // Tras el handshake, la ECU envia varios bloques ASCII de identificacion.
        val ids = mutableListOf<String>()
        while (true) {
            val block = receiveBlock() ?: break
            when (block.title) {
                Title.ASCII -> {
                    val s = String(block.data.map { (it and 0xFF).toByte() }.toByteArray(), Charsets.ISO_8859_1)
                    ids.add(s.trim())
                    log("ID: $s")
                    sendAck()
                }
                Title.ACK -> { break } // la ECU quedo lista esperando ordenes
                else -> {
                    log("Bloque inicial titulo 0x%02X".format(block.title))
                    sendAck()
                }
            }
        }
        return EcuInfo(kb1, kb2, ids)
    }

    fun disconnect() {
        if (!connected) return
        try {
            sendBlock(Title.END_OUTPUT, byteArrayOf())
        } catch (_: Exception) {}
        connected = false
    }

    // =====================================================================
    //  INIT LENTO (5 baudios)
    // =====================================================================

    /**
     * Envia la direccion del modulo a 5 baudios. Con un cable KKL "tonto" el
     * truco habitual es poner el puerto a 5 baudios y mandar el byte de direccion;
     * el patron de bits a esa velocidad reproduce la forma de onda del init lento.
     *
     * Si no engancha, aqui es donde hay que afinar (formato de bits / paridad).
     */
    private fun slowInit(address: Int) {
        log("Init lento, direccion 0x%02X ...".format(address))
        kline.setBaud(5)
        kline.write(address and 0xFF)
        // A 5 baudios el byte tarda ~2 segundos en salir completo.
        Thread.sleep(2200)
        kline.drain(30)
    }

    // =====================================================================
    //  LECTURA DE FALLAS
    // =====================================================================

    fun readFaults(): List<FaultCode> {
        check(connected) { "No conectado" }
        sendBlock(Title.READ_FAULTS, byteArrayOf())

        val faults = mutableListOf<FaultCode>()
        while (true) {
            val block = receiveBlock() ?: break
            if (block.title == Title.FAULTS_RESPONSE) {
                // Cada falla ocupa 3 bytes: codigo_hi, codigo_lo, elaboracion.
                var i = 0
                while (i + 2 < block.data.size) {
                    val hi = block.data[i] and 0xFF
                    val lo = block.data[i + 1] and 0xFF
                    val elab = block.data[i + 2] and 0xFF
                    val code = (hi shl 8) or lo
                    if (code != 0xFFFF && code != 0x0000) {
                        faults.add(FaultCode(code, elab, FaultText.describe(code)))
                    }
                    i += 3
                }
                sendAck()
                // La ECU puede mandar la lista en varios bloques; seguimos pidiendo
                // con ACK hasta que conteste con un ACK propio.
            } else if (block.title == Title.ACK) {
                break
            } else {
                sendAck()
                break
            }
        }
        return faults
    }

    fun clearFaults(): Boolean {
        check(connected) { "No conectado" }
        sendBlock(Title.CLEAR_FAULTS, byteArrayOf())
        val block = receiveBlock() ?: return false
        return block.title == Title.ACK
    }

    // =====================================================================
    //  GRUPOS DE MEDICION
    // =====================================================================

    fun readGroup(group: Int): List<Measurement> {
        check(connected) { "No conectado" }
        sendBlock(Title.READ_GROUP, byteArrayOf((group and 0xFF).toByte()))
        val block = receiveBlock() ?: return emptyList()
        if (block.title != Title.GROUP_RESPONSE) {
            sendAck()
            return emptyList()
        }
        val out = mutableListOf<Measurement>()
        var i = 0
        while (i + 2 < block.data.size) {
            val formula = block.data[i] and 0xFF
            val a = block.data[i + 1] and 0xFF
            val b = block.data[i + 2] and 0xFF
            out.add(Measurement(formula, a, b, Formulas.apply(formula, a, b)))
            i += 3
        }
        sendAck()
        return out
    }

    /** Mantiene viva la sesion cuando no pedimos nada. */
    fun keepAlive() {
        if (connected) sendAck()
    }

    // =====================================================================
    //  NUCLEO: ENVIO Y RECEPCION DE BLOQUES CON HANDSHAKE
    // =====================================================================

    private class Block(val counter: Int, val title: Int, val data: IntArray)

    private fun sendAck() = sendBlock(Title.ACK, byteArrayOf())

    /**
     * Envia un bloque completo con el handshake byte-a-byte.
     * El 0x03 final se envia pero NO se espera su complemento.
     */
    private fun sendBlock(title: Int, data: ByteArray) {
        counter = (counter + 1) and 0xFF
        val len = data.size + 3  // contador + titulo + ETX
        val bytes = ArrayList<Int>()
        bytes.add(len)
        bytes.add(counter)
        bytes.add(title)
        data.forEach { bytes.add(it.toInt() and 0xFF) }
        // todos estos se confirman...
        for (b in bytes) sendByteHandshaked(b)
        // ...y el ETX se manda "crudo", sin esperar complemento.
        sendByteRaw(0x03)
    }

    /**
     * Recibe un bloque completo. Devuelve null si hubo timeout.
     * Por cada byte recibido devolvemos su complemento, salvo el 0x03 final.
     */
    private fun receiveBlock(): Block? {
        val len = recvByteHandshaked() ?: return null
        val cnt = recvByteHandshaked() ?: return null
        counter = cnt // sincronizamos nuestro contador con el de la ECU
        val title = recvByteHandshaked() ?: return null

        val dataLen = len - 3
        val data = IntArray(if (dataLen > 0) dataLen else 0)
        for (i in 0 until data.size) {
            data[i] = recvByteHandshaked() ?: return null
        }
        // El ETX llega sin que tengamos que confirmarlo.
        val etx = kline.readByte(BYTE_TIMEOUT)
        discardEcho() // por si acaso
        if (etx != 0x03) {
            log("Aviso: fin de bloque inesperado 0x%02X".format(etx))
        }
        return Block(cnt, title, data)
    }

    // ---- envio de un byte que SI espera complemento ----
    private fun sendByteHandshaked(b: Int) {
        sendByteRaw(b)
        val comp = kline.readByte(BYTE_TIMEOUT)
        val expected = b.inv() and 0xFF
        if (comp != expected) {
            log("Handshake TX: enviado 0x%02X, esperaba 0x%02X, llego 0x%02X".format(b, expected, comp))
        }
    }

    // ---- envio crudo: escribe el byte y descarta su propio eco ----
    private fun sendByteRaw(b: Int) {
        kline.write(b)
        // descartar el eco local del byte que acabamos de transmitir
        val echo = kline.readByte(BYTE_TIMEOUT)
        if (echo != b) {
            // en algunos cables no hay eco; no es fatal
        }
        Thread.sleep(W_INTERBYTE_MS.toLong())
    }

    // ---- recepcion de un byte que debemos confirmar con su complemento ----
    private fun recvByteHandshaked(): Int? {
        val b = kline.readByte(BYTE_TIMEOUT)
        if (b < 0) return null
        Thread.sleep(W_INTERBYTE_MS.toLong())
        sendByteRaw(b.inv() and 0xFF) // confirmamos con el complemento
        return b
    }

    private fun discardEcho() {
        // lectura no bloqueante corta para limpiar un eco residual
        kline.readByte(10)
    }

    companion object {
        const val COMM_BAUD = 10400   // VAG KWP1281 tipico (si falla, probar 9600)
        const val W4_MS = 55          // espera antes de responder el key byte 2
        const val W_INTERBYTE_MS = 5  // separacion entre bytes del handshake
        const val BYTE_TIMEOUT = 1200 // tiempo max esperando un byte
        const val W_SYNC_TIMEOUT = 2500
    }
}
