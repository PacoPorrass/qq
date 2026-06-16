package com.empresa.vaultdrive.ui.browser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.empresa.vaultdrive.R
import com.empresa.vaultdrive.core.security.Prefs
import com.empresa.vaultdrive.core.security.PinnedFolder
import com.empresa.vaultdrive.core.session.TokenManager
import com.empresa.vaultdrive.data.model.FileItem
import com.empresa.vaultdrive.data.model.Result
import com.empresa.vaultdrive.data.repository.Repository
import com.empresa.vaultdrive.databinding.ActivityBrowserBinding
import com.empresa.vaultdrive.ui.auth.AuthActivity
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BrowserActivity : AppCompatActivity() {

    private lateinit var b: ActivityBrowserBinding
    private lateinit var repo: Repository
    private lateinit var adapter: FileAdapter

    data class BreadcrumbEntry(val item: FileItem, val driveId: String?)
    private val breadcrumb = ArrayDeque<BreadcrumbEntry>()
    private var currentFolder: FileItem? = null
    private var currentDriveId: String? = null

    private var pendingCameraUri: Uri? = null
    private var pendingCameraFile: File? = null

    // ── Launchers ──────────────────────────────────────────────────

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } }
                ?: listOfNotNull(data.data)
            if (uris.size == 1) askRenameAndUpload(uris[0])
            else uris.forEach { uploadFile(it, null) }
        }
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val uri = pendingCameraUri ?: return@registerForActivityResult
            val default = "Foto_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
            askRenameBeforeUpload(uri, default)
        }
        pendingCameraFile = null
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) openPicker() else snack("Se necesitan permisos de galería")
    }

    private val cameraPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else snack("Se necesita permiso de cámara")
    }

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)
        repo = Repository(this)
        setupRecycler()
        setupButtons()
        refreshPinnedChips()
        loadFolder("root", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (breadcrumb.isNotEmpty()) navigateUp() else super.onBackPressed()
    }

    // ── Setup ──────────────────────────────────────────────────────

    private fun setupRecycler() {
        adapter = FileAdapter(
            onClick = { item ->
                if (item.isFolder) {
                    val driveId = if (item.isShared) item.driveId else currentDriveId
                    openFolder(item, driveId)
                }
            },
            onLongClick = { item -> showItemMenu(item) }
        )
        b.rvFiles.layoutManager = LinearLayoutManager(this)
        b.rvFiles.adapter = adapter
        b.swipeRefresh.setOnRefreshListener { refresh() }
    }

    private fun setupButtons() {
        b.btnBack.setOnClickListener { navigateUp() }
        b.fabCamera.setOnClickListener { checkCameraAndLaunch() }
        b.fabUpload.setOnClickListener { checkPermAndPick() }
        b.fabNewFolder.setOnClickListener { showCreateFolderDialog() }
        b.tvUserName.text = Prefs.userName.substringBefore("@")
        b.btnSignOut.setOnClickListener { confirmSignOut() }
        b.btnShared.setOnClickListener { loadSharedFolders() }
    }

    // ── Chips de carpetas fijadas ──────────────────────────────────

    private fun refreshPinnedChips() {
        b.chipGroupPinned.removeAllViews()
        val pinned = Prefs.getPinnedFolders()
        if (pinned.isEmpty() || currentFolder != null) {
            b.chipGroupPinned.visibility = View.GONE
            b.tvPinnedLabel.visibility = View.GONE
            return
        }
        b.chipGroupPinned.visibility = View.VISIBLE
        b.tvPinnedLabel.visibility = View.VISIBLE

        pinned.forEach { folder ->
            val chip = Chip(this).apply {
                text = folder.name
                isClickable = true
                isCheckable = false
                chipIcon = getDrawable(if (folder.isShared) R.drawable.ic_folder_shared else R.drawable.ic_folder)
                chipIconSize = resources.getDimension(R.dimen.chip_icon_size)
                setChipBackgroundColorResource(R.color.accent_blue_dim)
                setTextColor(getColor(R.color.text_primary))
                chipStrokeWidth = 1f
                setChipStrokeColorResource(R.color.accent_blue)
                setOnClickListener {
                    openFolder(
                        FileItem(folder.id, folder.name, true, isShared = folder.isShared),
                        folder.driveId.ifBlank { null }
                    )
                }
                setOnLongClickListener {
                    AlertDialog.Builder(this@BrowserActivity, R.style.VaultDialog)
                        .setTitle("Quitar acceso rápido")
                        .setMessage("¿Quitar \"${folder.name}\" de los accesos rápidos?")
                        .setPositiveButton("Quitar") { _, _ ->
                            Prefs.unpinFolder(folder.id)
                            refreshPinnedChips()
                            snack("\"${folder.name}\" eliminado de accesos rápidos")
                        }
                        .setNegativeButton("Cancelar", null).show()
                    true
                }
            }
            b.chipGroupPinned.addView(chip)
        }
    }

    // ── Carpetas compartidas ───────────────────────────────────────

    private fun loadSharedFolders() {
        lifecycleScope.launch {
            b.swipeRefresh.isRefreshing = true
            val token = getToken() ?: return@launch
            when (val r = repo.getSharedWithMe(token)) {
                is Result.Success -> {
                    breadcrumb.clear()
                    currentFolder = FileItem("shared_root", "Compartido conmigo", true)
                    currentDriveId = null
                    b.tvCurrentFolder.text = "Compartido conmigo"
                    b.btnBack.visibility = View.VISIBLE
                    b.chipGroupPinned.visibility = View.GONE
                    b.tvPinnedLabel.visibility = View.GONE
                    adapter.submitList(r.data)
                    b.tvEmpty.visibility = if (r.data.isEmpty()) View.VISIBLE else View.GONE
                }
                is Result.Error -> snack(r.message)
            }
            b.swipeRefresh.isRefreshing = false
        }
    }

    // ── Navegación ─────────────────────────────────────────────────

    private fun openFolder(folder: FileItem, driveId: String?) {
        val cur = currentFolder
        if (cur != null) breadcrumb.addLast(BreadcrumbEntry(cur, currentDriveId))
        else breadcrumb.addLast(BreadcrumbEntry(FileItem("root", "Inicio", true), null))
        currentFolder = folder
        currentDriveId = driveId
        b.tvCurrentFolder.text = folder.name
        b.btnBack.visibility = View.VISIBLE
        b.chipGroupPinned.visibility = View.GONE
        b.tvPinnedLabel.visibility = View.GONE
        loadFolder(folder.id, driveId)
    }

    private fun navigateUp() {
        if (breadcrumb.isEmpty()) return
        val parent = breadcrumb.removeLast()
        currentFolder = if (parent.item.id == "root") null else parent.item
        currentDriveId = parent.driveId
        b.tvCurrentFolder.text = currentFolder?.name ?: "Mi OneDrive"
        b.btnBack.visibility = if (breadcrumb.isEmpty() && currentFolder == null) View.GONE else View.VISIBLE
        if (breadcrumb.isEmpty() && currentFolder == null) refreshPinnedChips()
        loadFolder(currentFolder?.id ?: "root", currentDriveId)
    }

    private fun refresh() = loadFolder(currentFolder?.id ?: "root", currentDriveId)

    private fun loadFolder(folderId: String, driveId: String?) {
        if (folderId == "shared_root") { loadSharedFolders(); return }
        lifecycleScope.launch {
            b.swipeRefresh.isRefreshing = true
            val token = getToken() ?: return@launch
            when (val r = repo.getChildren(token, folderId, driveId)) {
                is Result.Success -> {
                    adapter.submitList(r.data)
                    b.tvEmpty.visibility = if (r.data.isEmpty()) View.VISIBLE else View.GONE
                }
                is Result.Error -> snack(r.message)
            }
            b.swipeRefresh.isRefreshing = false
        }
    }

    // ── Menú ítem ──────────────────────────────────────────────────

    private fun showItemMenu(item: FileItem) {
        val isPinned = Prefs.isFolderPinned(item.id)
        val options = mutableListOf<String>()
        if (item.isFolder) {
            options.add(if (isPinned) "📌 Quitar acceso rápido" else "📌 Añadir a accesos rápidos")
        }
        options.add("✏️ Renombrar")

        AlertDialog.Builder(this, R.style.VaultDialog)
            .setTitle(item.name)
            .setItems(options.toTypedArray()) { _, idx ->
                when (options[idx]) {
                    "📌 Añadir a accesos rápidos" -> {
                        Prefs.pinFolder(PinnedFolder(
                            id = item.id,
                            name = item.name,
                            driveId = currentDriveId ?: "",
                            isShared = item.isShared
                        ))
                        refreshPinnedChips()
                        snack("\"${item.name}\" añadida a accesos rápidos ✓")
                    }
                    "📌 Quitar acceso rápido" -> {
                        Prefs.unpinFolder(item.id)
                        refreshPinnedChips()
                        snack("\"${item.name}\" eliminada de accesos rápidos")
                    }
                    "✏️ Renombrar" -> showRenameDialog(item)
                }
            }.show()
    }

    private fun showRenameDialog(item: FileItem) {
        val ext = if (!item.isFolder && item.name.contains('.')) ".${item.name.substringAfterLast('.')}" else ""
        val base = if (ext.isNotEmpty()) item.name.substringBeforeLast('.') else item.name
        val et = EditText(this).apply {
            setText(base); setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary)); background = null; selectAll()
        }
        AlertDialog.Builder(this, R.style.VaultDialog).setTitle("Renombrar")
            .setView(LinearLayout(this).apply { setPadding(64, 24, 64, 0); addView(et) })
            .setPositiveButton("Guardar") { _, _ ->
                val newName = et.text.toString().trim() + ext
                if (newName.isNotBlank() && newName != item.name) snack("Renombrado a \"$newName\"")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    // ── Crear carpeta ──────────────────────────────────────────────

    private fun showCreateFolderDialog() {
        val et = EditText(this).apply {
            hint = "Nombre de la carpeta"; setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary)); background = null
        }
        AlertDialog.Builder(this, R.style.VaultDialog).setTitle("Nueva carpeta")
            .setView(LinearLayout(this).apply { setPadding(64, 24, 64, 0); addView(et) })
            .setPositiveButton("Crear") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotBlank()) createFolder(name) else snack("El nombre no puede estar vacío")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun createFolder(name: String) {
        lifecycleScope.launch {
            val token = getToken() ?: return@launch
            when (val r = repo.createFolder(token, currentFolder?.id ?: "root", name)) {
                is Result.Success -> { snack("Carpeta \"$name\" creada ✓"); refresh() }
                is Result.Error -> snack(r.message)
            }
        }
    }

    // ── Cámara — CORREGIDO: crear carpeta cache antes del fichero ──

    private fun checkCameraAndLaunch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            launchCamera()
        else cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        try {
            // Asegurar que la carpeta caché existe
            val cacheDir = File(cacheDir, "camera_photos").also { it.mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val tmp = File(cacheDir, "cam_$stamp.jpg")
            pendingCameraFile = tmp
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tmp)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            snack("Error al abrir la cámara: ${e.message}")
        }
    }

    private fun askRenameBeforeUpload(uri: Uri, defaultName: String) {
        val base = defaultName.substringBeforeLast('.')
        val et = EditText(this).apply {
            setText(base); setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary)); background = null; selectAll()
        }
        AlertDialog.Builder(this, R.style.VaultDialog).setTitle("Nombre de la foto")
            .setMessage("Puedes cambiar el nombre antes de subir:")
            .setView(LinearLayout(this).apply { setPadding(64, 24, 64, 0); addView(et) })
            .setPositiveButton("Subir") { _, _ ->
                val name = (et.text.toString().trim().ifBlank { base }) + ".jpg"
                uploadFile(uri, name)
            }
            .setNegativeButton("Cancelar", null).setCancelable(false).show()
    }

    private fun askRenameAndUpload(uri: Uri) {
        val orig = getFileName(uri)
        val ext = if (orig.contains('.')) ".${orig.substringAfterLast('.')}" else ""
        val base = orig.substringBeforeLast('.')
        val et = EditText(this).apply {
            setText(base); setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary)); background = null; selectAll()
        }
        AlertDialog.Builder(this, R.style.VaultDialog).setTitle("Nombre del archivo")
            .setView(LinearLayout(this).apply { setPadding(64, 24, 64, 0); addView(et) })
            .setPositiveButton("Subir") { _, _ ->
                val name = (et.text.toString().trim().ifBlank { base }) + ext
                uploadFile(uri, name)
            }
            .setNegativeButton("Subir sin renombrar") { _, _ -> uploadFile(uri, null) }.show()
    }

    // ── Upload ─────────────────────────────────────────────────────

    private fun uploadFile(uri: Uri, customName: String?) {
        lifecycleScope.launch {
            val token = getToken() ?: return@launch
            val parentId = currentFolder?.id ?: "root"
            b.layoutUploadProgress.visibility = View.VISIBLE
            b.uploadBar.visibility = View.VISIBLE
            b.tvUploadPct.visibility = View.VISIBLE
            when (val r = repo.uploadFile(token, parentId, uri, customName) { pct ->
                runOnUiThread { b.uploadBar.progress = pct; b.tvUploadPct.text = "$pct%" }
            }) {
                is Result.Success -> { snack("\"${customName ?: "Archivo"}\" subido ✓"); refresh() }
                is Result.Error -> snack(r.message)
            }
            b.layoutUploadProgress.visibility = View.GONE
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun getFileName(uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) name = c.getString(idx) ?: name
        }
        return name
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this, R.style.VaultDialog).setTitle("Cerrar sesión")
            .setMessage("¿Seguro que quieres cerrar sesión?")
            .setPositiveButton("Sí") { _, _ -> TokenManager.signOut { startActivity(Intent(this, AuthActivity::class.java)); finish() } }
            .setNegativeButton("Cancelar", null).show()
    }

    private suspend fun getToken(): String? {
        if (Prefs.isTokenValid()) return Prefs.token
        val r = TokenManager.refreshSilently()
        if (r == null) { startActivity(Intent(this, AuthActivity::class.java)); finish() }
        return r
    }

    private fun checkPermAndPick() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
            openPicker() else permLauncher.launch(perms)
    }

    private fun openPicker() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "application/pdf"))
        }
        pickLauncher.launch(Intent.createChooser(i, "Seleccionar archivos"))
    }

    private fun snack(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()
}
