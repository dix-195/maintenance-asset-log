package com.maintenance.assetlog.core

import com.maintenance.assetlog.data.Snapshot
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Backup {
    private val magic = byteArrayOf(77, 65, 76, 2)
    const val MAX_BYTES = 64 * 1024 * 1024
    private const val HEADER_SIZE = 32

    fun write(snapshot: Snapshot, password: CharArray, output: OutputStream) {
        require(password.size >= 8)
        val header = magic + ByteArray(28).also(SecureRandom()::nextBytes)
        val payload = Json.encodeToString(snapshot).encodeToByteArray()
        require(payload.size <= MAX_BYTES - HEADER_SIZE - 16)
        output.write(header)
        output.write(cipher(Cipher.ENCRYPT_MODE, header, password).doFinal(payload))
    }

    fun read(input: InputStream, password: CharArray): Snapshot {
        val bytes = ByteArrayOutputStream().use { buffer ->
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count == -1) break
                require(buffer.size() <= MAX_BYTES - count)
                buffer.write(chunk, 0, count)
            }
            buffer.toByteArray()
        }
        require(bytes.size in (HEADER_SIZE + 16)..MAX_BYTES)
        val header = bytes.copyOfRange(0, HEADER_SIZE)
        require(header.copyOfRange(0, 3).contentEquals(magic.copyOfRange(0, 3)) && header[3].toInt() in 1..2)
        val payload = cipher(Cipher.DECRYPT_MODE, header, password)
            .doFinal(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE)
        return Json.decodeFromString(payload.decodeToString(throwOnInvalidSequence = true))
    }

    private fun cipher(mode: Int, header: ByteArray, password: CharArray): Cipher {
        val spec = PBEKeySpec(password, header.copyOfRange(4, 20), 600_000, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, header, 20, 12))
                updateAAD(header)
            }
        } finally {
            key.fill(0)
        }
    }
}
