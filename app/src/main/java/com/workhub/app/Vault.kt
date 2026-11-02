package com.workhub.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class Vault(ctx: Context) {
    private val sp = EncryptedSharedPreferences.create(
        ctx,
        "vault",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun get(host: String): Pair<String, String>? {
        val v = sp.getString(host, null) ?: return null
        val i = v.indexOf('\u0000')
        return if (i < 0) null else v.substring(0, i) to v.substring(i + 1)
    }

    fun put(host: String, user: String, pw: String) {
        sp.edit().putString(host, user + "\u0000" + pw).apply()
    }

    fun remove(host: String) {
        sp.edit().remove(host).apply()
    }
}
