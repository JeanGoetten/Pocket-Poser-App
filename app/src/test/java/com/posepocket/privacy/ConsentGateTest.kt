package com.posepocket.privacy

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ConsentGateTest {
    private val now = Instant.parse("2026-10-14T18:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val policy = Policy("dev-v1", "video-v1", "Documento integral")
    private class MemoryStore : ConsentStore {
        var value: ConsentReceipt? = null
        var failWrite = false
        override fun read() = value
        override fun write(receipt: ConsentReceipt) {
            if (failWrite) throw IOException("Storage unavailable")
            value = receipt
        }
        override fun clear() { value = null }
    }

    @Test fun missingConsentNeverRunsProtectedOperation() {
        val gate = ConsentGate(policy, MemoryStore(), clock)
        var executed = false
        assertThrows(ConsentRequired::class.java) { gate.withConsent { executed = true } }
        assertFalse(executed)
    }

    @Test fun bothAffirmationsAreRequiredAndPartialAcceptanceIsNeverSaved() {
        val store = MemoryStore()
        val gate = ConsentGate(policy, store, clock)
        listOf(false to false, true to false, false to true).forEach { (notice, remote) ->
            assertThrows(IllegalArgumentException::class.java) { gate.accept(notice, remote) }
            assertNull(store.value)
        }
        gate.accept(true, true)
        assertEquals(now, gate.receipt()?.acceptedAt)
        assertEquals("executed", gate.withConsent { "executed" })
    }

    @Test fun validReceiptSurvivesGateRecreation() {
        val store = MemoryStore()
        ConsentGate(policy, store, clock).accept(true, true)
        assertTrue(ConsentGate(policy, store, clock).isGranted())
    }

    @Test fun changedVersionScopeOrExactDocumentInvalidatesConsent() {
        val store = MemoryStore()
        ConsentGate(policy, store, clock).accept(true, true)
        listOf(policy.copy(version = "dev-v2"), policy.copy(scope = "other"),
            policy.copy(document = "Documento integral\n")).forEach {
            assertFalse(ConsentGate(it, store, clock).isGranted())
        }
    }

    @Test fun revokedConsentBlocksExistingGateAndNewProcess() {
        val store = MemoryStore()
        val gate = ConsentGate(policy, store, clock)
        gate.accept(true, true)
        gate.revoke()
        assertEquals(now, store.value?.revokedAt)
        assertThrows(ConsentRequired::class.java) { gate.withConsent { fail("Must not execute") } }
        assertFalse(ConsentGate(policy, store, clock).isGranted())
        gate.accept(true, true)
        assertTrue(gate.isGranted())
    }

    @Test fun failedRevocationClearsOldReceiptAndRemainsBlocked() {
        val store = MemoryStore()
        val gate = ConsentGate(policy, store, clock)
        gate.accept(true, true)
        store.failWrite = true
        assertThrows(IOException::class.java) { gate.revoke() }
        assertNull(store.value)
        assertFalse(gate.isGranted())
        assertFalse(ConsentGate(policy, store, clock).isGranted())
    }

    @Test fun storageFailureCannotGrantConsent() {
        val store = MemoryStore().apply { failWrite = true }
        val gate = ConsentGate(policy, store, clock)
        assertThrows(IOException::class.java) { gate.accept(true, true) }
        assertFalse(gate.isGranted())
    }

    @Test fun futureOrIncompleteReceiptIsRejected() {
        val store = MemoryStore()
        val gate = ConsentGate(policy, store, clock)
        gate.accept(true, true)
        val valid = store.value!!
        listOf(valid.copy(acceptedAt = now.plusSeconds(1)), valid.copy(noticeAcknowledged = false),
            valid.copy(remoteProcessingConsented = false)).forEach {
            store.value = it
            assertFalse(gate.isGranted())
        }
    }

    @Test fun silentlyLostWriteNeverGrantsConsent() {
        val store = object : ConsentStore {
            override fun read(): ConsentReceipt? = null
            override fun write(receipt: ConsentReceipt) = Unit
            override fun clear() = Unit
        }
        val gate = ConsentGate(policy, store, clock)
        assertThrows(IllegalStateException::class.java) { gate.accept(true, true) }
        assertFalse(gate.isGranted())
    }
}
