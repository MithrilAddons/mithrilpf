package dev.mithril.mithrilpf.soloroom

import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import java.util.Base64
import kotlin.test.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.component.CustomData

class TeleportItemTest {
    @Test
    fun `interaction observation never consumes clicks and only records eligible main hand use`() {
        for (eligible in listOf(false, true)) {
            for (active in listOf(false, true)) {
                for (hand in InteractionHand.entries) {
                    val replay = RunReplay()
                    if (active) replay.begin(TimerStamp(0, 0), -185.0, -185.0, 0f)
                    assertSame(
                        InteractionResult.PASS,
                        TeleportItem()
                            .observeUse(
                                replay,
                                eligible,
                                hand,
                                data("ASPECT_OF_THE_VOID", true),
                                true,
                                100_000_000,
                            ),
                    )
                    if (!active) replay.begin(TimerStamp(0, 0), -185.0, -185.0, 0f)
                    replay.teleport(150_000_000)
                    replay.observe(TimerStamp(4, 200_000_000), -165.0, -185.0, 0f, 0)
                    val bytes =
                        Base64.getDecoder()
                            .decode(assertNotNull(replay.freeze()).encode()["samples"].asString)
                    val expected =
                        if (eligible && active && hand == InteractionHand.MAIN_HAND) 5 else 17
                    assertEquals(expected, bytes[21].toInt() and 255)
                }
            }
        }
    }

    private fun data(id: String, ether: Boolean = false, impact: Boolean = false) =
        CustomData.of(
            CompoundTag().apply {
                put(
                    "ExtraAttributes",
                    CompoundTag().apply {
                        putString("id", id)
                        if (ether) putByte("ethermerge", 1)
                        if (impact)
                            put(
                                "ability_scroll",
                                ListTag().apply {
                                    listOf(
                                            "IMPLOSION_SCROLL",
                                            "WITHER_SHIELD_SCROLL",
                                            "SHADOW_WARP_SCROLL",
                                        )
                                        .forEach { add(StringTag.valueOf(it)) }
                                },
                            )
                    },
                )
            }
        )

    @Test
    fun `vanilla extra attributes classify only supported abilities and cache both sneak states`() {
        val item = TeleportItem()
        assertEquals(0, item.kind(null, false))
        for (id in listOf("ASPECT_OF_THE_END", "ASPECT_OF_THE_VOID")) {
            val merged = data(id, true)
            assertEquals(2, item.kind(merged, false))
            assertEquals(1, item.kind(merged, true))
            assertEquals(2, item.kind(data(id), true))
        }
        for (id in listOf("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE")) {
            assertEquals(3, item.kind(data(id, impact = true), false))
            assertEquals(0, item.kind(data(id), false))
        }
        assertEquals(0, item.kind(data("OTHER"), true))
        assertEquals(0, item.kind(null, true))
        assertEquals(0, item.kind(CustomData.EMPTY, false))
    }
}
