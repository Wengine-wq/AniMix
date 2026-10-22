package app.animix.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import app.animix.App
import app.animix.AppGraph
import androidx.compose.runtime.CompositionLocalProvider
import app.animix.ui.components.LocalLoadPreviewDelay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders the app off-screen into a PNG, for design reviews and docs:
 * `AniMix.exe --snapshot=out.png --size=1280x820 --wait=8 animix://home`.
 * Add `--font-scale=1.3` to check enlarged system text.
 * `--frames=6000,6100,6200` captures intermediate animation frames; `--scroll=400` checks lower content.
 * `--hover=200,200 --leave=4000` checks mouse pause and resume without opening a window.
 * `--click=1000:Начать сессию` invokes a visible button by its semantic label after 1 second; repeat for a sequence.
 * `--load-delay-ms=60000` holds network loading states for deterministic skeleton previews.
 * `--drag=1000:description:0:250` drags a semantic target over 700 ms; `--touch` uses touch input.
 * Nothing is shown on screen and no window takes focus.
 */
@OptIn(ExperimentalComposeUiApi::class)
object Snapshot {
    fun runIfRequested(args: Array<String>, graph: AppGraph): Boolean {
        val out = args.firstOrNull { it.startsWith("--snapshot=") }?.substringAfter('=') ?: return false
        val (width, height) = (args.firstOrNull { it.startsWith("--size=") }?.substringAfter('=') ?: "1280x820")
            .split('x').map { it.toInt() }
        val seconds = args.firstOrNull { it.startsWith("--wait=") }?.substringAfter('=')?.toLong() ?: 8
        val fontScale = args.firstOrNull { it.startsWith("--font-scale=") }?.substringAfter('=')?.toFloat() ?: 1f
        val loadDelay = args.firstOrNull { it.startsWith("--load-delay-ms=") }?.substringAfter('=')?.toLong()?.coerceAtLeast(0) ?: 0L
        val link = args.firstOrNull { it.startsWith("animix://") }
        val frames = args.firstOrNull { it.startsWith("--frames=") }?.substringAfter('=')?.split(',')
            ?.map { it.toLong() }?.filter { it >= 0 }?.sorted().orEmpty()
        val scroll = args.firstOrNull { it.startsWith("--scroll=") }?.substringAfter('=')?.toFloat() ?: 0f
        val hover = args.firstOrNull { it.startsWith("--hover=") }?.substringAfter('=')?.split(',')?.map(String::toFloat)
        val leave = args.firstOrNull { it.startsWith("--leave=") }?.substringAfter('=')?.toLong()
        val clicks = args.filter { it.startsWith("--click=") }.map {
            val action = it.substringAfter('=').split(':', limit = 2)
            action[0].toLong() to action[1]
        }.sortedBy { it.first }

        val drag = args.firstOrNull { it.startsWith("--drag=") }?.substringAfter('=')?.split(':', limit = 4)
        val touch = "--touch" in args
        val scene = ImageComposeScene(width, height, Density(1f, fontScale)) {
            CompositionLocalProvider(LocalLoadPreviewDelay provides loadDelay) { App(graph, startLink = link) }
        }
        try {
            val started = System.nanoTime()
            val duration = maxOf(seconds * 1000, (frames.lastOrNull() ?: 0) + 100)
            var frameIndex = 0
            var scrolled = false
            var hovered = false
            var left = false
            var clickIndex = 0
            var dragStart: Offset? = null
            var dragEnded = false
            while ((System.nanoTime() - started) / 1_000_000 < duration) {
                val rendered = scene.render(System.nanoTime())
                val elapsed = (System.nanoTime() - started) / 1_000_000
                if (clickIndex < clicks.size && elapsed >= clicks[clickIndex].first) {
                    val label = clicks[clickIndex].second
                    val target = scene.semanticsOwners.asSequence().flatMap { nodes(it.rootSemanticsNode) }.firstOrNull { node ->
                        node.config.getOrNull(SemanticsActions.OnClick)?.action != null &&
                            nodes(node).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true }
                    }
                    if (target?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true) clickIndex++
                }
                if (drag != null && !dragEnded && elapsed >= drag[0].toLong()) {
                    if (dragStart == null) {
                        val target = scene.semanticsOwners.asSequence().flatMap { nodes(it.rootSemanticsNode) }.firstOrNull {
                            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(drag[1]) == true
                        }
                        target?.let {
                            dragStart = it.boundsInRoot.center
                            scene.sendPointerEvent(PointerEventType.Press, dragStart!!,
                                type = if (touch) PointerType.Touch else PointerType.Mouse,
                                buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                        }
                    }
                    dragStart?.let { origin ->
                        val progress = ((elapsed - drag[0].toLong()).toFloat() / 700f).coerceIn(0f, 1f)
                        val point = origin + Offset(drag[2].toFloat(), drag[3].toFloat()) * progress
                        scene.sendPointerEvent(if (progress < 1f) PointerEventType.Move else PointerEventType.Release, point,
                            type = if (touch) PointerType.Touch else PointerType.Mouse,
                            buttons = PointerButtons(isPrimaryPressed = progress < 1f),
                            button = if (progress == 1f) PointerButton.Primary else null)
                        dragEnded = progress == 1f
                    }
                }
                if (hover != null && !hovered && elapsed >= 800) {
                    scene.sendPointerEvent(PointerEventType.Move, Offset(hover[0], hover[1]))
                    hovered = true
                }
                if (hovered && !left && leave != null && elapsed >= leave) {
                    scene.sendPointerEvent(PointerEventType.Exit, Offset.Zero)
                    left = true
                }
                if (!scrolled && scroll != 0f && elapsed >= 800) {
                    val point = Offset(width / 2f, height / 2f)
                    scene.sendPointerEvent(PointerEventType.Move, point)
                    scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, scroll / 64f))
                    scrolled = true
                }
                while (frameIndex < frames.size && elapsed >= frames[frameIndex]) {
                    val target = File(out).let { File(it.parentFile, "${it.nameWithoutExtension}-${frames[frameIndex]}ms.png") }
                    target.parentFile?.mkdirs()
                    target.writeBytes(rendered.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    frameIndex++
                }
                Thread.sleep(16)
            }
            if (drag != null) check(dragEnded) { "Drag target was not found or gesture did not finish" }
            val png = scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)
                ?: error("Could not encode the snapshot")
            File(out).apply { parentFile?.mkdirs() }.writeBytes(png.bytes)
        } finally {
            scene.close()
            runBlocking { graph.flushUserData() }
        }
        return true
    }

    private fun nodes(root: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(root)
        for (child in root.children) yieldAll(nodes(child))
    }
}
