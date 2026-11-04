package com.metavirtuoso.conjunction.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.legal.LegalDoc
import com.metavirtuoso.conjunction.legal.LegalText
import com.metavirtuoso.conjunction.legal.LegalTexts
import com.metavirtuoso.conjunction.ui.components.FinePrint
import com.metavirtuoso.conjunction.ui.components.GoldButton
import com.metavirtuoso.conjunction.ui.components.GoldText
import com.metavirtuoso.conjunction.ui.components.MarkdownText
import com.metavirtuoso.conjunction.ui.components.OrnateDivider
import com.metavirtuoso.conjunction.ui.components.OrreryLoader
import com.metavirtuoso.conjunction.ui.components.StarfieldBackground
import com.metavirtuoso.conjunction.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full text of one bundled legal document, scrollable, in a full-screen dialog.
 * [onAcknowledge] adds a "Got it" button at the end (used for the Safety Tips record).
 */
@Composable
fun LegalDocDialog(doc: LegalDoc, onDismiss: () -> Unit, onAcknowledge: ((LegalText) -> Unit)? = null) {
    val context = LocalContext.current
    var text by remember(doc) { mutableStateOf<LegalText?>(null) }
    var error by remember(doc) { mutableStateOf<String?>(null) }
    LaunchedEffect(doc) {
        runCatching { withContext(Dispatchers.IO) { LegalTexts.load(context, doc) } }
            .onSuccess { text = it }
            .onFailure { error = "This document could not be opened." }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        StarfieldBackground(dim = 0.7f) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Palette.GoldPale)
                    }
                    GoldText(doc.title, style = MaterialTheme.typography.titleLarge, heading = true)
                }
                OrnateDivider(Modifier.align(Alignment.CenterHorizontally))
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .background(Palette.Midnight.copy(alpha = 0.78f)),
                ) {
                    when {
                        text != null -> Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
                            FinePrint("Version ${text!!.version}")
                            MarkdownText(text!!.markdown)
                            if (onAcknowledge != null) {
                                GoldButton(stringOrDefault(R.string.safety_tip_got_it), onClick = { onAcknowledge(text!!) }, modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
                            }
                        }
                        error != null -> Text(error!!, color = Palette.Moonstone, modifier = Modifier.align(Alignment.Center))
                        else -> OrreryLoader(Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

@Composable
private fun stringOrDefault(id: Int): String = androidx.compose.ui.res.stringResource(id)

/** Shown when the bundled document versions are newer than what was accepted. */
@Composable
fun ReacceptScreen(changed: List<LegalDoc>, onAccept: () -> Unit) {
    var open by remember { mutableStateOf<LegalDoc?>(null) }
    StarfieldBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GoldText(androidx.compose.ui.res.stringResource(R.string.legal_reaccept_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, heading = true)
            Text(
                androidx.compose.ui.res.stringResource(R.string.legal_reaccept_body, androidx.compose.ui.res.stringResource(R.string.app_name)),
                style = MaterialTheme.typography.bodyLarge, color = Palette.Moonstone, textAlign = TextAlign.Center,
            )
            changed.forEach { doc ->
                com.metavirtuoso.conjunction.ui.components.GhostButton("Read the ${doc.title}", onClick = { open = doc }, modifier = Modifier.fillMaxWidth())
            }
            GoldButton(androidx.compose.ui.res.stringResource(R.string.legal_continue), onClick = onAccept, modifier = Modifier.fillMaxWidth())
        }
    }
    open?.let { LegalDocDialog(it, onDismiss = { open = null }) }
}
