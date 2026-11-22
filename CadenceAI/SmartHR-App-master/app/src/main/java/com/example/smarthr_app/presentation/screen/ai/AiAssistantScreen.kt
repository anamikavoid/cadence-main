package com.example.smarthr_app.presentation.screen.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.smarthr_app.data.model.AiPendingActionDto
import com.example.smarthr_app.presentation.theme.PrimaryPurple
import com.example.smarthr_app.presentation.viewmodel.AiChatMessage
import com.example.smarthr_app.presentation.viewmodel.AiViewModel

/**
 * The AI assistant chat screen (Jetpack Compose).
 *
 * COMPOSE IN ONE PARAGRAPH: a @Composable function DESCRIBES the screen for the current state.
 * When the state changes, Compose calls it again and updates what changed. Here the state is
 * [AiViewModel.uiState]: the list of messages, whether we are waiting for the server, and any
 * action waiting for confirmation. The screen never changes that state itself; it calls
 * viewModel.sendMessage(...) / respondToPending(...) and redraws when the new state arrives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantScreen(
    viewModel: AiViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Runs once when the screen opens: load this user's saved conversation from the server.
    LaunchedEffect(Unit) { viewModel.loadHistory() }

    // Keep the newest message (or the "thinking" bubble) in view.
    LaunchedEffect(state.messages.size, state.isLoading) {
        val count = state.messages.size + if (state.isLoading) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text("AI Assistant", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showClearDialog = true }, enabled = state.messages.isNotEmpty() && !state.isLoading) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear conversation")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding() // lifts the input above the on-screen keyboard
        ) {
            if (state.messages.isEmpty() && !state.isLoading) {
                WelcomeContent(
                    onExample = { viewModel.sendMessage(it) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.messages, key = { it.id }) { message -> MessageBubble(message) }
                    if (state.isLoading) {
                        item { ThinkingBubble() }
                    }
                }
            }

            // A proposed action waits here until the user confirms or cancels it.
            state.pendingAction?.let { pending ->
                PendingActionCard(
                    pending = pending,
                    enabled = !state.isLoading,
                    onConfirm = { viewModel.respondToPending(true) },
                    onCancel = { viewModel.respondToPending(false) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= 1000) input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask about your schedule…") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val canSend = input.isNotBlank() && !state.isLoading
                IconButton(
                    onClick = {
                        viewModel.sendMessage(input)
                        input = ""
                    },
                    enabled = canSend
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) PrimaryPurple else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear conversation?") },
            text = { Text("This deletes your chat with the assistant. Your events are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    viewModel.clearConversation()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Keep") } }
        )
    }
}

/** One chat bubble. User messages sit on the right in purple, assistant messages on the left, errors in red. */
@Composable
private fun MessageBubble(message: AiChatMessage) {
    val isUser = message.fromUser
    val container = when {
        message.isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> PrimaryPurple
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        message.isError -> MaterialTheme.colorScheme.onErrorContainer
        isUser -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = container,
            contentColor = content,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
                // Clear indication that something was actually done (not just discussed).
                if (message.status == "COMPLETED") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFF2E7D32)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Action completed", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32))
                    }
                }
            }
        }
    }
}

/** Shown while we wait for the server (and Gemini). */
@Composable
private fun ThinkingBubble() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = PrimaryPurple)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Thinking…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The Confirm / Cancel card for an action the assistant proposed. */
@Composable
private fun PendingActionCard(
    pending: AiPendingActionDto,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val isCancel = pending.type == "CANCEL_EVENT"
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = pending.summary ?: "Confirm this action?",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm, enabled = enabled) {
                    Text(if (isCancel) "Yes, cancel it" else "Confirm")
                }
                OutlinedButton(onClick = onCancel, enabled = enabled) {
                    Text(if (isCancel) "Keep it" else "Cancel")
                }
            }
        }
    }
}

/** Empty-chat screen with a few example requests the user can tap. */
@Composable
private fun WelcomeContent(onExample: (String) -> Unit, modifier: Modifier = Modifier) {
    val examples = listOf(
        "What do I have tomorrow?",
        "Schedule gym tomorrow at 7 AM",
        "Am I free at 4 PM tomorrow?"
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = PrimaryPurple, modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text("How can I help with your schedule?", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Ask me to create, move or cancel events, or ask what's coming up.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        examples.forEach { example ->
            OutlinedButton(
                onClick = { onExample(example) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            ) { Text(example) }
        }
    }
}
