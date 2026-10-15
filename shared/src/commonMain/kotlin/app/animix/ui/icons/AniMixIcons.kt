package app.animix.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The AniMix icon set: thin rounded outlines on a 24 grid (Lucide-style geometry),
 * so every icon in the app shares one stroke weight.
 */
object AniMixIcons {
    val Home = outline("Home", "M3 10.5 12 3l9 7.5V19a2 2 0 0 1-2 2h-4v-6H9v6H5a2 2 0 0 1-2-2z")
    val Catalog = outline(
        "Catalog",
        "M5 4h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z" +
            "M15 4h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z" +
            "M5 14h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1z" +
            "M15 14h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1z",
    )
    val Heart = outline(
        "Heart",
        "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2" +
            "A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7z",
    )
    val Download = outline("Download", "M12 4v11M7 10l5 5 5-5M5 20h14")
    val User = outline("User", "M12 12a4 4 0 1 0 0-8 4 4 0 1 0 0 8zM4.5 21a7.5 7.5 0 0 1 15 0")
    val Search = outline("Search", "M11 4a7 7 0 1 0 0 14 7 7 0 1 0 0-14zM20 20l-3.8-3.8")
    val Back = outline("Back", "M15 18l-6-6 6-6")
    val ChevronRight = outline("ChevronRight", "M9 18l6-6-6-6")
    val ChevronDown = outline("ChevronDown", "M6 9l6 6 6-6")
    val Close = outline("Close", "M18 6 6 18M6 6l12 12")
    val Refresh = outline("Refresh", "M20 12a8 8 0 1 1-2.34-5.66L20 8.5M20 4v4.5h-4.5")
    val Bookmark = outline("Bookmark", "M18 21l-6-4-6 4V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2z")
    val Bell = outline("Bell", "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.94 1.94 0 0 0 3.4 0")
    val Moon = outline("Moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val Sun = outline(
        "Sun",
        "M12 8a4 4 0 1 0 0 8 4 4 0 1 0 0-8zM12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41" +
            "M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41",
    )
    val Monitor = outline("Monitor", "M4 4h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM8 20h8M12 16v4")
    val Sliders = outline("Sliders", "M4 6h9M17 6h3M15 4v4M4 12h3M11 12h9M9 10v4M4 18h11M19 18h1M17 16v4")
    val Mic = outline(
        "Mic",
        "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM19 11a7 7 0 0 1-14 0M12 18v3",
    )
    val Clock = outline("Clock", "M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18zM12 7v5l3 2")
    val Share = outline("Share", "M12 3v12M8 7l4-4 4 4M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7")
    val Filter = outline("Filter", "M4 6h16M7 12h10M10 18h4")
    val Trophy = outline(
        "Trophy",
        "M8 21h8M12 17v4M7 4h10v5a5 5 0 0 1-10 0zM7 6H4v1a3 3 0 0 0 3 3M17 6h3v1a3 3 0 0 1-3 3",
    )
    val History = outline("History", "M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l3 2")
    val Help = outline(
        "Help",
        "M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18zM9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6v.6M12 17h.01",
    )
    val Play = filled("Play", "M8 5.6v12.8a1.1 1.1 0 0 0 1.66.95l10.3-6.4a1.1 1.1 0 0 0 0-1.9L9.66 4.65A1.1 1.1 0 0 0 8 5.6z")
    val Star = filled(
        "Star",
        "M12 2.8l2.83 5.73 6.32.92-4.57 4.46 1.08 6.3L12 17.24l-5.66 2.97 1.08-6.3-4.57-4.46 6.32-.92z",
    )

    private fun outline(name: String, path: String, stroke: Float = 1.7f) =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(path),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    private fun filled(name: String, path: String) =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
