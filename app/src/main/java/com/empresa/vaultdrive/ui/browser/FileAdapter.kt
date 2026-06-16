package com.empresa.vaultdrive.ui.browser
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.empresa.vaultdrive.R
import com.empresa.vaultdrive.data.model.FileItem
import com.empresa.vaultdrive.databinding.ItemFileBinding
import java.text.SimpleDateFormat
import java.util.Locale

class FileAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Unit
) : ListAdapter<FileItem, FileAdapter.VH>(Diff()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemFileBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: FileItem) {
            b.tvName.text = item.name
            b.tvMeta.text = buildMeta(item)
            when {
                item.isFolder -> {
                    b.ivIcon.setImageResource(if (item.isShared) R.drawable.ic_folder_shared else R.drawable.ic_folder)
                    b.ivIcon.setPadding(10,10,10,10)
                    b.ivChevron.visibility = View.VISIBLE
                    b.tvChildCount.visibility = if (item.childCount > 0) View.VISIBLE else View.GONE
                    b.tvChildCount.text = "${item.childCount}"
                }
                item.mimeType?.startsWith("image/") == true -> {
                    b.ivChevron.visibility = View.GONE; b.tvChildCount.visibility = View.GONE
                    if (!item.downloadUrl.isNullOrBlank()) {
                        b.ivIcon.setPadding(0,0,0,0)
                        b.ivIcon.load(item.downloadUrl) {
                            crossfade(true)
                            transformations(RoundedCornersTransformation(12f))
                            placeholder(R.drawable.ic_image)
                            error(R.drawable.ic_image)
                        }
                    } else {
                        b.ivIcon.setPadding(10,10,10,10)
                        b.ivIcon.setImageResource(R.drawable.ic_image)
                    }
                }
                else -> {
                    b.ivChevron.visibility = View.GONE; b.tvChildCount.visibility = View.GONE
                    b.ivIcon.setPadding(10,10,10,10)
                    b.ivIcon.setImageResource(iconForMime(item.mimeType, item.name))
                }
            }
            b.root.setOnClickListener { onClick(item) }
            b.root.setOnLongClickListener { onLongClick(item); true }
        }

        private fun buildMeta(item: FileItem): String {
            val parts = mutableListOf<String>()
            if (item.isShared) parts.add("Compartido")
            if (!item.isFolder && item.size > 0) parts.add(formatSize(item.size))
            if (item.lastModified.isNotBlank()) parts.add(formatDate(item.lastModified))
            return parts.joinToString(" · ")
        }

        private fun formatSize(b: Long) = when {
            b < 1_024 -> "$b B"
            b < 1_048_576 -> "${b/1024} KB"
            b < 1_073_741_824 -> "%.1f MB".format(b/1_048_576f)
            else -> "%.1f GB".format(b/1_073_741_824f)
        }

        private fun formatDate(iso: String): String = try {
            val p = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
            val f = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            f.format(p.parse(iso)!!)
        } catch (_: Exception) { iso.take(10) }

        private fun iconForMime(mime: String?, name: String) = when {
            mime?.startsWith("video/") == true -> R.drawable.ic_video
            mime == "application/pdf" -> R.drawable.ic_pdf
            mime?.contains("word") == true || name.endsWith(".doc") || name.endsWith(".docx") -> R.drawable.ic_doc
            mime?.contains("excel") == true || name.endsWith(".xls") || name.endsWith(".xlsx") -> R.drawable.ic_xls
            mime?.contains("zip") == true -> R.drawable.ic_zip
            else -> R.drawable.ic_file
        }
    }

    class Diff : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(a: FileItem, b: FileItem) = a.id == b.id
        override fun areContentsTheSame(a: FileItem, b: FileItem) = a == b
    }
}
