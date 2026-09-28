package com.krishna.remindly

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

// ---------------------------------------------------------------- palettes

data class TabPalette(
    val g1: Color,          // gradient start
    val g2: Color,          // gradient end
    val accent: Color,      // buttons / FAB
    val chipBg: Color,      // soft chip / group header background
    val onChip: Color       // text on chipBg
)

val TasksPal = TabPalette(
    g1 = Color(0xFF4C3FD6), g2 = Color(0xFF8E5BF0),
    accent = Color(0xFF5B4FE8), chipBg = Color(0xFFEDEAFD), onChip = Color(0xFF3A2FA8)
)

val ShopPal = TabPalette(
    g1 = Color(0xFF0E9F6E), g2 = Color(0xFF00B8A9),
    accent = Color(0xFF0E9F6E), chipBg = Color(0xFFE0F5EE), onChip = Color(0xFF0B6E4F)
)

val LearnPal = TabPalette(
    g1 = Color(0xFFF2711C), g2 = Color(0xFFE8467C),
    accent = Color(0xFFEE5A24), chipBg = Color(0xFFFDEAE0), onChip = Color(0xFFB1400E)
)

/** Per-role font multiplier (v1.4). */
fun TextStyle.fs(f: Float): TextStyle =
    if (f == 1f) this else copy(
        fontSize = if (fontSize.isSpecified) fontSize * f else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * f else lineHeight
    )

/** Card density paddings (v1.5: four levels; the two tightest shrink the checkbox). */
data class Dens(val outerV: Dp, val innerV: Dp, val gap: Dp, val shrinkCheck: Boolean = false)

fun densOf(key: String): Dens = densOfPct(when (key) {
    "XTIGHT" -> 0.10f; "TIGHT" -> 0.30f; "COMPACT" -> 0.55f; else -> 0.80f
})

/** v1.7: one continuous density number. 0.80 == the old Comfortable (4/8/4 dp). */
fun densOfPct(p: Float): Dens {
    val pc = p.coerceIn(0f, 1f)
    return Dens((5f * pc).dp, (10f * pc).dp, (5f * pc).dp, shrinkCheck = pc < 0.35f)
}

val CallPal = TabPalette(
    g1 = Color(0xFF1565C0), g2 = Color(0xFF26C6DA),
    accent = Color(0xFF1E88E5), chipBg = Color(0xFFE1F0FE), onChip = Color(0xFF0D47A1)
)

val SettingsPal = TabPalette(
    g1 = Color(0xFF37474F), g2 = Color(0xFF607D8B),
    accent = Color(0xFF455A64), chipBg = Color(0xFFECEFF1), onChip = Color(0xFF37474F)
)

fun paletteFor(tab: Tab): TabPalette = when (tab) {
    Tab.TASKS -> TasksPal
    Tab.SHOP -> ShopPal
    Tab.LEARN -> LearnPal
}

// ---- v1.69 (Q2): dark twins — g1/g2 gradients stay (already deep hues); accents lift one
// tone for dark-surface contrast; chips flip to deep tints with light ink.
val TasksPalD = TabPalette(
    g1 = Color(0xFF4C3FD6), g2 = Color(0xFF8E5BF0),
    accent = Color(0xFF8A7DF5), chipBg = Color(0xFF2A2740), onChip = Color(0xFFC9C2F5)
)
val ShopPalD = TabPalette(
    g1 = Color(0xFF0E9F6E), g2 = Color(0xFF00B8A9),
    accent = Color(0xFF2ED3A0), chipBg = Color(0xFF12312A), onChip = Color(0xFF9FE8CF)
)
val LearnPalD = TabPalette(
    g1 = Color(0xFFF2711C), g2 = Color(0xFFE8467C),
    accent = Color(0xFFFF8A50), chipBg = Color(0xFF3A2418), onChip = Color(0xFFFFC9A8)
)
val CallPalD = TabPalette(
    g1 = Color(0xFF1565C0), g2 = Color(0xFF26C6DA),
    accent = Color(0xFF4DA3F5), chipBg = Color(0xFF142A3E), onChip = Color(0xFFA8D4F5)
)
val SettingsPalD = TabPalette(
    g1 = Color(0xFF37474F), g2 = Color(0xFF607D8B),
    accent = Color(0xFF90A4AE), chipBg = Color(0xFF263238), onChip = Color(0xFFB0BEC5)
)

/** v1.69 (Q2): the theme-aware palette — composable sites use THIS, not the static vals. */
@Composable
fun palFor(tab: Tab?): TabPalette {
    val dark = LocalAppDark.current
    return when (tab) {
        Tab.SHOP -> if (dark) ShopPalD else ShopPal
        Tab.LEARN -> if (dark) LearnPalD else LearnPal
        else -> if (dark) TasksPalD else TasksPal
    }
}

@Composable fun callPalC(): TabPalette = if (LocalAppDark.current) CallPalD else CallPal
@Composable fun settingsPalC(): TabPalette = if (LocalAppDark.current) SettingsPalD else SettingsPal

val priorityColor: (Priority) -> Color @Composable get() {
    val dark = LocalAppDark.current
    return { p -> when (p) {
        Priority.LOW -> if (dark) Color(0xFF66BB6A) else Color(0xFF2E7D32)
        Priority.MEDIUM -> if (dark) Color(0xFFFDD835) else Color(0xFFF9A825)
        Priority.HIGH -> if (dark) Color(0xFFFFB74D) else Color(0xFFEF6C00)
        Priority.URGENT -> if (dark) Color(0xFFEF5350) else Color(0xFFC62828)
    } }
}

