package app.animix.core

import kotlinx.serialization.Serializable

/** Text with light structure, parsed from Shikimori/YummyAnime HTML or BBCode. */
@Serializable
data class RichText(val blocks: List<RichBlock>) {
    val isBlank: Boolean get() = blocks.all { it.isBlank }

    /** Plain text, for previews and search. */
    val plain: String get() = blocks.joinToString("\n\n") { it.plain }.trim()

    companion object {
        val Empty = RichText(emptyList())

        fun plain(text: String): RichText = RichText(
            text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
                .map { RichBlock.Paragraph(listOf(RichSpan(it))) },
        )
    }
}

@Serializable
data class RichSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false)

@Serializable
sealed interface RichBlock {
    val isBlank: Boolean
    val plain: String

    @Serializable
    data class Paragraph(val spans: List<RichSpan>) : RichBlock {
        override val isBlank get() = spans.all { it.text.isBlank() }
        override val plain get() = spans.joinToString("") { it.text }.trim()
    }

    @Serializable
    data class Quote(val author: String?, val blocks: List<RichBlock>) : RichBlock {
        override val isBlank get() = blocks.all { it.isBlank }
        override val plain get() = blocks.joinToString("\n") { it.plain }
    }

    /** Hidden until tapped. */
    @Serializable
    data class Spoiler(val label: String, val blocks: List<RichBlock>) : RichBlock {
        override val isBlank get() = blocks.all { it.isBlank }
        override val plain get() = "[спойлер]"
    }
}

/**
 * A small, forgiving HTML reader for the markup Shikimori and YummyAnime send:
 * paragraphs, line breaks, bold/italic, quotes (with their author) and spoilers.
 * Links become their text; images, smileys, buttons and reply lists are dropped.
 */
object RichTextParser {

