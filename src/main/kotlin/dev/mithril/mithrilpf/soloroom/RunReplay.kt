package dev.mithril.mithrilpf.soloroom

import com.google.gson.JsonObject
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.floor

/** Five observations per second in one bounded buffer; no encoding, disk or network on ticks. */
class RunReplay {
    private var buffer: ByteBuffer? = null
    private var start: TimerStamp? = null
    private var lastMillis = -1L
    private var lastX = 0
    private var lastZ = 0
    private var lastFlags = 3
    private var secrets = 0
    private var broken = true
    private var roomSecrets = RunRoomSecrets()

    fun begin(now: TimerStamp, x: Double, z: Double, yaw: Float) {
        buffer = ByteBuffer.allocate(MAX_SAMPLES * STRIDE).order(ByteOrder.LITTLE_ENDIAN)
        start = now
        lastMillis = -1
        lastFlags = 3
        secrets = 0
        broken = true
        roomSecrets = RunRoomSecrets()
        observe(now, x, z, yaw, null)
    }

    fun discontinuity() {
        broken = true
    }

    fun observe(
        now: TimerStamp,
        x: Double,
        z: Double,
        yaw: Float,
        collected: Int?,
        finish: Boolean = false,
    ) {
        val target = buffer ?: return
        val elapsed = (now.nanos - requireNotNull(start).nanos) / 1_000_000
        if (elapsed !in 0..7_200_000 || elapsed < lastMillis) {
            buffer = null
            return
        }
        if (!finish && lastMillis >= 0 && elapsed - lastMillis < 200) return
        if (collected != null && collected in 0..3600) secrets = maxOf(secrets, collected)
        val mapped = x >= -200 && x < -9 && z >= -200 && z < -9 && yaw.isFinite()
        val px = if (mapped) floor(x * 16).toInt() else 0
        val pz = if (mapped) floor(z * 16).toInt() else 0
        val dx = (px - lastX).toLong()
        val dz = (pz - lastZ).toLong()
        val jump = dx * dx + dz * dz >= 128 * 128
        var flags = if (mapped) 0 else 2
        if (broken || lastFlags and 2 != 0 || !mapped || elapsed - lastMillis > 1000 || jump)
            flags = flags or 1
        if (elapsed == lastMillis) {
            target.position(target.position() - STRIDE)
            flags = flags or (lastFlags and 1)
        }
        if (target.remaining() < STRIDE) {
            buffer = null
            return
        }
        val facing = if (mapped) (((yaw % 360 + 360) % 360) * 256 / 360).toInt() else 0
        target.putInt(elapsed.toInt()).putShort(px.toShort()).putShort(pz.toShort())
        target.put(facing.toByte()).put(flags.toByte()).putShort(secrets.toShort())
        lastMillis = elapsed
        lastX = px
        lastZ = pz
        lastFlags = flags
        broken = false
    }

    fun observeRoomSecrets(now: TimerStamp, tile: Int, found: Int, total: Int) {
        if (buffer == null) return
        roomSecrets.observe(
            (now.nanos - requireNotNull(start).nanos) / 1_000_000,
            tile,
            found,
            total,
        )
    }

    fun freeze(map: JsonObject? = null): ReplaySnapshot? {
        val captured = buffer ?: return null
        buffer = null
        if (captured.position() < STRIDE * 2) return null
        return ReplaySnapshot(
            captured.array().copyOf(captured.position()),
            map?.let { roomSecrets.freeze(it, lastMillis) },
        )
    }

    companion object {
        const val STRIDE = 12
        const val MAX_SAMPLES = 36002
    }
}

/** Owns a detached buffer; only the sync worker turns it into the wire representation. */
class ReplaySnapshot
internal constructor(
    private val bytes: ByteArray,
    private val roomSecrets: List<RoomSecretEvent>? = null,
) {
    fun encode() =
        JsonObject().apply {
            addProperty("version", 1)
            addProperty("samples", Base64.getEncoder().encodeToString(bytes))
            roomSecrets?.let { addProperty("room_secrets", encodeRoomSecrets(it)) }
        }
}
