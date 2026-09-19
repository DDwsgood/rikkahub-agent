package me.rerere.rikkahub

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Translucent full-screen alert shown over the lock screen when a scheduled job fires —
 * the alarm-app pattern (Etar/AOSP DeskClock). Opt-in only: the worker posts it via
 * `setFullScreenIntent` when the "wake screen on fire" setting is enabled (default off).
 *
 * The lock-screen flags are deliberately stacked: `setShowWhenLocked`/`setTurnScreenOn`
 * (API 27+) AND the deprecated window flags. Several OEM builds honour only a subset of
 * them, so alarm apps apply all four deprecated flags alongside the newer calls.
 */
class CronJobWakeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        val jobName = intent.getStringExtra(EXTRA_JOB_NAME)
        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.cron_job_wake_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (!jobName.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(text = jobName, style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { finish() }) {
                        Text(stringResource(R.string.cron_job_wake_dismiss))
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_JOB_NAME = "cron_job_wake_name"
    }
}
