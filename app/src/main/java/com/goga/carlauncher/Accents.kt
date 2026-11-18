package com.goga.carlauncher

/** v1.7.0 accent colours, applied to the whole interface (theme accent override). */
object Accents {
    val ALL = listOf("" to "Как в теме", "blue" to "Синий", "cyan" to "Бирюза", "green" to "Зелёный", "lime" to "Лайм",
        "yellow" to "Жёлтый", "orange" to "Оранжевый", "red" to "Красный", "pink" to "Розовый", "purple" to "Фиолет.", "gold" to "Золото")
    fun color(id: String): Int? = when (id) {
        "blue" -> 0xFF0A84FF.toInt(); "cyan" -> 0xFF32D2E6.toInt(); "green" -> 0xFF30D158.toInt(); "lime" -> 0xFFB8E01F.toInt()
        "yellow" -> 0xFFFFD60A.toInt(); "orange" -> 0xFFFF9F0A.toInt(); "red" -> 0xFFFF453A.toInt(); "pink" -> 0xFFFF375F.toInt()
        "purple" -> 0xFFBF5AF2.toInt(); "gold" -> 0xFFE0B252.toInt(); else -> null
    }
}
