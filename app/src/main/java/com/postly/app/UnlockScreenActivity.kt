package com.postly.app

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity

class UnlockScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("UnlockScreenActivity", "UnlockScreenActivity started to wake screen and request keyguard dismiss")

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    super.onDismissSucceeded()
                    Log.d("UnlockScreenActivity", "Keyguard successfully dismissed by system")
                    finish()
                }

                override fun onDismissError() {
                    super.onDismissError()
                    Log.e("UnlockScreenActivity", "Keyguard dismiss error")
                }

                override fun onDismissCancelled() {
                    super.onDismissCancelled()
                    Log.d("UnlockScreenActivity", "Keyguard dismiss cancelled")
                }
            })
        }

        // Close after 4 seconds if still open
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) finish()
        }, 4000)
    }
}
