package app.animix.data.notify

import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

actual object SystemNotifications {
    /** One tray icon for the whole run, created with the first notice; Windows shows its messages as toasts. */
    private val tray: TrayIcon? by lazy {
        if (!SystemTray.isSupported()) return@lazy null
        runCatching {
            val image = javaClass.getResourceAsStream("/animix-tray.png")?.use { javax.imageio.ImageIO.read(it) }
                ?: BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            TrayIcon(image, "AniMix").apply { isImageAutoSize = true; SystemTray.getSystemTray().add(this) }
        }.getOrNull()
    }

    actual fun show(notice: EpisodeNotice) {
        val (title, text) = notice.text()
        runCatching { tray?.displayMessage(title, text, TrayIcon.MessageType.NONE) }
    }
}
