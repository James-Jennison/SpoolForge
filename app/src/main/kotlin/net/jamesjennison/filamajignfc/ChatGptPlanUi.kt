package net.jamesjennison.filamajignfc

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

/**
 * Launched by the `spoolforge://chatgpt-signin` link on the sign-in completion page.
 * Its only job is to bring the existing SpoolForge task back to the front.
 * It reads nothing from the link; the sign-in result arrives over the loopback listener.
 */
class ChatGptReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
        finish()
    }
}

private fun openInBrowser(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** Sign-in, usage link, and disconnect for using a ChatGPT plan for AI label scanning. */
@Composable
fun ChatGptPlanCard(model: MainViewModel) {
    val state = model.chatGpt
    val context = LocalContext.current

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI label scanning", fontWeight = FontWeight.Bold)
            when {
                state.signingIn -> {
                    Text("Waiting for ChatGPT sign-in in your browser.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = model::cancelChatGptSignIn, modifier = Modifier.fillMaxWidth()) { Text("Cancel sign-in") }
                }
                !state.connected -> {
                    Text(
                        "Use your ChatGPT plan for faster label scans. Without it, scans use the local model.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = { model.signInWithChatGpt { url -> if (!openInBrowser(context, url)) model.cancelChatGptSignIn() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Continue with ChatGPT") }
                }
                else -> {
                    Text(
                        "Using your ChatGPT plan${state.email?.let { " · $it" }.orEmpty()}. Scans use $CHATGPT_LABEL_MODEL_NAME; the local model is the fallback.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.usageLimitReached) {
                            Button(onClick = { openInBrowser(context, CHATGPT_USAGE_URL) }, modifier = Modifier.weight(1f)) { Text("Manage usage") }
                        } else {
                            OutlinedButton(onClick = { openInBrowser(context, CHATGPT_USAGE_URL) }, modifier = Modifier.weight(1f)) { Text("Manage usage") }
                        }
                        OutlinedButton(onClick = model::disconnectChatGpt, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Disconnect") }
                    }
                }
            }
            state.message?.let { message ->
                Text(
                    message,
                    color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (state.showWelcome) {
        AlertDialog(
            onDismissRequest = model::dismissChatGptWelcome,
            title = { Text("You're using your ChatGPT plan") },
            text = { Text("Eligible usage in this app uses your ChatGPT plan. Manage usage in your ChatGPT settings.") },
            confirmButton = { TextButton(onClick = model::dismissChatGptWelcome) { Text("Got it") } },
            dismissButton = { TextButton(onClick = { openInBrowser(context, CHATGPT_USAGE_URL) }) { Text("Manage usage") } },
        )
    }
}
