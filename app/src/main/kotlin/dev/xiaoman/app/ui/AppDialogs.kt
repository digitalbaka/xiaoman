package dev.xiaoman.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Re-applies the app language inside a dialog. Compose dialogs live in their own window whose
 * context wraps the activity, so `stringResource` there resolves the system locale instead of the
 * one picked in Settings (the page was English while the dialog stayed Chinese).
 */
@Composable
fun ProvideAppLocale(content: @Composable () -> Unit) {
    LocalizedContextProvider(LocalAppLanguage.current, content)
}

/** [androidx.compose.ui.window.Dialog] whose content is composed in the app language. */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(),
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        ProvideAppLocale(content)
    }
}

/** [AlertDialog] with every slot composed in the app language. */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { ProvideAppLocale(confirmButton) },
        modifier = modifier,
        dismissButton = dismissButton?.let { button -> { ProvideAppLocale(button) } },
        icon = icon?.let { slot -> { ProvideAppLocale(slot) } },
        title = title?.let { slot -> { ProvideAppLocale(slot) } },
        text = text?.let { slot -> { ProvideAppLocale(slot) } },
    )
}
