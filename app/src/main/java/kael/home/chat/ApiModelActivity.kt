package kael.home.chat

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import kael.home.chat.service.StorageService

class ApiModelActivity : AppCompatActivity() {
    private lateinit var storage: StorageService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_model)
        storage = StorageService(this)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        val input = findViewById<EditText>(R.id.apiModelInput)
        input.setText(storage.apiModel)
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val model = input.text?.toString()?.trim()
            if (!model.isNullOrEmpty()) {
                storage.apiModel = model
                finish()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
