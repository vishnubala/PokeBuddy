package com.pokebuddy

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pokebuddy.capture.ScreenCaptureService
import com.pokebuddy.databinding.ActivityMainBinding

/**
 * FLAG_SECURE spike entry point. Requests the notification permission (Android 13+),
 * then the MediaProjection consent, then hands the grant token to the capture service.
 * Results from the service arrive back via an in-process broadcast.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager

    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — capture still works, just no visible FG notification if denied */
        launchProjectionConsent()
    }

    private val projectionConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val svc = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, svc)
            binding.result.text = "Capture armed. Switch to Pokémon GO, then use CAPTURE NOW in the notification."
        } else {
            binding.result.text = "Screen-capture permission denied."
        }
    }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val summary = intent?.getStringExtra(ScreenCaptureService.EXTRA_SUMMARY) ?: return
            binding.result.text = summary
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        binding.startButton.setOnClickListener { startFlow() }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, resultReceiver,
            IntentFilter(ScreenCaptureService.ACTION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(resultReceiver)
    }

    private fun startFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchProjectionConsent()
        }
    }

    private fun launchProjectionConsent() {
        projectionConsent.launch(projectionManager.createScreenCaptureIntent())
    }
}
