package app.animix.data

import app.animix.core.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** One group of files AniMix keeps, with what clearing it would mean. */
enum class StorageGroup(val title: String, val description: String, val clearable: Boolean) {
    Downloads("Загрузки", "Скачанные серии и недокачанные части. Удаляются на вкладке «Загрузки».", false),
    Images("Кэш изображений", "Постеры и кадры. После очистки загрузятся заново при просмотре каталога.", true),
    Catalog("Кэш каталога", "Ответы Shikimori и YummyAnime и найденные ссылки на видео. Без сети пропадут старые страницы каталога.", true),
    Browser("Служебные файлы браузера", "Профиль окна входа в Shikimori и временные файлы получения видео. Вход в окне Shikimori потребуется снова.", true),
    Data("Ваши данные", "Списки, история, настройки, достижения, сохранённые пропуски опенингов. Не очищаются.", false),
}

/** Sizes of everything AniMix keeps on this device, in bytes. */
data class StorageUsage(val bytes: Map<StorageGroup, Long>) {
    val total: Long get() = bytes.values.sum()
}

object Storage {
    private fun groupPaths(group: StorageGroup): List<String> = when (group) {
        StorageGroup.Downloads -> listOf("${Platform.dataDir}/downloads")
        StorageGroup.Images -> listOf("${Platform.cacheDir}/images")
        StorageGroup.Catalog -> listOf("${Platform.cacheDir}/api")
        StorageGroup.Browser -> listOf("${Platform.cacheDir}/stream-capture", "${Platform.cacheDir}/shikimori-login")
        StorageGroup.Data -> listOf(Platform.dataDir)
    }

    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        val downloads = size(Path("${Platform.dataDir}/downloads"))
        StorageUsage(StorageGroup.entries.associateWith { group ->
            // «Ваши данные» is the data folder without the downloads inside it.
            if (group == StorageGroup.Data) (size(Path(Platform.dataDir)) - downloads).coerceAtLeast(0)
            else groupPaths(group).sumOf { size(Path(it)) }
        })
    }

    /** Deletes the contents of a clearable group and returns how much was freed. */
    suspend fun clear(group: StorageGroup): Long = withContext(Dispatchers.IO) {
        require(group.clearable)
        var freed = 0L
        for (root in groupPaths(group)) {
            val path = Path(root)
            val before = size(path)
            runCatching { SystemFileSystem.list(path) }.getOrDefault(emptyList()).forEach { delete(it) }
            freed += before - size(path)
        }
        freed
    }

    private fun size(path: Path): Long {
        val meta = SystemFileSystem.metadataOrNull(path) ?: return 0
        if (!meta.isDirectory) return meta.size.coerceAtLeast(0)
        return runCatching { SystemFileSystem.list(path) }.getOrDefault(emptyList()).sumOf { size(it) }
    }

    private fun delete(path: Path) {
        val meta = SystemFileSystem.metadataOrNull(path) ?: return
        if (meta.isDirectory) runCatching { SystemFileSystem.list(path) }.getOrDefault(emptyList()).forEach { delete(it) }
        // Files in use (an open browser profile) stay; the rest goes.
        runCatching { SystemFileSystem.delete(path, mustExist = false) }
    }
}

/** «1,4 ГБ», «230 МБ», «12 КБ». */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> (bytes * 10 / (1L shl 30)).let { "${it / 10},${it % 10} ГБ" }
    bytes >= 1L shl 20 -> "${bytes / (1L shl 20)} МБ"
    bytes >= 1024 -> "${bytes / 1024} КБ"
    else -> "$bytes Б"
}
