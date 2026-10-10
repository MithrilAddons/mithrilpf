package dev.mithril.mithrilpf.ui

import com.google.gson.JsonObject
import dev.mithril.mithrilpf.MithrilPF
import dev.mithril.mithrilpf.finder.FinderClient
import dev.mithril.mithrilpf.finder.FinderMember
import dev.mithril.mithrilpf.finder.FinderMessage
import dev.mithril.mithrilpf.finder.FinderParty
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractScrollArea
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

class FinderPartyView(
    private val finder: FinderClient,
    private val layout: FinderLayout,
    private val navigation: FinderNavigation,
    private val rebuild: () -> Unit,
    private val edit: () -> Unit,
    private val confirm: (Component, () -> Unit) -> Unit,
) {
    private var chatText: MultiLineTextWidget? = null
    private var previousMessages = emptyList<FinderMessage>()
    private var chatScroll: ScrollableLayout? = null
    private var sendButton: FlatButton? = null

    fun build(): LinearLayout {
        val party =
            finder.state?.party
                ?: return LinearLayout.vertical().spacing(10).apply {
                    label(finderText("party.none"), layout.pageWidth, Palette.MUTED)
                    button(finderText("party.browse"), layout.pageWidth) {
                        navigation.tab = FinderTab.PARTIES
                        rebuild()
                    }
                }
        if (navigation.chatParty != party.id) {
            navigation.chatParty = party.id
            navigation.chatDraft = ""
            navigation.chatRequest = null
        }
        val root =
            if (layout.wide) LinearLayout.horizontal().spacing(12)
            else LinearLayout.vertical().spacing(12)
        val width = if (layout.wide) (layout.pageWidth - 12) / 2 else layout.pageWidth
        val roster = root.addChild(LinearLayout.vertical().spacing(8))
        roster.label(finderText("browse.party", party.leader), width)
        roster.label(
            finderText(
                "party.progress",
                party.floor,
                party.slots.count { it.filled },
                finderText(
                    when {
                        party.completed -> "party.complete"
                        party.invited -> "party.inviting"
                        party.slots.all { it.filled } -> "party.connecting"
                        party.paused -> "party.paused"
                        else -> "party.filling"
                    }
                ),
            ),
            width,
            Palette.ACCENT,
        )
        rosterMembers(roster, width, party)
        handoff(roster, width, party)
        leaderControls(roster, width, party)
        chat(root, width, party)
        return root
    }

    fun refreshMessages(): Boolean {
        sendButton?.active = !finder.busy && navigation.chatDraft.isNotBlank()
        val messages = finder.state?.party?.messages.orEmpty()
        if (previousMessages == messages) return false
        previousMessages = messages
        var atBottom = true
        chatScroll?.visitWidgets {
            if (it is AbstractScrollArea) atBottom = it.scrollAmount() >= it.maxScrollAmount() - 2
        }
        chatText?.message = messages(messages)
        chatScroll?.arrangeElements()
        if (atBottom)
            chatScroll?.visitWidgets {
                if (it is AbstractScrollArea) it.setScrollAmount(it.maxScrollAmount().toDouble())
            }
        return true
    }

    private fun messages(values: List<FinderMessage>): Component =
        if (values.isEmpty()) finderText("party.no_messages")
        else
            Component.literal(
                values.joinToString("\n\n") {
                    (it.name?.let { name -> "$name: " } ?: "") + it.text
                }
            )

    private fun rosterMembers(roster: LinearLayout, width: Int, party: FinderParty) {
        for ((index, slot) in party.slots.withIndex()) {
            val member = party.members.firstOrNull { it.slot == index }
            val role = finderText("role.${slot.role.key}")
            if (member == null)
                roster.label(finderText("browse.open_slot", role), width, Palette.MUTED)
            else {
                val presence =
                    finderText(
                        when {
                            party.accepted.getOrNull(index) == true -> "party.joined"
                            party.joined.getOrNull(index) == true -> "party.online"
                            else -> "party.offline"
                        }
                    )
                val memberRow = roster.addChild(LinearLayout.horizontal().spacing(5))
                memberRow.addChild(FinderAvatarWidget(member.uuid))
                memberRow.label(
                    finderText("party.member_row", role, member.name, presence),
                    width - 17,
                )
                if (party.youLead && member.uuid != finder.state?.uuid)
                    memberControls(roster, width, member)
            }
        }
    }

    private fun handoff(roster: LinearLayout, width: Int, party: FinderParty) {
        if (party.slots.all { it.filled } && !party.completed) {
            roster.label(
                finderText(if (party.youLead) "party.handoff_leader" else "party.handoff_member"),
                width,
                Palette.MUTED,
            )
            val status = MithrilPF.partyStatus
            roster.label(
                Component.translatable("party.mithrilpf.$status"),
                width,
                if (status == "conflict") Palette.DANGER else Palette.MUTED,
            )
            if (party.youLead)
                roster.button(
                    finderText(if (party.invited) "party.retry" else "party.invite"),
                    width,
                    enabled = MithrilPF.partyHandoff?.ready == true,
                ) {
                    MithrilPF.invitePartyMembers()
                }
        }
    }

    private fun leaderControls(roster: LinearLayout, width: Int, party: FinderParty) {
        if (party.youLead && !party.completed) {
            roster.button(finderText("party.edit"), width, enabled = !finder.busy, action = edit)
            roster.button(
                finderText(if (party.paused) "party.resume" else "party.pause"),
                width,
                enabled = !finder.busy,
            ) {
                finder.action("pause", JsonObject().apply { addProperty("paused", !party.paused) })
            }
            roster.button(finderText("party.unlist"), width, enabled = !finder.busy) {
                confirm(finderText("party.confirm_unlist")) {
                    finder.action("unlist", JsonObject())
                }
            }
        }
        roster.button(finderText("party.leave"), width, enabled = !finder.busy) {
            confirm(finderText("party.confirm_leave")) { finder.action("leave", JsonObject()) }
        }
    }

    private fun chat(root: LinearLayout, width: Int, party: FinderParty) {
        val chat = root.addChild(LinearLayout.vertical().spacing(8))
        val heading = chat.addChild(LinearLayout.horizontal().spacing(5))
        heading.defaultCellSetting().alignVerticallyMiddle()
        heading.label(finderText("party.chat"), width - 110)
        heading.button(finderText("report.title"), 105, enabled = !finder.busy) {
            val client = Minecraft.getInstance()
            client.screen?.let { parent ->
                client.setScreen(FinderReportScreen(parent, finder, finder.state?.party ?: party))
            }
        }
        val messages = LinearLayout.vertical()
        previousMessages = party.messages
        chatText = messages.label(messages(party.messages), width - 20, Palette.TEXT)
        chatScroll =
            chat
                .addChild(
                    ScrollableLayout(
                        Minecraft.getInstance(),
                        messages,
                        (layout.contentHeight - 118).coerceIn(70, 170),
                    )
                )
                .apply {
                    setMinWidth(width - 20)
                    arrangeElements()
                    visitWidgets {
                        if (it is AbstractScrollArea)
                            it.setScrollAmount(it.maxScrollAmount().toDouble())
                    }
                }
        lateinit var send: () -> Unit
        val input =
            chat
                .addChild(
                    object :
                        EditBox(
                            Minecraft.getInstance().font,
                            width,
                            20,
                            finderText("party.message"),
                        ) {
                        override fun keyPressed(event: KeyEvent): Boolean {
                            if (
                                isFocused &&
                                    event.key() in
                                        setOf(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER)
                            ) {
                                send()
                                return true
                            }
                            return super.keyPressed(event)
                        }
                    }
                )
                .apply {
                    setMaxLength(256)
                    value = navigation.chatDraft
                    setHint(finderText("party.message"))
                    setResponder {
                        navigation.chatDraft = it
                        navigation.chatRequest = null
                    }
                }
        send = send@{
            if (navigation.chatDraft.isBlank() || finder.busy) return@send
            val sentText = navigation.chatDraft
            val request =
                navigation.chatRequest
                    ?: UUID.randomUUID().toString().replace("-", "").also {
                        navigation.chatRequest = it
                    }
            finder.action(
                "chat",
                JsonObject().apply {
                    addProperty("version", 1)
                    addProperty("party_id", party.id)
                    addProperty("request_id", request)
                    addProperty("text", sentText)
                },
            ) { success ->
                if (
                    success && navigation.chatParty == party.id && navigation.chatDraft == sentText
                ) {
                    navigation.chatDraft = ""
                    navigation.chatRequest = null
                    input.value = ""
                }
            }
        }
        sendButton =
            chat.button(
                finderText("party.send"),
                width,
                primary = true,
                enabled = !finder.busy && navigation.chatDraft.isNotBlank(),
                action = send,
            )
    }

    private fun memberControls(roster: LinearLayout, width: Int, member: FinderMember) {

        val controls = roster.addChild(LinearLayout.horizontal().spacing(4))
        for (block in listOf(false, true)) controls.button(
            finderText(if (block) "party.remove_block" else "party.remove"),
            (width - 4) / 2,
            enabled = !finder.busy,
        ) {
            confirm(
                finderText(
                    if (block) "party.confirm_block" else "party.confirm_remove",
                    member.name,
                )
            ) {
                finder.action(
                    "remove",
                    JsonObject().apply {
                        addProperty("member", member.uuid)
                        addProperty("block", block)
                    },
                )
            }
        }
    }
}
