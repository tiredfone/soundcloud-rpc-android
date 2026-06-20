package com.tiredfone.soundcloudrpc

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tiredfone.soundcloudrpc.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var storage: TokenStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        storage = TokenStorage(this)

        binding.etToken.setText(storage.discordToken ?: "")
        binding.etAppId.setText(storage.applicationId ?: "")
        binding.switchEnabled.isChecked = storage.rpcEnabled

        binding.btnSave.setOnClickListener { save() }
    }

    private fun save() {
        val token = binding.etToken.text.toString().trim()
        val appId = binding.etAppId.text.toString().trim()
        val enabled = binding.switchEnabled.isChecked

        if (token.isEmpty() || appId.isEmpty()) {
            Toast.makeText(this, "Token and Application ID are required", Toast.LENGTH_SHORT).show()
            return
        }

        storage.discordToken = token
        storage.applicationId = appId
        storage.rpcEnabled = enabled

        if (enabled) {
            startService(Intent(this, RpcService::class.java))
        } else {
            stopService(Intent(this, RpcService::class.java))
        }

        Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
