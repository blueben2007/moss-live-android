package cn.moss.live.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 苔绿主色（呼应 MOSS=苔藓），纸感底色，无渐变无发光
private val MossGreen = Color(0xFF3C6E57)
private val MossGreenBright = Color(0xFF9CC7AF) // 暗色模式下的主色
private val PaperLight = Color(0xFFFAF8F4)
private val PaperSurface = Color(0xFFF1EFE7)
private val InkLight = Color(0xFF1D1B17)
private val InkDarkBg = Color(0xFF141210)
private val InkDarkSurface = Color(0xFF1C1916)
private val TextOnDark = Color(0xFFE9E5DC)
private val MutedLight = Color(0xFF8A8578)
private val MutedDark = Color(0xFF98928A)
private val HangupRed = Color(0xFFA6543A) // 挂断钮专用，唯一的暖色

/** 挂断 / 停止按钮的统一暖色。 */
val HangupColor = HangupRed

private val lightScheme = lightColorScheme(
    primary = MossGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFEADF),
    onPrimaryContainer = Color(0xFF1F3629),
    secondary = Color(0xFF54705C),
    onSecondary = Color.White,
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperSurface,
    onSurface = InkLight,
    surfaceVariant = Color(0xFFEAE6DC),
    onSurfaceVariant = MutedLight,
    outline = Color(0xFFC4BEAF),
    error = Color(0xFF9C4A32),
    onError = Color.White,
)

private val darkScheme = darkColorScheme(
    primary = MossGreenBright,
    onPrimary = Color(0xFF0F2318),
    primaryContainer = Color(0xFF24402F),
    onPrimaryContainer = Color(0xFFCDE7D4),
    secondary = Color(0xFF7E9983),
    onSecondary = Color(0xFF0F2318),
    background = InkDarkBg,
    onBackground = TextOnDark,
    surface = InkDarkSurface,
    onSurface = TextOnDark,
    surfaceVariant = Color(0xFF26221D),
    onSurfaceVariant = MutedDark,
    outline = Color(0xFF48443C),
    error = Color(0xFFD98871),
    onError = Color(0xFF3B0F05),
)

/** 标题衬线、正文无衬线：克制、有质感。 */
private val mossTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp, lineHeight = 42.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 38.sp,
    ),
    // 翻译译文专用大字（字幕隐喻）
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
        fontSize = 30.sp, lineHeight = 44.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
        fontSize = 21.sp, lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

@Composable
fun MossLiveTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkScheme else lightScheme,
        typography = mossTypography,
        content = content,
    )
}
