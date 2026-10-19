package app.animix.data.profile

import androidx.compose.runtime.Composable
import app.animix.core.Platform
import app.animix.core.nowMillis
import app.animix.data.library.ProfileMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random

enum class ProfileImageKind(val width: Int, val height: Int) { Avatar(640, 640), Banner(1600, 600) }

class ProfileImagePicker(val busy: Boolean, val open: (ProfileImageKind) -> Unit)

@Composable
expect fun rememberProfileImagePicker(onPicked: (ProfileImageKind, ProfileMedia) -> Unit, onError: (String) -> Unit): ProfileImagePicker

internal fun newProfileImageFile(kind: ProfileImageKind): Path {
    val folder = Path(Platform.dataDir, "profile-media")
    SystemFileSystem.createDirectories(folder)
    return Path(folder, "${kind.name.lowercase()}-${nowMillis()}-${Random.nextInt(1_000_000)}.png")
}

/** Removes only files created by this editor session, never shared or remote media. */
suspend fun discardProfileImages(images: List<ProfileMedia>, keep: Set<String>) = withContext(Dispatchers.IO) {
    for (image in images) {
        val name = image.localFileName?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+\\.png")) && it !in keep } ?: continue
        runCatching { SystemFileSystem.delete(Path(Platform.dataDir, "profile-media", name), mustExist = false) }
    }
}
