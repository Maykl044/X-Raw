package ai.xrav.xravscan.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Apple-HIG-inspired typography on top of the default Compose typography baseline.
private val ApiFamily = FontFamily.Default

val AppTypography: Typography = Typography(
    displayLarge = TextStyle(fontFamily = ApiFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp),
    headlineSmall = TextStyle(fontFamily = ApiFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = ApiFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = ApiFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = ApiFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = ApiFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    labelLarge = TextStyle(fontFamily = ApiFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelSmall = TextStyle(fontFamily = ApiFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)
