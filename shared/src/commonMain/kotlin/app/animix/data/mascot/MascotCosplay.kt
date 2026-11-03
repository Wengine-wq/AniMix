package app.animix.data.mascot

import app.animix.model.Anime
import app.animix.model.AnimeSource

/** Explicit catalog identities: costume matches never depend on translated title substrings. */
enum class MascotCosplay(val character: String, val animeIds: Set<Int>) {
    Nezuko("Незуко", setOf(38000, 40456, 49926, 47778, 51019, 55701, 59192, 62546, 62547)),
    Miku("Мику Накано", setOf(38101, 39783, 48548, 54915, 58755, 64104)),
    Scout("Разведкорпус", setOf(16498, 25777, 35760, 38524, 40028, 48583, 51535, 59571,
        18397, 25781, 36106, 23775, 23777, 36702, 42091)),
    Gon("Гон", setOf(11061, 13271, 19951)),
    /** «Необъятный океан»: Shikimori 37105 — diving gear; seasons 2 and 3 (59986, 62542) — the beach. */
    Diver("Дайвинг-клуб", setOf(37105)),
    Beach("Пляж Идзу", setOf(59986, 62542)),
}

fun mascotCosplayFor(anime: Anime): MascotCosplay? = if (anime.source == AnimeSource.Shikimori)
    MascotCosplay.entries.firstOrNull { anime.id in it.animeIds } else null
