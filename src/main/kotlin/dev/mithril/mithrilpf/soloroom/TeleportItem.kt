package dev.mithril.mithrilpf.soloroom

import net.minecraft.nbt.StringTag
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.component.CustomData

/** Reads vanilla custom data only; cached until the held component changes. */
class TeleportItem {
    private var previous: CustomData? = null
    private var normal = 0
    private var sneaking = 0

    fun observeUse(
        replay: RunReplay,
        eligible: Boolean,
        hand: InteractionHand,
        data: CustomData?,
        sneak: Boolean,
        nanos: Long,
    ): InteractionResult {
        if (eligible && replay.active && hand == InteractionHand.MAIN_HAND)
            replay.use(kind(data, sneak), nanos)
        return InteractionResult.PASS
    }

    fun kind(data: CustomData?, sneak: Boolean): Int {
        if (data !== previous) {
            previous = data
            val attributes = data?.copyTag()?.getCompoundOrEmpty("ExtraAttributes")
            val id = attributes?.getStringOr("id", "")
            normal =
                when (id) {
                    "ASPECT_OF_THE_VOID",
                    "ASPECT_OF_THE_END" -> 2
                    "HYPERION",
                    "ASTRAEA",
                    "SCYLLA",
                    "VALKYRIE" ->
                        if (attributes.getListOrEmpty("ability_scroll").containsAll(IMPACT)) 3
                        else 0
                    else -> 0
                }
            sneaking = if (normal == 2 && attributes?.getIntOr("ethermerge", 0) == 1) 1 else normal
        }
        return if (sneak) sneaking else normal
    }

    companion object {
        private val IMPACT =
            listOf("IMPLOSION_SCROLL", "WITHER_SHIELD_SCROLL", "SHADOW_WARP_SCROLL")
                .map(StringTag::valueOf)
    }
}
