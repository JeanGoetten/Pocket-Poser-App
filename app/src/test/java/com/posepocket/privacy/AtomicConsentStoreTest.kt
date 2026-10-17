package com.posepocket.privacy

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
// API 35's Robolectric Os.rename shadow uses Windows File.renameTo, which cannot
// replace an existing file. Exercise the portable AtomicFile backup implementation
// here and verify current Android atomic replacement in DevicePipelineTest.
@Config(sdk = [28])
class AtomicConsentStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val policy = Policy("dev-v1", "video-v1", "Política completa")
    private val clock = Clock.fixed(Instant.parse("2026-10-14T18:00:00Z"), ZoneOffset.UTC)

    @Test fun durableAcceptanceAndRevocationSurviveStoreRecreation() {
        val directory = temporary.newFolder()
        val gate = ConsentGate(policy, AtomicConsentStore(directory), clock)
        assertFalse(gate.isGranted())
        gate.accept(true, true)
        assertEquals(gate.receipt(), ConsentGate(policy, AtomicConsentStore(directory), clock).receipt())
        gate.revoke()
        assertFalse(ConsentGate(policy, AtomicConsentStore(directory), clock).isGranted())
        assertNotNull(AtomicConsentStore(directory).read()?.revokedAt)
    }

    @Test fun truncatedOrModifiedStorageFailsClosed() {
        val directory = temporary.newFolder()
        val gate = ConsentGate(policy, AtomicConsentStore(directory), clock)
        gate.accept(true, true)
        val file = File(directory, "consent.json")
        val valid = file.readText()
        file.writeText(valid.replace("dev-v1", "dev-v2"))
        assertFalse(gate.isGranted())
        file.writeText(valid.take(20))
        assertFalse(gate.isGranted())
        file.writeText("{}")
        assertFalse(gate.isGranted())
    }

    @Test fun deletingDataRequiresFreshConsent() {
        val directory = temporary.newFolder()
        val store = AtomicConsentStore(directory)
        ConsentGate(policy, store, clock).accept(true, true)
        store.clear()
        assertNull(AtomicConsentStore(directory).read())
    }
}
