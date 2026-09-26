package dev.mithril.mithrilpf.account

import java.util.concurrent.locks.ReentrantLock

/** Mojang keeps one pending server proof per account. Do not race link/sync proofs. */
object OwnershipProof {
    private val lock = ReentrantLock()

    fun <T> serialized(action: () -> T): T {
        lock.lockInterruptibly()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }
}
