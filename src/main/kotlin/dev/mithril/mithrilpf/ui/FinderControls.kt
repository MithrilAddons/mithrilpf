package dev.mithril.mithrilpf.ui

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
