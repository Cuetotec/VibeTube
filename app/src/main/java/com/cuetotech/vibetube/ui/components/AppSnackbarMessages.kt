package com.cuetotech.vibetube.ui.components

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@Composable
fun AppSnackbarMessages(
    message: String?,
    onShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hostState = remember { SnackbarHostState() }
    SnackbarHost(hostState = hostState, modifier = modifier)
    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        val result = hostState.showSnackbar(current)
        if (result == SnackbarResult.Dismissed || result == SnackbarResult.ActionPerformed) {
            onShown()
        }
    }
}
