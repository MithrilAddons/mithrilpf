package dev.mithril.mithrilpf.dungeontimer

import java.util.Locale
import net.minecraft.network.chat.Component

internal object DungeonTimeFormat {
    const val TICK_COLOR = 0xAAAAAA
    const val BRACKET_COLOR = 0x777777

    fun millis(millis: Long): String {
        // Round before splitting units so a minute boundary never displays 60.00s.
        val hundredths = millis / 10 + if (millis % 10 >= 5) 1 else 0
        val seconds = String.format(Locale.ROOT, "%.2fs", (hundredths % 6000) / 100.0)
        val minutes = hundredths / 6000
        return when {
            minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m $seconds"
            minutes > 0 -> "${minutes}m $seconds"
            else -> seconds
        }
    }

    fun ticks(ticks: Long): String = millis(ticks * 50)

    fun pair(time: SplitTime): String = "${millis(time.realMillis)} (${ticks(time.ticks)})"

    fun styledPair(time: SplitTime, realColor: Int): Component =
        Component.literal(millis(time.realMillis))
            .withStyle { it.withColor(realColor and 0xFFFFFF) }
            .append(Component.literal(" (").withStyle { it.withColor(BRACKET_COLOR) })
            .append(Component.literal(ticks(time.ticks)).withStyle { it.withColor(TICK_COLOR) })
            .append(Component.literal(")").withStyle { it.withColor(BRACKET_COLOR) })
}
