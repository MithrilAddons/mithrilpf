package dev.mithril.mithrilpf.ui

import kotlin.math.roundToInt
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.contents.objects.AtlasSprite
import net.minecraft.resources.Identifier

/**
 * Everything MithrilPF prints in chat starts with its icon and "MithrilPF ›", the name fading from
 * light to deep accent, so it stands out from server and other mods' messages. The icon is a GUI
 * atlas sprite drawn inline by vanilla text rendering; logs and narration see no text for it.
 */
object ModChat {
    private val GUI_ATLAS = Identifier.withDefaultNamespace("gui")
    private val ICON = Identifier.fromNamespaceAndPath("mithrilpf", "chat_icon")

    /**
     * The name's shadow: a deep accent, brighter than vanilla's quarter-brightness shadow so the
     * name reads as tinted rather than plain.
     */
    internal val SHADOW = 0xFF4B4E9E.toInt()

    fun line(message: Component): MutableComponent = prefix().append(message)

    /**
     * [text] fading from [from] to [to] one letter at a time, every letter with the name's shadow.
     */
    internal fun gradient(text: String, from: Int, to: Int): MutableComponent {
        val name = Component.empty()
        text.forEachIndexed { index, letter ->
            val t = if (text.length > 1) index / (text.length - 1.0) else 0.0
            fun channel(shift: Int): Int {
                val a = (from shr shift) and 0xFF
                val b = (to shr shift) and 0xFF
                return (a + (b - a) * t).roundToInt() shl shift
            }
            val color = channel(16) or channel(8) or channel(0)
            name.append(
                Component.literal(letter.toString()).withStyle {
                    it.withColor(color).withShadowColor(SHADOW)
                }
            )
        }
        return name
    }

    fun prefix(): MutableComponent =
        Component.empty()
            .append(Component.`object`(AtlasSprite(GUI_ATLAS, ICON), Component.empty()))
            .append(" ")
            .append(gradient("MithrilPF", Palette.HIGHLIGHT, Palette.BRAND))
            .append(Component.literal(" › ").withStyle { it.withColor(Palette.MUTED and 0xFFFFFF) })
}
