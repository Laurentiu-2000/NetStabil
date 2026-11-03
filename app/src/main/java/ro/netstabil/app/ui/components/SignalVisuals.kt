package ro.netstabil.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ro.netstabil.app.ui.theme.AmberWarn
import ro.netstabil.app.ui.theme.CrimsonAlert
import ro.netstabil.app.ui.theme.EmeraldPrimary

fun scoreColor(score: Int): Color = when {
    score >= 70 -> EmeraldPrimary
    score >= 40 -> AmberWarn
    else -> CrimsonAlert
}

fun dbmQualityColor(dbm: Int?, isWifi: Boolean): Color {
    if (dbm == null) return Color(0xFF94A3B8)
    return if (isWifi) {
        when {
            dbm >= -67 -> EmeraldPrimary
            dbm >= -78 -> AmberWarn
            else -> CrimsonAlert
        }
    } else {
        when {
            dbm >= -95 -> EmeraldPrimary
            dbm >= -108 -> AmberWarn
            else -> CrimsonAlert
        }
    }
}

@Composable
fun MinimalHealthGauge(
    score: Int,
    isStabilizerActive: Boolean,
    modifier: Modifier = Modifier
) {
    val isRo = ro.netstabil.app.i18n.LocalIsRo.current
    val clamped = score.coerceIn(0, 100)
    val animatedProgress by animateFloatAsState(
        targetValue = clamped / 100f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "gaugeProgress"
    )
    val activeColor = scoreColor(clamped)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier.size(196.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(196.dp)) {
            val strokeWidth = 13.dp.toPx()
            val inset = strokeWidth / 2f + 6.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = trackColor,
                startAngle = 140f,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            drawArc(
                color = activeColor,
                startAngle = 140f,
                sweepAngle = 260f * animatedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$clamped",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                letterSpacing = (-1.5).sp
            )
            Text(
                text = if (isStabilizerActive) {
                    ro.netstabil.app.i18n.tr(isRo, "STABILIZARE ACTIVĂ", "STABILIZER ACTIVE")
                } else {
                    ro.netstabil.app.i18n.tr(isRo, "MONITORIZARE", "MONITORING")
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (isStabilizerActive) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SignalSparkline(
    samplesDbm: List<Int>,
    modifier: Modifier = Modifier
) {
    val lineColor = EmeraldPrimary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(92.dp)
    ) {
        val w = size.width
        val h = size.height

        // 3 linii discrete de referinta
        for (i in 1..3) {
            val y = h * (i / 4f)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        if (samplesDbm.size < 2) return@Canvas

        val minDbm = (samplesDbm.minOrNull() ?: -115).coerceAtMost(-95) - 4
        val maxDbm = (samplesDbm.maxOrNull() ?: -65).coerceAtLeast(-70) + 4
        val span = (maxDbm - minDbm).coerceAtLeast(10).toFloat()

        val path = Path()
        samplesDbm.forEachIndexed { index, dbm ->
            val x = (index.toFloat() / (samplesDbm.size - 1).coerceAtLeast(1)) * w
            val norm = ((dbm - minDbm) / span).coerceIn(0f, 1f)
            val y = h - (norm * (h - 12.dp.toPx())) - 6.dp.toPx()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(
                width = 2.6.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}
