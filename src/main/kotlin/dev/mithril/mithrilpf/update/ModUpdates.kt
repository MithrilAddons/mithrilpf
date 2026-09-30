package dev.mithril.mithrilpf.update

import java.nio.file.Path
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

class ModUpdates(private val client: Minecraft) : AutoCloseable {
    private val service: UpdateService
    private var notifiedVersion = ""
    val status: UpdateStatus
        get() = service.status

    val cooldownSeconds: Long
        get() = service.cooldownSeconds

    init {
        val loader = FabricLoader.getInstance()
        val container = loader.getModContainer("mithrilpf").orElseThrow()
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

    fun tick() {
        if (status.state == "ready" && status.version != notifiedVersion && client.player != null) {
            notifiedVersion = status.version
            client.player?.sendSystemMessage(
                Component.translatable("update.mithrilpf.notice", status.version)
            )
        }
    }

    override fun close() = service.close()
}
