package com.tiredfone.soundcloudrpc

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storage = TokenStorage(this)
        if (storage.isSoundCloudLoggedIn()) {
            startActivity(Intent(this, HomeActivity::class.java))
        } else {
            startActivity(Intent(this, SoundCloudLoginActivity::class.java))
        }
        finish()
    }
}
