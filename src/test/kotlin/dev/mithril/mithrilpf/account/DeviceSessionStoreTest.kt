package dev.mithril.mithrilpf.account

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DeviceSessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val directory: Path
        get() = temporary.root.toPath()

    private val uuid = "a".repeat(32)
    private val other = "b".repeat(32)

    @Test
    fun keepsAccountsSeparateAndPreservesUnknownKeys() {
        val path = directory.resolve("device.json")
        Files.writeString(path, """{"version":1,"future":"keep","accounts":{}}""")
        val store = DeviceSessionStore(path)
        val session =
            DeviceSession("c".repeat(43), "d".repeat(43), System.currentTimeMillis() / 1000 + 1000)
        store.save(uuid, session)
        store.save(other, session)
        assertEquals(session.token, store.load(uuid)!!.token)
        store.save(uuid, null)
        assertNull(store.load(uuid))
        assertEquals(session.receipt, store.load(other)!!.receipt)
        assertTrue(Files.readString(path).contains("\"future\":\"keep\""))
    }

    @Test
    fun refusesMalformedAndNewerDataWithoutReplacingIt() {
        val path = directory.resolve("device.json")
        for (source in
            listOf(
                "broken",
                """{"version":2} """,
                """{"version":1,"accounts":{"$uuid":{"token":"bad"}}}""",
            )) {
            Files.writeString(path, source)
            assertFails { DeviceSessionStore(path).save(uuid, null) }
            assertEquals(source, Files.readString(path))
        }
    }

    @Test
    fun expiredCredentialsCannotAuthenticate() {
        val store = DeviceSessionStore(directory.resolve("device.json"))
        store.save(uuid, DeviceSession("c".repeat(43), "d".repeat(43), 1))
        assertNull(store.load(uuid))
    }
}
