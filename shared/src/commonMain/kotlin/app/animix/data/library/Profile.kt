package app.animix.data.library

import app.animix.core.Platform
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable

/** Local media and server assets are different references; a device path is never an upload ID. */
@Serializable
data class ProfileMedia(
    val localFileName: String? = null,
    val assetId: String? = null,
    val url: String? = null,
) {
    fun imageModel(): String? {
        val file = localFileName?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+\\.png")) }
            ?.let { Path(Platform.dataDir, "profile-media", it) }
        return if (file != null && SystemFileSystem.exists(file)) file.toString()
            else url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }
}

/** Backwards-compatible local cache of a profile. Server identity is never editable in the UI. */
@Serializable
data class LocalProfile(
    val name: String = "Гость",
    val createdAt: Long = 0,
    val accountId: String? = null,
    val handle: String? = null,
    val about: String = "",
    val avatar: ProfileMedia? = null,
    val banner: ProfileMedia? = null,
    val badgeAchievementId: String? = null,
    val updatedAt: Long = 0,
) {
    fun draft() = ProfileDraft(name, about, avatar, banner, badgeAchievementId)

    fun edited(draft: ProfileDraft, unlockedBadges: Set<String>, now: Long): LocalProfile = copy(
        name = draft.name.trim().replace(Regex("\\s+"), " ").take(ProfileNameLimit).ifBlank { name.take(ProfileNameLimit) },
        about = draft.about.trim().take(160),
        avatar = draft.avatar,
        banner = draft.banner,
        badgeAchievementId = draft.badgeAchievementId?.takeIf { it in unlockedBadges },
        updatedAt = now,
    )
}

/** Only user-editable fields. A future sync adapter uploads local media before sending asset IDs. */
@Serializable
data class ProfileDraft(
    val name: String,
    val about: String = "",
    val avatar: ProfileMedia? = null,
    val banner: ProfileMedia? = null,
    val badgeAchievementId: String? = null,
)

const val ProfileNameLimit = 35
