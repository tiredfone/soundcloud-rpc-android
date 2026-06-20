package com.tiredfone.soundcloudrpc

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tiredfone.soundcloudrpc.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var storage: TokenStorage

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == DiscordLoginActivity.RESULT_LOGGED_IN) {
            refreshAccountStatus()
            Toast.makeText(this, "Discord account connected!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "RPC Settings"

        storage = TokenStorage(this)

        binding.etAppId.setText(storage.applicationId ?: "")
        binding.switchEnabled.isChecked = storage.rpcEnabled

        binding.btnLogin.setOnClickListener {
            loginLauncher.launch(Intent(this, DiscordLoginActivity::class.java))
        }

        binding.btnLogout.setOnClickListener {
            storage.discordToken = null
            refreshAccountStatus()
        }

        binding.btnSave.setOnClickListener { save() }

        refreshAccountStatus()
    }

    private fun refreshAccountStatus() {
        val token = storage.discordToken
        if (token.isNullOrBlank()) {
            showLoggedOut()
        } else {
            showLoggedIn(null) // optimistic — fetch name async
            fetchUsername(token)
        }
    }

    private fun showLoggedOut() {
        binding.tvAccountStatus.text = "Not connected"
        binding.btnLogin.visibility = View.VISIBLE
        binding.btnLogout.visibility = View.GONE
    }

    private fun showLoggedIn(name: String?) {
        binding.tvAccountStatus.text = if (name != null) "Connected as $name" else "Connected"
        binding.btnLogin.visibility = View.GONE
        binding.btnLogout.visibility = View.VISIBLE
    }

    private fun fetchUsername(token: String) {
        lifecycleScope.launch {
            try {
                val username = withContext(Dispatchers.IO) {
                    val response = OkHttpClient().newCall(
                        Request.Builder()
                            .url("https://discord.com/api/v10/users/@me")
                            .header("Authorization", token)
                            .build()
                    ).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@withContext null
                        // Parse just the username field without a full JSON library call
                        val match = Regex(""""username"\s*:\s*"([^"]+)"""").find(body)
                        match?.groupValues?.get(1)
                    } else null
                }
                if (username != null) showLoggedIn("@$username")
            } catch (_: Exception) { /* network unavailable — keep "Connected" label */ }
        }
    }

    private fun save() {
        val appId = binding.etAppId.text.toString().trim()

        if (storage.discordToken.isNullOrBlank()) {
            Toast.makeText(this, "Please log in with Discord first", Toast.LENGTH_SHORT).show()
            return
        }
        if (appId.isEmpty()) {
            Toast.makeText(this, "Discord Application ID is required", Toast.LENGTH_SHORT).show()
            return
        }

        storage.applicationId = appId
        storage.rpcEnabled = binding.switchEnabled.isChecked

        if (storage.rpcEnabled) {
            startService(Intent(this, RpcService::class.java))
        } else {
            stopService(Intent(this, RpcService::class.java))
        }

        Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
