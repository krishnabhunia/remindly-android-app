package com.krishna.remindly

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * v1.67: semantic, theme-aware colors. v1.69 (Q2): every getter now follows the APP theme
 * (Settings > Appearance > Theme) via [LocalAppDark], not the system toggle — the v1.67 tokens
 * silently tracked isSystemInDarkTheme(), so app-forced Dark on a light system leaked light
 * chips. RemindlyTheme provides the resolved flag. LIGHT values stay byte-identical to the
 * literals they replaced. White-on-accent TEXT stays untokenized — correct in both themes.
 */

/** The app-resolved dark flag (Settings theme + system fallback), provided by RemindlyTheme. */
val LocalAppDark = staticCompositionLocalOf { false }

private val dk: Boolean @Composable get() = LocalAppDark.current

val InkPrimary: Color @Composable get() = if (dk) Color(0xFFECECF4) else Color(0xFF1B1B26)
val InkSubtle: Color @Composable get() = if (dk) Color(0xFFA8A8BC) else Color(0xFF6B6B80)
val InkHint: Color @Composable get() = if (dk) Color(0xFF8A8A9E) else Color(0xFF9A9AAC)
val GreyIcon: Color @Composable get() = if (dk) Color(0xFF9898AC) else Color(0xFF8A8A98)
val PillBgIdle: Color @Composable get() = if (dk) Color(0xFF2A2A36) else Color(0xFFE9E9F2)
val PillTextIdle: Color @Composable get() = if (dk) Color(0xFFC8C8D8) else Color(0xFF44445A)
val SurfaceCard: Color @Composable get() = if (dk) Color(0xFF1F1F28) else Color.White
val DangerInk: Color @Composable get() = if (dk) Color(0xFFFF8A80) else Color(0xFFC00000)
val DangerSoft: Color @Composable get() = if (dk) Color(0xFFFFB4AB) else Color(0xFFB3261E)

// ---- v1.69 (Q2) tokens: the hardcoded-hex sweep lands here ----

val SettingsAccent: Color @Composable get() = if (dk) Color(0xFF8FB3FF) else Color(0xFF1A56DB)
val ShopInk: Color @Composable get() = if (dk) Color(0xFF9FE8CF) else Color(0xFF0B6E4F)
val ShopTeal: Color @Composable get() = if (dk) Color(0xFF4DB6AC) else Color(0xFF00695C)
val CallBlue: Color @Composable get() = if (dk) Color(0xFF4DA3F5) else Color(0xFF1E88E5)
val TasksSoft: Color @Composable get() = if (dk) Color(0xFF2A2740) else Color(0xFFEDE7F6)
val BlueSoft: Color @Composable get() = if (dk) Color(0xFF14263B) else Color(0xFFE8F0FE)
val LearnSoft: Color @Composable get() = if (dk) Color(0xFF3A2A18) else Color(0xFFFFF3E0)
val UrgentSoft: Color @Composable get() = if (dk) Color(0xFF421B1B) else Color(0xFFFBE2E2)
val UrgentInk: Color @Composable get() = if (dk) Color(0xFFEF5350) else Color(0xFFC62828)
val AmberInk: Color @Composable get() = if (dk) Color(0xFFFFD54F) else Color(0xFF9A6A00)
val InkStrong: Color @Composable get() = if (dk) Color(0xFFD6D6E2) else Color(0xFF44444F)
val InkFaint: Color @Composable get() = if (dk) Color(0xFF6E6E80) else Color(0xFFB9B9C6)
val SurfaceSubtle: Color @Composable get() = if (dk) Color(0xFF23232E) else Color(0xFFF4F4F9)
val DangerAccent: Color @Composable get() = if (dk) Color(0xFFFF6E6B) else Color(0xFFE53935)
val OverdueRed: Color @Composable get() = if (dk) Color(0xFFFF6B6B) else Color(0xFFD32F2F)

val HeaderBlue: Color @Composable get() = if (dk) Color(0xFF8FB3FF) else Color(0xFF1A3E8C)
val SourceAuto: Color @Composable get() = if (dk) Color(0xFF64B5F6) else Color(0xFF1565C0)
val SourceManual: Color @Composable get() = if (dk) Color(0xFF9C8CFF) else Color(0xFF4A31C7)
val GreenSoft: Color @Composable get() = if (dk) Color(0xFF16352A) else Color(0xFFE7F6EC)
val SuccessGreen: Color @Composable get() = if (dk) Color(0xFF34D399) else Color(0xFF1FA463)
val PurpleInk: Color @Composable get() = if (dk) Color(0xFFC9A8F5) else Color(0xFF5E35B1)

/** Always-dark pill/chip — identical in both themes by design. */
val PillDark: Color @Composable get() = Color(0xFF2D2D3A)
