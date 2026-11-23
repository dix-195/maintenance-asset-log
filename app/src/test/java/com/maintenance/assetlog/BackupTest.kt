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
        listOf(Part(id = 3, assetId = 1, name = "بطارية")),
        sections = listOf(Section(id = "house", name = "المنزل")),
        services = listOf(Service(id = "bill", sectionId = "house", name = "الكهرباء")),
        operations = listOf(Operation(id = "payment", sectionId = "house", title = "الكهرباء", amount = 4500,
            currency = "SAR", state = PaymentState.PAID, date = "2026-01-01", paidOn = "2026-01-03"))
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

    @Test fun canReadVersionOneWithoutLedgerFields() {
        val header = byteArrayOf(77, 65, 76, 1) + ByteArray(28) { it.toByte() }
        val spec = javax.crypto.spec.PBEKeySpec(password, header.copyOfRange(4, 20), 600_000, 256)
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, header, 20, 12))
        cipher.updateAAD(header)
        val payload = """{"assets":[{"id":1,"name":"سيارة","category":"مركبة"}],"history":[],"parts":[]}"""
        val restored = Backup.read((header + cipher.doFinal(payload.encodeToByteArray())).inputStream(), password)
        assertEquals(1L, restored.assets.single().id)
        assertTrue(restored.sections.isEmpty())
        assertTrue(restored.operations.isEmpty())
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
