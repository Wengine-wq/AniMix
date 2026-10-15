package app.animix.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/** Colors the native Windows title bar to match the app theme (Windows 10 20H1+ / 11). */
object WindowsTitleBar {
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    @Suppress("FunctionName")
    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwm? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) null
        else runCatching { Native.load("dwmapi", Dwm::class.java) }
            .onFailure { System.err.println("AniMix: dwmapi unavailable: $it") }
            .getOrNull()
    }

    fun apply(window: Window, dark: Boolean, captionArgb: Int, textArgb: Int) {
        val api = dwm ?: return
        runCatching<Unit> {
            val hwnd = WinDef.HWND(Native.getComponentPointer(window))
            val result = api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, IntByReference(if (dark) 1 else 0), 4)
            if (result != 0) System.err.println("AniMix: DwmSetWindowAttribute failed: 0x${result.toUInt().toString(16)}")
            // Caption colors exist on Windows 11 only; older versions ignore them.
            api.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, IntByReference(captionArgb.toColorRef()), 4)
            api.DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, IntByReference(textArgb.toColorRef()), 4)
        }.onFailure { System.err.println("AniMix: title bar color not applied: $it") }
    }

    /** ARGB to Win32 COLORREF (0x00BBGGRR). */
    private fun Int.toColorRef(): Int {
        val r = (this shr 16) and 0xFF
        val g = (this shr 8) and 0xFF
        val b = this and 0xFF
        return (b shl 16) or (g shl 8) or r
    }
}
