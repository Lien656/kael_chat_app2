package kael.home.chat

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kael.home.chat.service.StorageService

class SettingsActivity : AppCompatActivity() {
    private lateinit var storage: StorageService

    private val restoreChatLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val content = input.bufferedReader().readText()
                val list = storage.loadBackupFromStream(content)
                if (!list.isNullOrEmpty()) {
                    val toSave = if (list.size > StorageService.MAX_STORED) list.takeLast(StorageService.MAX_STORED) else list
                    storage.saveMessagesSync(toSave)
                    Toast.makeText(this, R.string.restore_chat_done, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, R.string.restore_chat_error, Toast.LENGTH_SHORT).show()
                }
            } ?: Toast.makeText(this, R.string.restore_chat_error, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.restore_chat_error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        storage = StorageService(this)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val list = findViewById<ListView>(R.id.list)
        // Заголовок с переключателем «Вибрация при ответе»
        val headerView = LayoutInflater.from(this).inflate(R.layout.item_settings_switch, list, false)
        val titleView = headerView.findViewById<android.widget.TextView>(R.id.title)
        titleView.text = getString(R.string.vibration_on_reply)
        val switchVibration = headerView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchVibration)
        switchVibration.isChecked = storage.vibrationOnReply
        switchVibration.setOnCheckedChangeListener { _, isChecked ->
            storage.vibrationOnReply = isChecked
        }
        list.addHeaderView(headerView, null, false)
        val items = listOf(
            getString(R.string.change_api_key),
            getString(R.string.export_chat),
            getString(R.string.restore_chat_from_file),
            getString(R.string.change_api_url),
            getString(R.string.change_api_model),
            getString(R.string.attachments),
            getString(R.string.console)
        )
        list.adapter = ArrayAdapter(this, R.layout.item_settings, items)
        list.setOnItemClickListener { _, _, position, _ ->
            val index = position - 1
            if (index < 0) return@setOnItemClickListener
            when (index) {
                0 -> startActivity(Intent(this, ApiKeyActivity::class.java).apply {
                    putExtra(ApiKeyActivity.EXTRA_FROM_SETTINGS, true)
                })
                1 -> exportChat()
                2 -> restoreChatLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                3 -> startActivity(Intent(this, ApiUrlActivity::class.java))
                4 -> startActivity(Intent(this, ApiModelActivity::class.java))
                5 -> startActivity(Intent(this, AttachmentsActivity::class.java))
                6 -> startActivity(Intent(this, ConsoleActivity::class.java))
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
