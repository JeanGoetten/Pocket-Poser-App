package com.posepocket.privacy

import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

data class Policy(val version: String, val scope: String, val document: String) {
    val hash: String = sha256(document)
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

data class ConsentReceipt(
    val policyVersion: String,
    val scope: String,
    val documentHash: String,
    val noticeAcknowledged: Boolean,
    val remoteProcessingConsented: Boolean,
    val acceptedAt: Instant,
    val revokedAt: Instant? = null,
) {
    fun isValid(policy: Policy, now: Instant): Boolean =
        policyVersion == policy.version && scope == policy.scope &&
            documentHash == policy.hash && noticeAcknowledged && remoteProcessingConsented &&
            acceptedAt <= now && revokedAt == null
}

interface ConsentStore {
    /** Missing or corrupt data must be treated as no consent. */
    fun read(): ConsentReceipt?
    /** Return only after durable write; throw on failure. */
    fun write(receipt: ConsentReceipt)
    fun clear()
}

class ConsentRequired : IllegalStateException("É necessário um aceite válido da política.")

/** The future camera, picker and transport must check this at each operation boundary. */
class ConsentGate(
    val policy: Policy,
    private val store: ConsentStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    // A failed write/revoke may leave an old durable record. Never trust it in this process.
    private var storageFailed = false

    @Synchronized
    fun receipt(): ConsentReceipt? = if (storageFailed) null else try {
        store.read()?.takeIf { it.isValid(policy, clock.instant()) }
    } catch (_: Exception) {
        null
    }

    fun isGranted(): Boolean = receipt() != null

    @Synchronized
    fun accept(notice: Boolean, remote: Boolean) {
        require(notice && remote) { "As duas confirmações são obrigatórias." }
        val receipt = ConsentReceipt(policy.version, policy.scope, policy.hash, true, true, clock.instant())
        storageFailed = true
        store.write(receipt)
        check(store.read() == receipt) { "Não foi possível confirmar a gravação do aceite." }
        storageFailed = false
    }

    @Synchronized
    fun revoke() {
        storageFailed = true
        try {
            val previous = store.read()
            if (previous != null) {
                val revoked = previous.copy(revokedAt = clock.instant())
                store.write(revoked)
                check(store.read() == revoked) { "Não foi possível confirmar a revogação." }
            } else {
                store.clear()
            }
        } catch (error: Exception) {
            // Remove any old valid record if recording the revocation failed.
            try { store.clear() } catch (_: Exception) { /* Stay blocked; UI reports failure. */ }
            throw error
        }
        storageFailed = false
    }

    @Synchronized
    fun <T> withConsent(operation: () -> T): T {
        if (!isGranted()) throw ConsentRequired()
        return operation()
    }
}
