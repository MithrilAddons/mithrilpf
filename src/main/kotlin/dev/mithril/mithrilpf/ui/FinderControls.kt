package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.finder.FinderMetric
import java.text.NumberFormat
import java.util.Locale
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.network.chat.Component

internal fun finderText(key: String, vararg args: Any): Component =
    Component.translatable("finder.mithrilpf.$key", *args)

internal fun LinearLayout.label(value: Component, width: Int, color: Int = Palette.TEXT) =
    addChild(
        MultiLineTextWidget(
                value.copy().withStyle { it.withColor(color and 0xFFFFFF) },
                Minecraft.getInstance().font,
            )
            .setMaxWidth(width)
    )

internal fun LinearLayout.button(
    label: Component,
    width: Int,
    primary: Boolean = false,
    enabled: Boolean = true,
    action: () -> Unit,
): FlatButton =
    addChild(FlatButton(0, 0, width, label, primary, action)).also { it.active = enabled }

internal fun LinearLayout.input(
    label: Component,
    width: Int,
    value: String,
    length: Int = 32,
    change: (String) -> Unit,
): EditBox {
    label(label, width, Palette.MUTED)
    return addChild(EditBox(Minecraft.getInstance().font, width, 20, label)).apply {
        setMaxLength(length)
        setValue(value)
        setResponder(change)
    }
}

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
