package app.tiredfone.sclient

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.tiredfone.sclient.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnContinue.setOnClickListener {
            getSharedPreferences("rpc_prefs", MODE_PRIVATE)
                .edit().putBoolean("onboarding_done", true).apply()
            val storage = TokenStorage(this)
            if (storage.isSoundCloudLoggedIn()) {
                startActivity(Intent(this, HomeActivity::class.java))
            } else {
                startActivity(Intent(this, SoundCloudLoginActivity::class.java))
            }
            finish()
        }
    }
}
