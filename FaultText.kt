package com.galzza.golscan.kwp

/**
 * Descripciones de codigos de falla VAG mas frecuentes en motores con inyeccion
 * Magneti Marelli / Bosch de esa epoca. La lista es parcial a proposito: se va
 * ampliando a medida que aparezcan codigos reales en tu Gol.
 *
 * El numero es el codigo VAG de 5 digitos (el mismo que mostraba el programa del PC).
 */
object FaultText {
    private val table = mapOf(
        0 to "Sin fallas",
        513 to "Sensor de regimen del motor (rpm) -G28-",
        514 to "Sensor Hall -G40-",
        515 to "Sensor de referencia (PMS)",
        516 to "Sensor de posicion de mariposa -G69-",
        517 to "Temperatura del refrigerante -G62-",
        518 to "Potenciometro de mariposa -G69-",
        519 to "Temperatura del aire de admision -G42-",
        520 to "Medidor de masa de aire / MAP -G71-",
        522 to "Temperatura del refrigerante -G62-",
        523 to "Temperatura del aire -G42-",
        524 to "Sensor de detonacion 1 -G61-",
        525 to "Sonda lambda -G39-",
        533 to "Regulacion de ralenti (mezcla)",
        537 to "Regulacion lambda, limite alcanzado",
        542 to "Sensor de recorrido de aguja",
        546 to "Senal de carga ausente",
        548 to "Senal de consumo ausente",
        549 to "Senal de consumo de combustible",
        550 to "Regulacion de la marcha suave",
        553 to "Caudalimetro / medidor de aire",
        558 to "Inyector cil.1 -N30-",
        559 to "Inyector cil.2 -N31-",
        560 to "Inyector cil.3 -N32-",
        561 to "Mezcla adaptativa (limite)",
        562 to "Inyector cil.4 -N33-",
        577 to "Regulacion antidetonante cil.1",
        624 to "Compresor del aire acondicionado",
        625 to "Senal de velocidad -G68-",
        750 to "Lampara de averia (MIL)"
    )

    fun describe(code: Int): String = table[code] ?: "Codigo VAG %05d (desconocido)".format(code)
}
