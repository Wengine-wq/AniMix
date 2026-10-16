package app.animix.data.downloads

import androidx.compose.runtime.Composable

/** Asks, once, for what background downloads need to show their progress (Android notifications). */
@Composable
expect fun rememberDownloadPermissionRequest(): () -> Unit
