package com.empresa.vaultdrive.data.repository
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.empresa.vaultdrive.data.api.OneDriveApi
import com.empresa.vaultdrive.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class Repository(private val context: Context) {
    private val api = OneDriveApi.create()
    private val uploadClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
    private fun bearer(t: String) = "Bearer $t"

    suspend fun getChildren(token: String, folderId: String, driveId: String? = null): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val resp = if (!driveId.isNullOrBlank()) api.getChildrenByDrive(bearer(token), driveId, folderId)
                       else api.getChildren(bearer(token), folderId)
            if (resp.isSuccessful) Result.Success(resp.body()?.value?.map { it.toFileItem() }?.sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() })) ?: emptyList())
            else Result.Error("Error ${resp.code()}: ${resp.message()}")
        } catch (e: Exception) { Result.Error(e.message ?: "Error de red") }
    }

    suspend fun getSharedWithMe(token: String): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val resp = api.getSharedWithMe(bearer(token))
            if (resp.isSuccessful) Result.Success(resp.body()?.value?.map { it.toFileItem() }?.sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() })) ?: emptyList())
            else Result.Error("Error ${resp.code()}: ${resp.message()}")
        } catch (e: Exception) { Result.Error(e.message ?: "Error de red") }
    }

    suspend fun createFolder(token: String, parentId: String, name: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val resp = api.createFolder(bearer(token), parentId, CreateFolderRequest(name = name.trim()))
            if (resp.isSuccessful) Result.Success(resp.body()!!.toFileItem()) else Result.Error("Error ${resp.code()}")
        } catch (e: Exception) { Result.Error(e.message ?: "Error") }
    }

    suspend fun uploadFile(token: String, parentId: String, uri: Uri, customName: String? = null, onProgress: (Int) -> Unit = {}): Result<FileItem> = withContext(Dispatchers.IO) {
        var tmp: File? = null
        try {
            val finalName = customName?.ifBlank { null } ?: getFileName(uri)
            tmp = File(context.cacheDir, "up_${System.currentTimeMillis()}")
            context.contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o) } }
                ?: return@withContext Result.Error("No se puede abrir el archivo")
            if (tmp.length() <= 4 * 1024 * 1024) {
                onProgress(10)
                val resp = api.uploadSmall(bearer(token), parentId, finalName, tmp.asRequestBody(getMime(finalName).toMediaType()))
                onProgress(100)
                if (resp.isSuccessful) Result.Success(resp.body()!!.toFileItem()) else Result.Error("Error ${resp.code()}")
            } else uploadLarge(token, parentId, finalName, tmp, onProgress)
        } catch (e: Exception) { Result.Error(e.message ?: "Error al subir") } finally { tmp?.delete() }
    }

    private suspend fun uploadLarge(token: String, parentId: String, name: String, file: File, onProgress: (Int) -> Unit): Result<FileItem> {
        val sessionResp = api.createUploadSession(bearer(token), parentId, name, UploadSessionRequest())
        if (!sessionResp.isSuccessful) return Result.Error("No se pudo crear sesión")
        val uploadUrl = sessionResp.body()!!.uploadUrl
        val fileSize = file.length(); val chunkSize = 5 * 1024 * 1024; var offset = 0L
        file.inputStream().use { stream ->
            while (offset < fileSize) {
                val len = minOf(chunkSize.toLong(), fileSize - offset).toInt()
                val chunk = ByteArray(len).also { stream.read(it) }
                val req = Request.Builder().url(uploadUrl).put(chunk.toRequestBody("application/octet-stream".toMediaType())).header("Content-Range", "bytes $offset-${offset + len - 1}/$fileSize").build()
                val resp = uploadClient.newCall(req).execute()
                if (!resp.isSuccessful && resp.code != 202) return Result.Error("Error en fragmento: ${resp.code}")
                offset += len; onProgress(((offset.toFloat() / fileSize) * 100).toInt())
            }
        }
        return Result.Success(FileItem(id = "", name = name, isFolder = false, size = fileSize))
    }

    private fun getFileName(uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) name = c.getString(idx) ?: name
        }
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
    }

    private fun getMime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"
        "mp4" -> "video/mp4"; "pdf" -> "application/pdf"; else -> "application/octet-stream"
    }
}
