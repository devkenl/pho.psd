package app.phopsd.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phopsd.ui.theme.PhoColors
import app.phopsd.ui.theme.PhoType

/**
 * Original pho.psd mark: two offset rounded frames (stacked layers) with a solid dot —
 * the period in "pho.psd". Drawn in code so it scales crisply and has no external asset.
 */
@Composable
fun PhoMark(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = s * 0.085f
        val r = CornerRadius(s * 0.16f)
        drawRoundRect(
            color = PhoColors.Accent.copy(alpha = 0.45f),
            topLeft = Offset(s * 0.28f, s * 0.08f),
            size = Size(s * 0.62f, s * 0.62f),
            cornerRadius = r,
            style = Stroke(width = stroke),
        )
        drawRoundRect(
            color = PhoColors.Accent,
            topLeft = Offset(s * 0.08f, s * 0.28f),
            size = Size(s * 0.62f, s * 0.62f),
            cornerRadius = r,
            style = Stroke(width = stroke),
        )
        drawCircle(color = Color.White, radius = s * 0.085f, center = Offset(s * 0.39f, s * 0.59f))
    }
}

/** Logo lock-up. The product name is always written exactly `pho.psd`. */
@Composable
fun PhoWordmark(modifier: Modifier = Modifier, markSize: Dp = 26.dp, large: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PhoMark(markSize)
        Spacer(Modifier.width(if (large) 12.dp else 8.dp))
        Text(
            text = "pho.psd",
            style = PhoType.Title.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = if (large) 30.sp else 15.sp,
                letterSpacing = if (large) (-0.5).sp else 0.sp,
            ),
        )
    }
}
