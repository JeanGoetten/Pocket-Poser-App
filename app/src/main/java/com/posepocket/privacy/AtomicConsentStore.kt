package com.posepocket.privacy

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * The receipt is written to a sibling file, fsynced and atomically replaced.
 *
 * Android AtomicFile's Robolectric implementation may fail to replace an existing
 * file on Windows without reporting an error, leaving an old, still-valid grant
 * in place. Using NIO ATOMIC_MOVE + REPLACE_EXISTING avoids silent stale receipts.
 * If an atomic replacement is unsupported or fails, writes fail closed.
 */
class AtomicConsentStore(directory: File) : ConsentStore {
    private val baseFile = File(directory, "consent.json")
    private val pendingFile = File(directory, "consent.json.pending")

    @Synchronized
    override fun read(): ConsentReceipt? = try {
        val envelope = JSONObject(baseFile.readText(Charsets.UTF_8))
        val payload = envelope.getString("payload")
        if (sha256(payload) != envelope.getString("checksum")) null else {
            val json = JSONObject(payload)
            require(json.getInt("schema_version") == 1)
            ConsentReceipt(
                json.getString("policy_version"), json.getString("scope"),
                json.getString("document_hash"), json.getBoolean("notice_ack"),
                json.getBoolean("remote_consent"), Instant.parse(json.getString("accepted_at")),
                if (json.isNull("revoked_at")) null else Instant.parse(json.getString("revoked_at")),
            )
        }
    } catch (_: Exception) {
        null
    }

    @Synchronized
    override fun write(receipt: ConsentReceipt) {
        val payload = JSONObject().apply {
            put("schema_version", 1)
            put("policy_version", receipt.policyVersion)
            put("scope", receipt.scope)
            put("document_hash", receipt.documentHash)
            put("notice_ack", receipt.noticeAcknowledged)
            put("remote_consent", receipt.remoteProcessingConsented)
            put("accepted_at", receipt.acceptedAt.toString())
            put("revoked_at", receipt.revokedAt?.toString() ?: JSONObject.NULL)
        }.toString()
        val bytes = JSONObject().put("payload", payload).put("checksum", sha256(payload))
            .toString().toByteArray(Charsets.UTF_8)
        require(baseFile.parentFile?.isDirectory == true || baseFile.parentFile?.mkdirs() == true)
        try {
            FileOutputStream(pendingFile).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                pendingFile.toPath(), baseFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            // An interrupted pending write must never be interpreted as a grant.
            pendingFile.delete()
        }
        check(read() == receipt) { "Falha ao verificar o registro de privacidade." }
    }

    @Synchronized
    override fun clear() {
        val files = listOf(baseFile, pendingFile, File(baseFile.path + ".bak"), File(baseFile.path + ".new"))
        files.forEach { it.delete() }
        check(files.none { it.exists() }) { "Falha ao excluir o registro de autorização." }
    }
}
