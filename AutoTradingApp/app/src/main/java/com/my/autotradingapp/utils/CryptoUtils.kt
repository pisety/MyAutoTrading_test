package com.my.autotradingapp.utils

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

import com.my.autotradingapp.BuildConfig

object CryptoUtils {
    // AES key injected from local.properties via BuildConfig (not hardcoded)
    private val SECRET_KEY = BuildConfig.AES_SECRET_KEY.toByteArray(Charsets.UTF_8)

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        if (plainText.startsWith("ENC:")) return plainText // Already encrypted
        try {
            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(SECRET_KEY, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            
            return "ENC:" + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            return plainText
        }
    }

    fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty() || !cipherText.startsWith("ENC:")) return cipherText
        
        try {
            val b64Str = cipherText.substring(4)
            val combined = Base64.decode(b64Str, Base64.NO_WRAP)
            
            val iv = ByteArray(16)
            System.arraycopy(combined, 0, iv, 0, 16)
            val encryptedBytes = ByteArray(combined.size - 16)
            System.arraycopy(combined, 16, encryptedBytes, 0, encryptedBytes.size)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(SECRET_KEY, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            return cipherText
        }
    }
}
