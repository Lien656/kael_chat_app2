package kael.home.chat

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import kael.home.chat.service.StorageService

class VisionApiActivity : AppCompatActivity() {
    private lateinit var storage: StorageService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vision_api)
        storage = StorageService(this)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        findViewById<EditText>(R.id.visionKeyInput).setText(storage.visionApiKey ?: "")
        findViewById<EditText>(R.id.visionBaseInput).setText(storage.visionApiBase)
        findViewById<EditText>(R.id.visionModelInput).setText(storage.visionModel)
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val key = findViewById<EditText>(R.id.visionKeyInput).text?.toString()?.trim()
            storage.visionApiKey = key
            storage.visionApiBase = findViewById<EditText>(R.id.visionBaseInput).text?.toString()?.trim() ?: storage.visionApiBase
            storage.visionModel = findViewById<EditText>(R.id.visionModelInput).text?.toString()?.trim() ?: "gpt-4o-mini"
            finish()
        }
    }
}
