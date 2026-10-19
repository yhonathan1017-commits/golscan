package com.galzza.golscan.kwp

/**
 * Formulas de los grupos de medicion de KWP1281.
 *
 * Cada medicion llega como (formula, a, b). La formula indica como convertir a
 * y b en un valor con unidades. Hay ~255 formulas; aqui estan las mas comunes.
 * Las que no reconocemos se muestran en crudo para poder mapearlas probando.
 */
object Formulas {
    fun apply(formula: Int, a: Int, b: Int): String = when (formula) {
        1 -> "%.0f rpm".format(0.2 * a * b)
        2 -> "%.1f %%".format(a * b / 128.0)         // p.ej. caudal
        3 -> "%.3f".format(0.002 * a * b)
        4 -> "%d°".format((b - 127) * a / 128 + 0)   // angulo
        5 -> "%.1f °C".format(a * (b - 100) / 10.0)  // temperatura
        6 -> "%.3f V".format(0.001 * a * b)
        7 -> "%.0f km/h".format(0.01 * a * b)
        8 -> "%.1f".format(0.1 * a * b)
        9 -> "%.1f °".format((b - 127) * a / 50.0)
        10 -> if (b == 0) "frio" else "caliente"
        11 -> "%.4f".format(0.0001 * a * (b - 128) + 1)
        12 -> "%.3f Ohm".format(0.001 * a * b)
        14 -> "%.3f bar".format(0.005 * a * b)
        15 -> "%.0f ms".format(0.01 * a * b)
        18 -> "%.1f A".format(0.1 * a * b)
        19 -> "%.3f l".format(a * b / 256.0)
        21 -> "%.3f V".format(a * b / 400.0)
        22 -> "%.0f ccm".format(a * b / 1.0)
        23 -> "%.3f %%".format(b * a / 256.0)
        25 -> "%.3f g/s".format(a * b / 182.04)
        else -> "f%d a=%d b=%d".format(formula, a, b) // sin formula: crudo
    }
}
