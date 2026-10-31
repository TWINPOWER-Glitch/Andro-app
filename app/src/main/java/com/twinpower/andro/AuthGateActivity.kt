package com.twinpower.andro

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.Github
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch

class AuthGateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AndroSupabase.client.handleDeeplinks(intent)

        setContent {
            AndroAuthScreen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        setIntent(intent)

        AndroSupabase.client.handleDeeplinks(intent)
    }
}

@Composable
private fun AndroAuthScreen() {

    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var loading by remember {
        mutableStateOf(true)
    }

    var signingIn by remember {
        mutableStateOf(false)
    }

    var authenticated by remember {
        mutableStateOf(false)
    }

    var message by remember {
        mutableStateOf("Checking your Andro account...")
    }

    var navigated by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(Unit) {

        AndroSupabase.client.auth.sessionStatus.collect { status ->

            when (status) {

                SessionStatus.Initializing -> {
                    loading = true
                    signingIn = false
                    message = "Restoring your saved account session..."
                }

                is SessionStatus.Authenticated -> {

                    loading = false
                    signingIn = false
                    authenticated = true

                    message = "Account authenticated."

                    if (!navigated) {

                        navigated = true

                        context.startActivity(
                            Intent(
                                context,
                                MainActivity::class.java
                            ).apply {
                                flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                        )

                        activity?.finish()
                    }
                }

                is SessionStatus.NotAuthenticated -> {

                    loading = false
                    authenticated = false
                    signingIn = false

                    message = "Sign in to your Andro account."
                }

                is SessionStatus.RefreshFailure -> {

                    loading = true
                    authenticated = false

                    message =
                        "Your saved session could not currently be refreshed. Check your internet connection while Andro retries."
                }
            }
        }
    }

    MaterialTheme {

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {

                Text(
                    "ANDRO",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 5.sp
                )

                Spacer(
                    Modifier.height(12.dp)
                )

                Text(
                    "Your Android development environment",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(
                    Modifier.height(36.dp)
                )

                Text(
                    "CUSTOMER ACCOUNT",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(
                    Modifier.height(12.dp)
                )

                Text(
                    "Sign in with GitHub to establish your personal Andro identity. Your devices and MCP permissions will belong to this account.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(
                    Modifier.height(24.dp)
                )

                if (loading || authenticated) {

                    CircularProgressIndicator()

                } else {

                    Button(
                        onClick = {

                            scope.launch {

                                signingIn = true

                                message = "Opening GitHub sign-in..."

                                runCatching {

                                    AndroSupabase.client.auth.signInWith(
                                        Github
                                    )

                                }.onFailure { error ->

                                    signingIn = false

                                    message =
                                        error.message
                                            ?: "GitHub sign-in could not start."
                                }
                            }
                        },
                        enabled = !signingIn && !navigated,
                        modifier = Modifier.fillMaxWidth()
                    ) {

                        Text(
                            if (signingIn) {
                                "Connecting..."
                            } else {
                                "Continue with GitHub"
                            }
                        )
                    }
                }

                Spacer(
                    Modifier.height(20.dp)
                )

                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(
                    Modifier.height(32.dp)
                )

                Text(
                    "Your GitHub account authenticates you to Andro. It does not give Andro unrestricted control of your GitHub account.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
