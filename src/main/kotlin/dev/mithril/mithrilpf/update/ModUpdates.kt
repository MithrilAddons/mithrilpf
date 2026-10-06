package dev.mithril.mithrilpf.update

import dev.mithril.mithrilpf.ui.UpdateOptInScreen
import dev.mithril.mithrilpf.ui.UpdatePromptScreen
import java.nio.file.Path
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component

/** Opens the first-run choice and update prompt over the title screen, never during play. */
class ModUpdates(private val client: Minecraft, firstRun: Boolean) : AutoCloseable {
    private val service: UpdateService
    private var askedThisLaunch = false
    private var prompted = ""
    private var announced = ""
    private var dismissed = ""
    val status: UpdateStatus
        get() = service.status

    val installedVersion: String

    val cooldownSeconds: Long
        get() = service.cooldownSeconds

    init {
        val loader = FabricLoader.getInstance()
        val container = loader.getModContainer("mithrilpf").orElseThrow()
        installedVersion = container.metadata.version.friendlyString
        val game = loader.gameDir.toAbsolutePath().normalize()
        val environment =
            UpdateEnvironment(
                game,
                container.metadata.version.friendlyString,
                loader.allMods.associate { it.metadata.id to it.metadata.version.friendlyString },
                if (loader.isDevelopmentEnvironment) null
                else container.origin.paths.singleOrNull(),
                { javaClass.getResourceAsStream("/assets/mithrilpf/updater.jar") },
                UpdateDistribution.official(
                    javaClass.getResourceAsStream("/assets/mithrilpf/build.properties"),
                    container.metadata.version.friendlyString,
                ),
                firstRun = firstRun,
            )
        service =
            UpdateService(environment, { work -> client.execute(work) }, UpdateHttp()::get) { update
                ->
                val windows = System.getProperty("os.name").startsWith("Windows")
                val java =
                    Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        if (windows) "javaw.exe" else "java",
                    )
                val parent = ProcessHandle.current()
                val helper =
                    ProcessBuilder(
                            java.toString(),
                            "-jar",
                            update.helper.toString(),
                            parent.pid().toString(),
                            parent.info().startInstant().orElseThrow().toEpochMilli().toString(),
                            update.target.toString(),
                            update.staged.toString(),
                            update.oldHash,
                            update.newHash,
                        )
                        .directory(game.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(update.helper.parent.resolve("install.log").toFile())
                        .start()
                helper.outputStream.close()
            }
    }

    fun configure(settings: UpdateSettings) = service.configure(settings)

    fun checkNow() = service.checkNow()

    fun install() = service.install()

    fun installWithDependencies() = service.installWithDependencies()

    fun cancel() = service.cancel()

    fun skip() = service.skip()

    /** "Remind me later": no prompt for this version until the next launch. */
    fun remindLater() {
        dismissed = status.version
    }

    fun tick() {
        val status = status
        val screen = client.screen
        if (screen is TitleScreen) {
            if (status.state == "ask" && !askedThisLaunch) {
                askedThisLaunch = true
                client.setScreen(UpdateOptInScreen(screen, this))
            } else if (
                status.state == "available" &&
                    status.version != dismissed &&
                    status.version != prompted
            ) {
                prompted = status.version
                client.setScreen(UpdatePromptScreen(screen, this))
            }
        } else if (
            client.player != null &&
                status.state == "available" &&
                status.version !in setOf(announced, prompted, dismissed)
        ) {
            announced = status.version
            client.player?.sendSystemMessage(
                Component.translatable("update.mithrilpf.available_notice", status.version)
            )
        }
    }

    override fun close() = service.close()
}
