package com.galzza.golscan.kwp

/** Un codigo de falla leido de la ECU. */
data class FaultCode(
    val code: Int,          // codigo de 16 bits (p.ej. 00561)
    val elaboration: Int,   // byte de estado/elaboracion
    val text: String        // descripcion si la conocemos
) {
    val statusText: String
        get() = buildString {
            if (elaboration and 0x80 != 0) append("intermitente ")
            // Los bits bajos describen el tipo; se afina con pruebas reales.
        }.trim().ifEmpty { "presente" }
}

/** Una lectura de un grupo de medicion (4 valores por grupo en KWP1281). */
data class Measurement(
    val formula: Int,
    val a: Int,
    val b: Int,
    val value: String
)

/** Resultado del handshake inicial con la ECU. */
data class EcuInfo(
    val keyWordLow: Int,
    val keyWordHigh: Int,
    val idStrings: List<String>
)
