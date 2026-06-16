package com.empresa.vaultdrive.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class PinnedFolder(
    val id: String,
    val name: String,
    val driveId: String = "",
    val isShared: Boolean = false
)

object Prefs {
    private const val FILE = "vd_prefs_v3"
    private var sp: SharedPreferences? = null
    private val gson = Gson()

    fun init(context: Context) {
        sp = try {
            val key = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(FILE, key, context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        } catch (e: Exception) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    private fun p() = sp!!

    var token: String?
        get() = p().getString("token", null)
        set(v) = p().edit().putString("token", v).apply()

    var tokenExpiry: Long
        get() = p().getLong("token_exp", 0L)
        set(v) = p().edit().putLong("token_exp", v).apply()

    var userName: String
        get() = p().getString("user_name", "") ?: ""
        set(v) = p().edit().putString("user_name", v).apply()

    fun getPinnedFolders(): List<PinnedFolder> {
        val json = p().getString("pinned_folders", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<PinnedFolder>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun savePinnedFolders(folders: List<PinnedFolder>) {
        p().edit().putString("pinned_folders", gson.toJson(folders)).apply()
    }

    fun pinFolder(folder: PinnedFolder) {
        val current = getPinnedFolders().toMutableList()
        if (current.none { it.id == folder.id }) {
            current.add(folder)
            savePinnedFolders(current)
        }
    }

    fun unpinFolder(folderId: String) {
        savePinnedFolders(getPinnedFolders().filter { it.id != folderId })
    }

    fun isFolderPinned(folderId: String) = getPinnedFolders().any { it.id == folderId }

    fun isTokenValid(): Boolean {
        val t = token ?: return false
        return t.isNotBlank() && tokenExpiry > System.currentTimeMillis() + 5 * 60 * 1000
    }

    fun clear() = p().edit().clear().apply()
}
