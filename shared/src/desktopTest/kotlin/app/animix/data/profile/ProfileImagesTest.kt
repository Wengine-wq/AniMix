package app.animix.data.profile

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class ProfileImagesTest {
    @Test fun cameraOrientationIsAppliedBeforeCroppingTheBanner() {
        val directory = Files.createTempDirectory("animix-profile-orientation").toFile()
        try {
            val source = File(directory, "phone.jpg")
            val image = BufferedImage(1200, 600, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = Color.RED; graphics.fillRect(0, 0, 600, 600)
            graphics.color = Color.BLUE; graphics.fillRect(600, 0, 600, 600)
            graphics.dispose()
            ImageIO.write(image, "jpg", source)
            val original = source.readBytes()
            val exif = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            exif.put(byteArrayOf(69, 120, 105, 102, 0, 0, 73, 73)).putShort(42).putInt(8)
            exif.putShort(1).putShort(0x112).putShort(3).putInt(1).putShort(6).putShort(0).putInt(0)
            val insertion = 4 + ((original[4].toInt() and 0xff) * 256 + (original[5].toInt() and 0xff))
            source.writeBytes(original.take(insertion).toByteArray() + byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 34) + exif.array() + original.drop(insertion).toByteArray())
            val target = File(directory, "banner.png")
            prepareProfileImage(source, ProfileImageKind.Banner, target)
            val result = ImageIO.read(target)
            assertTrue(Color(result.getRGB(800, 50)).red > 180)
            assertTrue(Color(result.getRGB(800, 550)).blue > 180)
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun importedImagesAreCroppedToTheirPreviewRatioAndStoredIndependentlyOfTheSource() {
        val directory = Files.createTempDirectory("animix-profile-images").toFile()
        try {
            val source = File(directory, "source.png")
            val image = BufferedImage(1200, 600, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            graphics.color = Color.RED; graphics.fillRect(0, 0, 1200, 600)
            graphics.color = Color.BLUE; graphics.fillRect(300, 0, 600, 600)
            graphics.dispose()
            ImageIO.write(image, "png", source)
            val avatar = File(directory, "avatar.png")
            val banner = File(directory, "banner.png")
            prepareProfileImage(source, ProfileImageKind.Avatar, avatar)
            prepareProfileImage(source, ProfileImageKind.Banner, banner)
            assertTrue(source.delete())
            val savedAvatar = ImageIO.read(avatar)
            val savedBanner = ImageIO.read(banner)
            assertEquals(640, savedAvatar.width)
            assertEquals(640, savedAvatar.height)
            assertEquals(Color.BLUE.rgb, savedAvatar.getRGB(320, 320))
            assertEquals(1600, savedBanner.width)
            assertEquals(600, savedBanner.height)
            assertEquals(Color.RED.rgb, savedBanner.getRGB(1, 300))
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
