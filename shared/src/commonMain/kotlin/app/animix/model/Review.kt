package app.animix.model

import app.animix.core.RichText
import kotlinx.serialization.Serializable

@Serializable
enum class ReviewSource(val label: String) { Yummy("YummyAnime"), Shikimori("Shikimori") }

/** A review (YummyAnime) or a discussion comment (Shikimori), as plain text. */
@Serializable
data class Review(
    val id: String,
    val author: String,
    val avatarUrl: String?,
    val body: RichText,
    /** Reviewer's score out of 10, when the source has one. */
    val score: Double?,
    /** "dd.MM.yyyy". */
    val date: String,
    val likes: Int?,
    val source: ReviewSource,
)
