package it.goletto.iride.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Diaframma a lamelle disegnato a mano.
 * [openness] 0 = quasi chiuso, 1 = tutto aperto. [rotation] in radianti, per animazioni.
 */
@Composable
fun Iris(
    openness: Float,
    modifier: Modifier = Modifier,
    blades: Int = 7,
    rotation: Float = 0f,
    bladeColor: Color,
    edgeColor: Color,
    holeColor: Color = Color.Black,
    rimColor: Color = Color.Transparent,
) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val outer = size.minDimension / 2f
        val rim = outer * 0.06f
        val r0 = outer - rim
        val o = openness.coerceIn(0f, 1f)
        val hole = r0 * (0.1f + 0.84f * o)
        val off = rotation + (1f - o) * 0.9f - (PI / 2).toFloat()

        if (rimColor.alpha > 0f) drawCircle(rimColor, outer, c)
        drawCircle(holeColor, r0, c)

        val v = List(blades) { k ->
            val a = off + 2f * PI.toFloat() * k / blades
            Offset(c.x + hole * cos(a), c.y + hole * sin(a))
        }
        val lens = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r0)) }
        clipPath(lens) {
            val far = r0 * 2.3f
            for (i in 0 until blades) {
                val a = v[i]
                val b = v[(i + 1) % blades]
                var ex = b.x - a.x
                var ey = b.y - a.y
                val len = hypot(ex, ey).coerceAtLeast(0.001f)
                ex /= len; ey /= len
                val mx = (a.x + b.x) / 2f - c.x
                val my = (a.y + b.y) / 2f - c.y
                val ml = hypot(mx, my).coerceAtLeast(0.001f)
                val nx = mx / ml
                val ny = my / ml
                val pB = Offset(a.x + ex * far, a.y + ey * far)
                val pC = Offset(pB.x + nx * far, pB.y + ny * far)
                val pD = Offset(a.x + nx * far, a.y + ny * far)
                val blade = Path().apply {
                    moveTo(a.x, a.y); lineTo(pB.x, pB.y); lineTo(pC.x, pC.y); lineTo(pD.x, pD.y); close()
                }
                val shade = 0.82f + 0.18f * (i.toFloat() / blades)
                val base = lerp(Color.Black, bladeColor, shade)
                drawPath(
                    blade,
                    Brush.linearGradient(
                        listOf(lerp(base, Color.White, 0.06f), base),
                        start = a,
                        end = pD,
                    ),
                )
                // bordo esterno della lamella, dà l'effetto a girandola
                drawLine(lerp(base, Color.Black, 0.55f), a, pD, strokeWidth = outer * 0.012f)
            }
        }
        val holePath = Path().apply {
            moveTo(v[0].x, v[0].y)
            for (i in 1 until blades) lineTo(v[i].x, v[i].y)
            close()
        }
        drawPath(holePath, edgeColor, style = Stroke(width = outer * 0.018f))
    }
}
