package app.animix.data.importer

import androidx.compose.runtime.Composable

/**
 * Shows a sign-in page and reports the URL it is redirected to once it reaches [redirectPrefix].
 * Android uses an in-app WebView; Windows opens a separate Edge/Chrome window with its own profile.
 */
@Composable
expect fun OAuthBrowser(url: String, redirectPrefix: String, onRedirect: (String) -> Unit, onCancel: () -> Unit)
