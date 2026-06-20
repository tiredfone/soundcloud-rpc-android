package app.tiredfone.sclient

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.tiredfone.sclient.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var storage: TokenStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "RPC Settings"

        storage = TokenStorage(this)

        // SoundCloud section
        updateSoundCloudStatus()
        binding.btnDisconnectSoundCloud.setOnClickListener {
            storage.soundcloudToken = null
            storage.soundcloudClientId = null
            val intent = Intent(this, SoundCloudLoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finishAffinity()
        }

        binding.etToken.setText(storage.discordToken ?: "")
        binding.etAppId.setText(storage.applicationId ?: "")
        binding.switchEnabled.isChecked = storage.rpcEnabled

        // Toggle instructions visibility
        binding.btnShowInstructions.setOnClickListener {
            val visible = binding.cardInstructions.visibility == View.VISIBLE
            binding.cardInstructions.visibility = if (visible) View.GONE else View.VISIBLE
            binding.btnShowInstructions.text = if (visible) "How to get your token ▼" else "How to get your token ▲"
        }

        binding.btnSave.setOnClickListener { save() }

        // If we already have a token, show the username
        storage.discordToken?.let { fetchAndShowUsername(it) }
    }

    private fun updateSoundCloudStatus() {
        if (storage.isSoundCloudLoggedIn()) {
            binding.tvSoundCloudStatus.text = "Connected"
            binding.tvSoundCloudStatus.setTextColor(getColor(android.R.color.holo_green_light))
            binding.btnDisconnectSoundCloud.visibility = View.VISIBLE
        } else {
            binding.tvSoundCloudStatus.text = "Not connected"
            binding.tvSoundCloudStatus.setTextColor(getColor(R.color.text_secondary))
            binding.btnDisconnectSoundCloud.visibility = View.GONE
        }
    }

    private fun save() {
        val token = binding.etToken.text.toString().trim()
        val appId = binding.etAppId.text.toString().trim()

        if (token.isEmpty()) {
            Toast.makeText(this, "Paste your Discord token first", Toast.LENGTH_SHORT).show()
            return
        }
        if (appId.isEmpty()) {
            Toast.makeText(this, "Discord Application ID is required", Toast.LENGTH_SHORT).show()
            return
        }

        storage.discordToken = token
        storage.applicationId = appId
        storage.rpcEnabled = binding.switchEnabled.isChecked

        if (storage.rpcEnabled) {
            startService(Intent(this, RpcService::class.java))
        } else {
            stopService(Intent(this, RpcService::class.java))
        }

        fetchAndShowUsername(token)
        Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun fetchAndShowUsername(token: String) {
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    val res = OkHttpClient().newCall(
                        Request.Builder()
                            .url("https://discord.com/api/v10/users/@me")
                            .header("Authorization", token)
                            .build()
                    ).execute()
                    if (!res.isSuccessful) return@runCatching null
                    val body = res.body?.string() ?: return@runCatching null
                    Regex(""""username"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                }.getOrNull()
            }
            if (name != null) {
                binding.tvTokenStatus.text = "Logged in as @$name"
                binding.tvTokenStatus.visibility = View.VISIBLE
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
