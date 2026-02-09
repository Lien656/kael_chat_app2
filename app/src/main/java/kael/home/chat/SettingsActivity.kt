package kael.home.chat

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
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
            getString(R.string.change_api_url),
            getString(R.string.console),
            getString(R.string.attachments)
        )
        list.adapter = ArrayAdapter(this, R.layout.item_settings, items)
        list.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> startActivity(Intent(this, ApiKeyActivity::class.java).apply {
                    putExtra(ApiKeyActivity.EXTRA_FROM_SETTINGS, true)
                })
                1 -> startActivity(Intent(this, ApiUrlActivity::class.java))
                2 -> startActivity(Intent(this, ConsoleActivity::class.java))
                3 -> startActivity(Intent(this, AttachmentsActivity::class.java))
            }
        }
    }
}
