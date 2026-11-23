package com.maintenance.assetlog.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "maintenance-db-key-v1"

fun databasePassword(context: Context): ByteArray {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val wrapped = File(context.noBackupFilesDir, "database-key")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val existingKey = store.getKey(KEY_ALIAS, null) as? SecretKey
    if (wrapped.exists()) {
        val data = wrapped.readBytes()
        require(data.size == 60)
        cipher.init(Cipher.DECRYPT_MODE, checkNotNull(existingKey), GCMParameterSpec(128, data, 0, 12))
        return cipher.doFinal(data, 12, data.size - 12)
    }
    check(!context.getDatabasePath("maintenance.db").exists())
    val key = existingKey ?: KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
    ).apply {
        init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
    val password = ByteArray(32).also(SecureRandom()::nextBytes)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    check(cipher.iv.size == 12)
    val temp = File(context.noBackupFilesDir, "database-key.tmp")
    temp.outputStream().use {
        it.write(cipher.iv)
        it.write(cipher.doFinal(password))
        it.fd.sync()
    }
    check(temp.renameTo(wrapped))
    return password
}