    fun parseHtml(html: String): RichText {
        val root = Frame(Kind.Root)
        val frames = ArrayDeque<Frame>().apply { addLast(root) }
        // Open elements, so closing tags pop the right frame even in sloppy HTML.
        val open = ArrayDeque<Open>()
        var skipDepth = 0
        var bold = 0
        var italic = 0

        var i = 0
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            val textEnd = if (lt < 0) html.length else lt
            if (textEnd > i && skipDepth == 0) {
                frames.last().text(decodeEntities(html.substring(i, textEnd)), bold > 0, italic > 0)
            }
            if (lt < 0) break
            val gt = html.indexOf('>', lt)
            if (gt < 0) break
            val tag = html.substring(lt + 1, gt)
            i = gt + 1
            if (tag.startsWith("!")) continue

            val closing = tag.startsWith("/")
            val name = tag.removePrefix("/").trim().takeWhile { it.isLetterOrDigit() }.lowercase()
            val classes = classOf(tag)
            val selfClosing = tag.endsWith("/") || name in voidTags

            if (closing) {
                // Pop up to and including the nearest element with this name.
                if (open.none { it.name == name }) continue
                while (open.isNotEmpty()) {
                    val element = open.removeLast()
                    if (element.skips) skipDepth--
                    if (element.bold) bold--
                    if (element.italic) italic--
                    if (element.frame != null) frames.removeLast().closeInto(frames.last())
                    if (element.blockBreak) frames.last().breakParagraph()
                    if (element.name == name) break
                }
                continue
            }

            if (skipDepth > 0) {
                if (!selfClosing) open.addLast(Open(name, skips = true).also { skipDepth++ })
                continue
            }

            when {
                name == "br" -> frames.last().lineBreak()
                name == "img" || name == "hr" || name == "input" -> Unit
                selfClosing -> Unit
                name in skipTags || classes.any { it in skipClasses } -> {
                    open.addLast(Open(name, skips = true)); skipDepth++
                }
                "quoteable" in classes -> {
                    // The quoted author's name: read it into the quote frame, not into the text.
                    val frame = Frame(Kind.Author)
                    frames.addLast(frame)
                    open.addLast(Open(name, frame = frame))
                }
                name == "blockquote" || classes.any { it.startsWith("b-quote") } -> {
                    frames.last().breakParagraph()
                    val frame = Frame(Kind.Quote)
                    frames.addLast(frame)
                    open.addLast(Open(name, frame = frame))
                }
                name == "spoiler" || classes.any { it.startsWith("b-spoiler") } -> {
                    frames.last().breakParagraph()
                    val frame = Frame(Kind.Spoiler)
                    frames.addLast(frame)
                    open.addLast(Open(name, frame = frame))
                }
                name == "b" || name == "strong" || name.matches(heading) -> {
                    if (name.matches(heading)) frames.last().breakParagraph()
                    open.addLast(Open(name, bold = true, blockBreak = name.matches(heading))); bold++
                }
                name == "i" || name == "em" -> {
                    open.addLast(Open(name, italic = true)); italic++
                }
                name in blockTags -> {
                    frames.last().breakParagraph()
                    open.addLast(Open(name, blockBreak = true))
                }
                else -> open.addLast(Open(name))
            }
        }
        while (frames.size > 1) frames.removeLast().closeInto(frames.last())
        root.breakParagraph()
        return RichText(root.blocks.filterNot { it.isBlank })
    }

    /** Shikimori BBCode (used when no HTML is available). */
    fun parseBbCode(text: String): RichText {
        val html = text
            .replace(Regex("""\[\[([^\]|]*\|)?([^\]]*)]]"""), "$2") // [[Синигами]] wiki links
            .replace(Regex("""\[(b|i)]""", RegexOption.IGNORE_CASE), "<$1>")
            .replace(Regex("""\[/(b|i)]""", RegexOption.IGNORE_CASE), "</$1>")
            .replace(Regex("""\[spoiler(=[^\]]*)?]""", RegexOption.IGNORE_CASE), "<spoiler>")
            .replace(Regex("""\[/spoiler]""", RegexOption.IGNORE_CASE), "</spoiler>")
            .replace(Regex("""\[quote(=[^\]]*)?]""", RegexOption.IGNORE_CASE), "<blockquote>")
            .replace(Regex("""\[/quote]""", RegexOption.IGNORE_CASE), "</blockquote>")
            .replace(Regex("""\[/?[a-z_]+(=[^\]]*)?]""", RegexOption.IGNORE_CASE), "")
            .replace("\r\n", "\n")
            .replace("\n", "<br>")
        return parseHtml(html)
    }

    private enum class Kind { Root, Quote, Spoiler, Author }

    private class Open(
        val name: String,
        val frame: Frame? = null,
        val skips: Boolean = false,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val blockBreak: Boolean = false,
    )

    private class Frame(val kind: Kind) {
        val blocks = mutableListOf<RichBlock>()
        private val spans = mutableListOf<RichSpan>()
        private val authorText = StringBuilder()
        private var author: String? = null

        fun text(raw: String, bold: Boolean, italic: Boolean) {
            if (kind == Kind.Author) {
                authorText.append(raw)
                return
            }
            val text = raw.replace(whitespace, " ")
            if (text.isEmpty()) return
            val previous = spans.lastOrNull()?.text
            // No leading space at a line start, and no double space where an element was dropped.
            val trimLead = previous == null || previous.endsWith("\n") || previous.endsWith(" ")
            val cleaned = if (trimLead) text.trimStart() else text
            if (cleaned.isEmpty()) return
            val last = spans.lastOrNull()
            if (last != null && last.bold == bold && last.italic == italic) {
                spans[spans.lastIndex] = last.copy(text = last.text + cleaned)
            } else {
                spans += RichSpan(cleaned, bold, italic)
            }
        }

        fun lineBreak() {
            if (kind == Kind.Author) return
            if (spans.isEmpty()) return
            val last = spans.last()
            // Two line breaks in a row start a new paragraph.
            if (last.text.endsWith("\n")) breakParagraph()
            else spans[spans.lastIndex] = last.copy(text = last.text.trimEnd() + "\n")
        }

        fun breakParagraph() {
            if (spans.isEmpty()) return
            val trimmed = spans.mapIndexed { index, span ->
                if (index == spans.lastIndex) span.copy(text = span.text.trimEnd()) else span
            }.filter { it.text.isNotEmpty() }
            if (trimmed.isNotEmpty()) blocks += RichBlock.Paragraph(trimmed)
            spans.clear()
        }

        /** Finishes this frame and hands its result to [parent]. */
        fun closeInto(parent: Frame) {
            breakParagraph()
            val block = when (kind) {
                Kind.Author -> {
                    parent.author = authorText.toString().replace(whitespace, " ").trim()
                    null
                }
                Kind.Quote -> RichBlock.Quote(author?.ifBlank { null }, blocks.toList())
                Kind.Spoiler -> RichBlock.Spoiler("Спойлер", blocks.toList())
                Kind.Root -> null
            }
            if (block != null && !block.isBlank) {
                parent.breakParagraph()
                parent.blocks += block
            }
        }
    }

    private fun classOf(tag: String): List<String> =
        classAttr.find(tag)?.groupValues?.get(1)?.split(' ')?.filter { it.isNotBlank() }.orEmpty()

    private val classAttr = Regex("""class\s*=\s*"([^"]*)"""")
    private val whitespace = Regex("""[ \t\r\n ]+""")
    private val heading = Regex("h[1-6]")
    private val voidTags = setOf("br", "img", "hr", "input", "meta", "link", "source", "wbr")
    private val blockTags = setOf("p", "div", "li", "ul", "ol", "center", "section", "article", "table", "tr")
    private val skipTags = setOf("button", "script", "style", "noscript", "svg", "label", "iframe")
    private val skipClasses = setOf("b-replies", "marker", "before", "after", "smiley", "b-image")

    fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        return entity.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.drop(2).toIntOrNull(16)?.let(::codePoint) ?: match.value
                body.startsWith("#") -> body.drop(1).toIntOrNull()?.let(::codePoint) ?: match.value
                else -> namedEntities[body] ?: match.value
            }
        }
    }

    private fun codePoint(code: Int): String = if (code in 0..0xFFFF) code.toChar().toString() else {
        val v = code - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }

    private val entity = Regex("""&(#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);""")
    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "laquo" to "«", "raquo" to "»", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„", "copy" to "©",
    )
}
