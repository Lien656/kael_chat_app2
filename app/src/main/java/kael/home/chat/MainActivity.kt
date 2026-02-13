package kael.home.chat

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import kael.home.chat.service.StorageService

class MainActivity : AppCompatActivity() {
    private lateinit var storage: StorageService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        storage = StorageService(this)
        if (storage.isFirstRun) {
            showFragment(ApiKeyFragment.newInstance(storage) {
                storage.setFirstRunDone()
                openChat()
            })
        } else {
            if (storage.getMessages().isEmpty() && storage.restoreFromBackupIfEmpty()) {
                Toast.makeText(this, R.string.chat_restored_from_backup, Toast.LENGTH_LONG).show()
            }
            openChat()
        }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }

    private fun openChat() {
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }
}
