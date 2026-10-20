package com.my.autotradingapp.utils

import android.content.Context

object ProfileSecurityManager {

    /**
     * Checks if the given profile is unlocked on this specific device.
     * Returns true if no PIN is set, or if the correct PIN was previously saved on this device.
     */
    fun isProfileUnlocked(context: Context, profileId: String, encryptedPin: String): Boolean {
        if (encryptedPin.isEmpty()) return true
        val prefs = context.getSharedPreferences("TradingApp", Context.MODE_PRIVATE)
        val savedPin = prefs.getString("SavedPin_$profileId", "") ?: ""
        val savedStockPin = prefs.getString("SavedPin_stock_$profileId", "") ?: ""

        if (savedPin == encryptedPin || savedStockPin == encryptedPin) return true

        val decrypted = try {
            CryptoUtils.decrypt(encryptedPin)
        } catch (e: Exception) {
            encryptedPin
        }

        return (savedPin.isNotEmpty() && savedPin == decrypted) ||
               (savedStockPin.isNotEmpty() && savedStockPin == decrypted)
    }

    /**
     * Verifies user input PIN against the profile's encrypted PIN.
     */
    fun verifyPin(enteredPin: String, encryptedPin: String): Boolean {
        if (encryptedPin.isEmpty()) return true
        val decrypted = try {
            CryptoUtils.decrypt(encryptedPin)
        } catch (e: Exception) {
            encryptedPin
        }
        return enteredPin.trim() == decrypted.trim()
    }

    /**
     * Saves the profile PIN on this device so that subsequent accesses do not require entering the PIN.
     */
    fun saveProfilePin(context: Context, profileId: String, encryptedPin: String) {
        val prefs = context.getSharedPreferences("TradingApp", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("SavedPin_$profileId", encryptedPin)
            .putString("SavedPin_stock_$profileId", encryptedPin)
            .apply()
    }

    /**
     * Clears the saved PIN on this device.
     */
    fun clearProfilePin(context: Context, profileId: String) {
        val prefs = context.getSharedPreferences("TradingApp", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("SavedPin_$profileId")
            .remove("SavedPin_stock_$profileId")
            .apply()
    }
}
