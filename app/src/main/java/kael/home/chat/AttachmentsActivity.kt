package kael.home.chat

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kael.home.chat.service.StorageService
import java.io.File

class AttachmentsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_attachments)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)?.let {
            setSupportActionBar(it)
            it.setNavigationOnClickListener { finish() }
        }
        val paths = mutableSetOf<String>()
        for (m in StorageService(this).getMessages()) {
            m.attachmentPath?.let { if (File(it).exists()) paths.add(it) }
        }
        val dir = File(filesDir, "attachments")
        if (dir.exists()) dir.listFiles()?.forEach { paths.add(it.absolutePath) }
        val list = paths.toList().sortedBy { it }
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = AttachmentsAdapter(list) { path ->
            try {
                val f = File(path)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, getMime(path))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (_: Exception) {}
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun getMime(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }
}

class AttachmentsAdapter(
    private val paths: List<String>,
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<AttachmentsAdapter.Holder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_attachment, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val path = paths[position]
        val name = path.substringAfterLast('/')
        holder.name.text = name
        try {
            val bmp = BitmapFactory.decodeFile(path)
            holder.thumb.setImageBitmap(bmp)
        } catch (_: Exception) {
            holder.thumb.setImageDrawable(null)
        }
        holder.itemView.setOnClickListener { onItemClick(path) }
    }

    override fun getItemCount(): Int = paths.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumb: ImageView = itemView.findViewById(R.id.thumb)
        val name: TextView = itemView.findViewById(R.id.name)
    }
}
