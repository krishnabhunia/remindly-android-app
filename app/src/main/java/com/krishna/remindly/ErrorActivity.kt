package com.krishna.remindly

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * v1.22 item 12: what Krishna sees instead of "Remindly has stopped".
 *
 * Everything shown here was already written to Error Logs before this screen appeared, so the
 * trace is safe whether or not he reads or sends anything from it.
 */
class ErrorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val location = intent?.getStringExtra(CrashGuard.EXTRA_LOCATION) ?: "unknown location"
        val type = intent?.getStringExtra(CrashGuard.EXTRA_TYPE) ?: "Error"
        val message = intent?.getStringExtra(CrashGuard.EXTRA_MESSAGE) ?: ""
        val screen = intent?.getStringExtra(CrashGuard.EXTRA_SCREEN) ?: "—"
        setContent { RemindlyTheme { ErrorScreen(location, type, message, screen) } }
    }
}

@Composable
private fun ErrorScreen(location: String, type: String, message: String, screen: String) {
    val context = LocalContext.current
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    val details = buildString {
        append("Remindly v").append(version).append('\n')
        append(CrashGuard.deviceLine()).append('\n')
        append("Where: ").append(location).append('\n')
        append("Screen: ").append(screen).append('\n')
        append(type)
        if (message.isNotBlank()) append(": ").append(message)
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.padding(top = 20.dp))
            Text(
                "Something went wrong",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Remindly caught the error and saved it. Nothing you had already entered is lost.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSubtle
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceSubtle)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Where it happened", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(location, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(2.dp))
                Text("What happened", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(type, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                if (message.isNotBlank()) Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSubtle
                )
                Spacer(Modifier.padding(2.dp))
                Text("Screen: $screen", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                Text("Remindly v$version", style = MaterialTheme.typography.bodySmall, color = InkSubtle)
                Text(CrashGuard.deviceLine(), style = MaterialTheme.typography.bodySmall, color = InkSubtle)
            }

            Button(
                onClick = { runCatching { context.startActivity(CrashGuard.restartIntent(context)) } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restart Remindly", fontWeight = FontWeight.Bold) }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Remindly error", details))
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Copy Details") }

                OutlinedButton(
                    onClick = {
                        runCatching {
                            val subject = "[Remindly v$version] Crash"
                            val body = details + "\n\n--- Describe what you were doing ---\n\n"
                            val uri = Uri.parse(
                                "mailto:kri.subsc@gmail.com?subject=" + Uri.encode(subject) +
                                    "&body=" + Uri.encode(body)
                            )
                            context.startActivity(Intent(Intent.ACTION_SENDTO, uri))
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Email Report") }
            }

            Text(
                "The full trace is in Settings → Error Logs.",
                style = MaterialTheme.typography.labelSmall,
                color = InkHint
            )
        }
    }
}
