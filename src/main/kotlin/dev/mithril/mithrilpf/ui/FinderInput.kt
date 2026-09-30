package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.finder.FinderMetric
import java.text.NumberFormat
import java.util.Locale

internal fun metricText(metric: FinderMetric, value: Double?): String {
    if (value == null) return "—"
    return when (metric) {
        FinderMetric.S_PLUS,
        FinderMetric.SOLO,
        FinderMetric.TERMINALS -> {
            val seconds = value.toLong() / 1000
            "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
        }
        FinderMetric.SS -> "%.2f".format(Locale.ROOT, value / 1000)
        else -> NumberFormat.getIntegerInstance(Locale.ROOT).format(value.toLong())
    }
}

internal fun metricInput(metric: FinderMetric, value: String): Int? {
    if (value.isBlank()) return null
    val text = value.trim()
    val number =
        when (metric) {
            FinderMetric.S_PLUS,
            FinderMetric.SOLO,
            FinderMetric.TERMINALS -> {
                require(text.matches(Regex("[0-9]{1,3}:[0-5][0-9](\\.[0-9]{1,3})?")))
                val parts = text.split(':')
                parts[0].toLong() * 60000 +
                    parts[1].toBigDecimal().movePointRight(3).longValueExact()
            }
            FinderMetric.SS -> {
                require(text.matches(Regex("[0-9]{1,2}(\\.[0-9]{1,3})?")))
                text.toBigDecimal().movePointRight(3).longValueExact()
            }
            else -> {
                require(text.matches(Regex("[0-9]{1,5}")))
                text.toLong()
            }
        }
    require(number in 1..metric.maximum.toLong())
    return number.toInt()
}