val priorityContainer: (Priority) -> Color @Composable get() {
    val dark = LocalAppDark.current
    return { p -> when (p) {
        Priority.LOW -> if (dark) Color(0xFF1B3620) else Color(0xFFE3F3E4)
        Priority.MEDIUM -> if (dark) Color(0xFF3B3313) else Color(0xFFFDF3D3)
        Priority.HIGH -> if (dark) Color(0xFF402A14) else Color(0xFFFDE9D7)
        Priority.URGENT -> if (dark) Color(0xFF421B1B) else Color(0xFFFBE2E2)
    } }
}

val PageBg = Color(0xFFF6F6FB)

// ---------------------------------------------------------------- typography

fun scaledTypography(scale: Float): Typography {
    val base = Typography()
    return Typography(
        displaySmall = base.displaySmall.copy(fontSize = base.displaySmall.fontSize * scale),
        headlineMedium = base.headlineMedium.copy(fontSize = base.headlineMedium.fontSize * scale),
        headlineSmall = base.headlineSmall.copy(fontSize = base.headlineSmall.fontSize * scale),
        titleLarge = base.titleLarge.copy(fontSize = base.titleLarge.fontSize * scale),
        titleMedium = base.titleMedium.copy(fontSize = base.titleMedium.fontSize * scale),
        titleSmall = base.titleSmall.copy(fontSize = base.titleSmall.fontSize * scale),
        bodyLarge = base.bodyLarge.copy(fontSize = base.bodyLarge.fontSize * scale),
        bodyMedium = base.bodyMedium.copy(fontSize = base.bodyMedium.fontSize * scale),
        bodySmall = base.bodySmall.copy(fontSize = base.bodySmall.fontSize * scale),
        labelLarge = base.labelLarge.copy(fontSize = base.labelLarge.fontSize * scale),
        labelMedium = base.labelMedium.copy(fontSize = base.labelMedium.fontSize * scale),
        labelSmall = base.labelSmall.copy(fontSize = base.labelSmall.fontSize * scale)
    )
}

@Composable
fun RemindlyTheme(content: @Composable () -> Unit) {
    val settings by SettingsStore.s.collectAsState()
    val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = settings.theme == "DARK" || (settings.theme == "SYSTEM" && sysDark)
    androidx.compose.runtime.CompositionLocalProvider(LocalAppDark provides dark) {
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(
            primary = TasksPal.accent,
            secondary = ShopPal.accent,
            tertiary = LearnPal.accent,
            background = Color(0xFF14141B),
            surface = Color(0xFF1D1D26)
        ) else lightColorScheme(
            primary = TasksPal.accent,
            secondary = ShopPal.accent,
            tertiary = LearnPal.accent,
            background = PageBg,
            surface = Color.White
        ),
        typography = scaledTypography(settings.fontScale),
        content = content
    )
}
}

/** v1.69 (Q2): settings section tints, dark-aware (moved from SettingsScreen for the hex gate). */
@Composable
fun sectionTint(key: String): Color {
    val dark = LocalAppDark.current
    return when (key) {
        "groups" -> if (dark) Color(0xFFCE93D8) else Color(0xFF7B1FA2)
        "adding" -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        "housekeeping" -> if (dark) Color(0xFFFFB74D) else Color(0xFFEF6C00)
        "alerts" -> if (dark) Color(0xFFEF9A9A) else Color(0xFFC62828)
        "gestures" -> if (dark) Color(0xFFCE93D8) else Color(0xFF6A1B9A)
        "appearance" -> if (dark) Color(0xFFF48FB1) else Color(0xFFD81B60)
        "backup" -> if (dark) Color(0xFF4DB6AC) else Color(0xFF00897B)
        "errlog" -> if (dark) Color(0xFFEF9A9A) else Color(0xFFB71C1C)
        "health" -> if (dark) Color(0xFFDCE775) else Color(0xFF9E9D24)
        "bin" -> if (dark) Color(0xFFFF8A80) else Color(0xFF8E0000)
        "google" -> if (dark) Color(0xFF64B5F6) else Color(0xFF1565C0)
        "details" -> if (dark) Color(0xFF9FA8DA) else Color(0xFF283593)
        "about" -> if (dark) Color(0xFFBCAAA4) else Color(0xFF5D4037)
        "reset" -> if (dark) Color(0xFFEF9A9A) else Color(0xFFC62828)
        "tests" -> if (dark) Color(0xFF4DD0E1) else Color(0xFF00838F)
        "location" -> if (dark) Color(0xFF4DB6AC) else Color(0xFF00695C)
        else -> if (dark) Color(0xFF90A4AE) else Color(0xFF546E7A)
    }
}

/**
 * v1.80 (Q16): the alert card's background says WHICH TYPE fired, mapped to urgency so colour
 * reads before the text does. Global by design — a per-tab override would destroy the very
 * signal this carries, which is why it is exempt from the global+per-tab standing rule.
 */
fun alertCardGradient(letter: String): List<Color> = when (letter) {
    "A" -> listOf(Color(0xFFB3261E), Color(0xFFD9433A))   // deep red — loudest
    "R" -> listOf(Color(0xFFB26A00), Color(0xFFE08A1E))   // amber — middle
    else -> listOf(Color(0xFF3730A3), Color(0xFF5B54D6))  // indigo — quietest
}

/** v1.80 (Q16): button colour says WHICH ACTION, constant across all three cards. */
fun alertActionColor(action: String): Color = when (action) {
    "DONE" -> Color(0xFF1B5E20)
    "DISMISS" -> Color(0xFF5F6368)
    "SNOOZE" -> Color(0xFF8A5A00)
    else -> Color(0xFF1A56DB)
}
