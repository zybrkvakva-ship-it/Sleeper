package com.sleeper.app.ui.screen.privacy

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sleeper.app.R
import com.sleeper.app.ui.theme.BgMain
import com.sleeper.app.ui.theme.CyberGray
import com.sleeper.app.ui.theme.CyberGreen
import com.sleeper.app.ui.theme.CyberWhite

@Composable
fun PrivacyScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val privacyUrl = stringResource(R.string.privacy_policy_url).trim()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgMain)
            .padding(24.dp)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = stringResource(R.string.privacy_title),
            style = MaterialTheme.typography.titleLarge,
            color = CyberWhite
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.privacy_body),
            style = MaterialTheme.typography.bodyMedium,
            color = CyberGray,
            textAlign = TextAlign.Start
        )
        if (privacyUrl.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(privacyUrl)))
                    } catch (_: Exception) { }
                }
            ) {
                Text(
                    text = stringResource(R.string.privacy_open_browser),
                    color = CyberGreen
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onBack) {
            Text(text = stringResource(R.string.privacy_back), color = CyberGreen)
        }
    }
}
