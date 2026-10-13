package it.goletto.iride.ui

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

/** Icone dell'app (24×24). Quelle a tratto vengono comunque tinte da Icon(). */
object IrideIcons {
    private fun filled(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) b.addPath(addPathNodes(p), fill = SolidColor(Color.White))
        return b.build()
    }

    private fun stroked(name: String, width: Float, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(
                addPathNodes(p),
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Camera = filled(
        "camera",
        "M12,12m-3.2,0a3.2,3.2 0,1 1,6.4 0a3.2,3.2 0,1 1,-6.4 0",
        "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z",
    )

    val Gallery = filled(
        "gallery",
        "M22,16V4c0,-1.1 -0.9,-2 -2,-2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2zM11,12l2.03,2.71L16,11l4,5H8l3,-4zM2,6v14c0,1.1 0.9,2 2,2h14v-2H4V6H2z",
    )

    val Log = stroked("log", 1.8f, "M4,6h16", "M4,12h10", "M4,18h13")

    val Wifi = filled(
        "wifi",
        "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z",
    )

    val Hotspot = filled(
        "hotspot",
        "M12,11c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM18,13c0,-3.31 -2.69,-6 -6,-6s-6,2.69 -6,6c0,2.22 1.21,4.15 3,5.19l1,-1.74c-1.19,-0.7 -2,-1.97 -2,-3.45 0,-2.21 1.79,-4 4,-4s4,1.79 4,4c0,1.48 -0.81,2.75 -2,3.45l1,1.74c1.79,-1.04 3,-2.97 3,-5.19zM12,3C6.48,3 2,7.48 2,13c0,3.7 2.01,6.92 4.99,8.65l1,-1.73C5.61,18.53 4,15.96 4,13c0,-4.42 3.58,-8 8,-8s8,3.58 8,8c0,2.96 -1.61,5.53 -4,6.92l1,1.73c2.99,-1.73 5,-4.95 5,-8.65 0,-5.52 -4.48,-10 -10,-10z",
    )

    val Moon = filled("moon", "M12.4,2.5a9.5,9.5 0,1 0,9.1 12.3a7.6,7.6 0,0 1,-9.1 -12.3z")

    val Sun = stroked(
        "sun", 1.8f,
        "M12,12m-4,0a4,4 0,1 1,8 0a4,4 0,1 1,-8 0",
        "M12,2v2", "M12,20v2", "M2,12h2", "M20,12h2",
        "M4.9,4.9l1.4,1.4", "M17.7,17.7l1.4,1.4", "M4.9,19.1l1.4,-1.4", "M17.7,6.3l1.4,-1.4",
    )

    val Power = filled(
        "power",
        "M13,3h-2v10h2L13,3zM17.83,5.17l-1.42,1.42C17.99,7.86 19,9.81 19,12c0,3.87 -3.13,7 -7,7s-7,-3.13 -7,-7c0,-2.19 1.01,-4.14 2.58,-5.42L6.17,5.17C4.23,6.82 3,9.26 3,12c0,4.97 4.03,9 9,9s9,-4.03 9,-9c0,-2.74 -1.23,-5.18 -3.17,-6.83z",
    )

    val Download = filled("download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")

    val Grid = stroked("grid", 1.6f, "M4,4h16v16H4z", "M9.33,4v16", "M14.67,4v16", "M4,9.33h16", "M4,14.67h16")

    val Focus = stroked(
        "focus", 1.8f,
        "M4,8V4h4", "M16,4h4v4", "M20,16v4h-4", "M8,20H4v-4",
        "M12,12m-2,0a2,2 0,1 1,4 0a2,2 0,1 1,-4 0",
    )

    val LiveView = stroked(
        "liveview", 1.8f,
        "M2,12c2.5,-4.5 6,-7 10,-7s7.5,2.5 10,7c-2.5,4.5 -6,7 -10,7s-7.5,-2.5 -10,-7z",
        "M12,12m-3,0a3,3 0,1 1,6 0a3,3 0,1 1,-6 0",
    )

    val Timer = stroked(
        "timer", 1.8f,
        "M12,13m-8,0a8,8 0,1 1,16 0a8,8 0,1 1,-16 0",
        "M12,13V9", "M10,2h4", "M19,6l1.5,-1.5",
    )

    val Refresh = stroked("refresh", 1.8f, "M20,11a8,8 0,1 0,-2.3 5.7", "M20,4v7h-7")

    val Check = stroked("check", 2.4f, "M5,12.5l4.5,4.5L19,7.5")

    val Close = stroked("close", 2f, "M6,6l12,12", "M18,6L6,18")

    val Copy = stroked("copy", 1.8f, "M9,9h11v11H9z", "M5,15H4V4h11v1")

    val Chevron = stroked("chevron", 2f, "M9,6l6,6 -6,6")

    val Minus = stroked("minus", 2.2f, "M6,12h12")

    val Plus = stroked("plus", 2.2f, "M6,12h12", "M12,6v12")
}
