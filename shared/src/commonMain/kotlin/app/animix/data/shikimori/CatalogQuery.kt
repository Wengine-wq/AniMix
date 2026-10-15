package app.animix.data.shikimori

import kotlinx.serialization.Serializable

@Serializable
enum class CatalogOrder(val apiValue: String, val label: String) {
    Popularity("popularity", "Популярные"),
    Ranked("ranked", "По рейтингу"),
    AiredOn("aired_on", "Новинки"),
    Name("name", "По названию"),
}

@Serializable
data class CatalogQuery(
    val page: Int = 1,
    val limit: Int = 30,
    val order: CatalogOrder = CatalogOrder.Popularity,
    /** Shikimori kind values: tv, movie, ova, ona, special. */
    val kinds: List<String> = emptyList(),
    /** anons, ongoing, released. */
    val status: String? = null,
    /** e.g. "2026", "fall_2026". */
    val season: String? = null,
    val genreIds: List<String> = emptyList(),
    val search: String? = null,
) {
    val cacheKey: String
        get() = "animes:$page:$limit:${order.apiValue}:${kinds.joinToString(",")}:$status:$season:" +
            "${genreIds.joinToString(",")}:${search?.trim()?.lowercase()}"
}
