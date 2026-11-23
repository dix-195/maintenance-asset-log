package com.maintenance.assetlog

import com.maintenance.assetlog.core.Backup
import com.maintenance.assetlog.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException

class BackupTest {
    private val snapshot = Snapshot(
        listOf(Asset(id = 1, name = "سيارة", category = "مركبة")),
        listOf(Maintenance(id = 2, assetId = 1, type = "فحص", date = "2025-02-03", cycle = "test")),
        listOf(Part(id = 3, assetId = 1, name = "بطارية"))
    )
    private val password = "كلمة مرور اختبارية".toCharArray()

    private fun encrypted() = ByteArrayOutputStream().use {
        Backup.write(snapshot, password, it)
        it.toByteArray()
    }

    @Test fun encryptedBackupRoundTripsAndUsesFreshRandomness() {
        val first = encrypted()
        assertEquals(snapshot, Backup.read(first.inputStream(), password))
        assertFalse(first.contentEquals(encrypted()))
        assertFalse(first.decodeToString().contains("سيارة"))
    }

    @Test fun wrongPasswordAndAlteredCiphertextAreRejected() {
        val bytes = encrypted()
        assertThrows(AEADBadTagException::class.java) {
            Backup.read(bytes.inputStream(), "different".toCharArray())
        }
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { Backup.read(bytes.inputStream(), password) }
    }
}
