package dev.mithril.mithrilpf.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.ObjectContents
import net.minecraft.network.chat.contents.objects.AtlasSprite

class ModChatTest {
    @Test
    fun `every line starts with the icon and a gradient name`() {
        val line = ModChat.line(Component.literal("Saved 3 room PBs"))
        assertEquals("MithrilPF › Saved 3 room PBs", line.string.trimStart())
        val (icon, _, name, arrow, message) = line.siblings
        val sprite = (icon.contents as ObjectContents).contents() as AtlasSprite
        assertEquals("minecraft:gui", sprite.atlas().toString())
        assertEquals("mithrilpf:chat_icon", sprite.sprite().toString())
        val letters = name.siblings
        assertEquals("MithrilPF", letters.joinToString("") { it.string })
        assertEquals(Palette.HIGHLIGHT and 0xFFFFFF, letters.first().style.color?.value)
        assertEquals(Palette.BRAND and 0xFFFFFF, letters.last().style.color?.value)
        assertTrue(letters.all { it.style.shadowColor == ModChat.SHADOW })
        assertEquals(Palette.MUTED and 0xFFFFFF, arrow.style.color?.value)
        assertEquals("Saved 3 room PBs", message.string)
    }

    @Test
    fun `the gradient steps evenly between its ends`() {
        val colors =
            ModChat.gradient("abc", 0x000000, 0xFF8040).siblings.map { it.style.color?.value }
        assertEquals(listOf(0x000000, 0x804020, 0xFF8040), colors)
        assertEquals(
            0x123456,
            ModChat.gradient("a", 0x123456, 0xFFFFFF).siblings.single().style.color?.value,
        )
    }
}
