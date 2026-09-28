package com.krishna.remindly

import android.app.Activity
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * v2.01 (N32) — the ONE bottom-sheet standard for every popup in the app.
 *
 * Why this exists: the v1.88/N27 clearance was `Spacer(Modifier.navigationBarsPadding())` read
 * INSIDE the sheet. On material3 1.2.1 the ModalBottomSheet lives in its own popup window and, on
 * Krishna's phone (app not edge-to-edge), the nav-bar inset that reaches the sheet content is 0 —
 * so the "ample" spacer measured 0 + 16 dp while the sheet still drew under Back/Home/Recents.
 * Now the clearance is never derived from the popup: SystemBars measures the real bar once on the
 * ACTIVITY window, and SheetBottomSpace() = max(25% of the window height, bar + 24 dp) — Option D.
 */

/** Real system-bar geometry, measured on the activity window (never inside a popup). */
object SystemBars {
    /** Navigation-bar bottom inset in dp. Safe default 48 dp (3-button bar) until measured. */
    val navBottomDp = MutableStateFlow(48f)
    /** True while the soft keyboard is showing (it covers the nav bar, so sheets need less gap). */
    val imeVisible = MutableStateFlow(false)

    /**
     * Installs a layout listener on the decor view and reads the ROOT window insets on every
     * layout pass. Deliberately NOT setOnApplyWindowInsetsListener on the decor view — that would
     * replace DecorView.onApplyWindowInsets and break the platform's own inset fitting.
     */
    fun install(activity: Activity) {
        runCatching {
            val decor = activity.window.decorView
            val density = activity.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                runCatching {
                    val insets = ViewCompat.getRootWindowInsets(decor) ?: return@runCatching
                    val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom / density
                    val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                    val imeUp = insets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > 0
                    if (nav.isFinite() && nav >= 0f) navBottomDp.value = nav
                    imeVisible.value = imeUp
                }.onFailure { Logger.e(activity, "INSETS", it, "system-bar read failed; keeping last value") }
            }
            decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
            ViewCompat.requestApplyInsets(decor)
        }.onFailure { Logger.e(activity, "INSETS", it, "system-bar listener install failed; using 48 dp default") }
    }
}

/** LAST child of every sheet, on EVERY exit path. Option D clearance; IME-aware (L-h). */
@Composable
fun SheetBottomSpace() {
    val nav by SystemBars.navBottomDp.collectAsState()
    val ime by SystemBars.imeVisible.collectAsState()
    val windowH = LocalConfiguration.current.screenHeightDp.toFloat()
    Spacer(Modifier.height(sheetClearanceDp(windowH, nav, ime).dp))
}

/** Sheets keep the status-bar (top) inset only; the bottom is ours (one source of clearance). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun sheetWindowInsets(): WindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top)

/**
 * The action row every editor uses: [Delete] · Cancel (outlined) · Save (filled accent) —
 * equal width, 50 dp tall, one row. Delete appears only when [onDelete] is given (editing).
 */
@Composable
fun EditorActionRow(
    accent: Color,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean = true,
    saveLabel: String = "Save",
    cancelLabel: String = "Cancel",
    onDelete: (() -> Unit)? = null
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (onDelete != null) OutlinedButton(
            onClick = onDelete,
            contentPadding = PaddingValues(horizontal = 6.dp),
            modifier = Modifier.weight(1f).height(50.dp)
        ) { Text("Delete", fontWeight = FontWeight.Bold, color = OverdueRed, maxLines = 1) }
        OutlinedButton(
            onClick = onCancel,
            contentPadding = PaddingValues(horizontal = 6.dp),
            modifier = Modifier.weight(1f).height(50.dp)
        ) { Text(cancelLabel, fontWeight = FontWeight.Bold, color = InkSubtle, maxLines = 1) }
        Button(
            onClick = onSave,
            enabled = saveEnabled,
            colors = ButtonDefaults.buttonColors(containerColor = accent),
            modifier = Modifier.weight(1f).height(50.dp)
        ) { Text(saveLabel, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) }
    }
}

/**
 * The editor sheet chrome: fully-expanded ModalBottomSheet (never half-open, L-i), 18 dp side
 * padding, accent title, [content], then [actions] and the Option-D SheetBottomSpace() — the
 * spacer is unconditional and last, so every exit path clears the system buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSheet(
    title: String,
    accent: Color,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceCard,
        windowInsets = sheetWindowInsets()
    ) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.height(8.dp))
            content()
            Spacer(Modifier.height(10.dp))
            actions()
            SheetBottomSpace()
        }
    }
}
