package com.posepocket.ui.theme

import androidx.compose.ui.graphics.Color

/** Design tokens from docs/UX_AUDIT_AND_DESIGN.md §2.2. Contrast of each text/background pair must stay >= 4.5:1. */
internal object Palette {
    val PrimaryLight = Color(0xFF1468EB)
    val OnPrimaryLight = Color(0xFFFFFFFF)
    val SurfaceLight = Color(0xFFFAFAFC)
    val OnSurfaceLight = Color(0xFF14191F)
    val ErrorLight = Color(0xFFB3261E)
    val WarningLight = Color(0xFF8A5A00)
    val SyntheticLight = Color(0xFF5B4FC4)

    val PrimaryDark = Color(0xFF8DE8C2)
    val OnPrimaryDark = Color(0xFF00382A)
    val SurfaceDark = Color(0xFF1B2227)
    val OnSurfaceDark = Color(0xFFE2E8EA)
    val ErrorDark = Color(0xFFF2B8B5)
    val WarningDark = Color(0xFFFFD27A)
    val SyntheticDark = Color(0xFFC9BEFF)

    /** The 3D stage is dark in both themes so the skeleton keeps the same contrast. */
    val Stage = Color(0xFFE5E8ED)
    val StageFloor = Color(0x507B8794)
    val StageBone = Color(0xFF1468EB)
}

/** Colors that Material 3 has no slot for. */
data class PocketExtras(
    val warning: Color,
    val synthetic: Color,
    val stage: Color = Palette.Stage,
    val stageFloor: Color = Palette.StageFloor,
    val stageBone: Color = Palette.StageBone,
)
