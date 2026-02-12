package kael.home.chat

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kael.home.chat.service.StorageService

class SettingsActivity : AppCompatActivity() {
    private lateinit var storage: StorageService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        storage = StorageService(this)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val list = findViewById<ListView>(R.id.list)
        val items = listOf(
            getString(R.string.change_api_key),
            getString(R.string.export_chat),
            getString(R.string.change_api_url),
            getString(R.string.attachments),
            getString(R.string.console)
        )
        list.adapter = ArrayAdapter(this, R.layout.item_settings, items)
        list.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> startActivity(Intent(this, ApiKeyActivity::class.java).apply {
                    putExtra(ApiKeyActivity.EXTRA_FROM_SETTINGS, true)
                })
                1 -> exportChat()
                2 -> startActivity(Intent(this, ApiUrlActivity::class.java))
                3 -> startActivity(Intent(this, AttachmentsActivity::class.java))
                4 -> startActivity(Intent(this, ConsoleActivity::class.java))
            }
        }
    }

    private fun exportChat() {
        val file = storage.exportChatToFile()
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.export_chat_error, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_chat)))
        Toast.makeText(this, R.string.export_chat_done, Toast.LENGTH_SHORT).show()
    }
}
