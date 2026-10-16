package app.animix

import app.animix.core.RichBlock
import app.animix.core.RichTextParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RichTextTest {

    @Test
    fun shikimoriDescriptionKeepsLinkTextAndParagraphs() {
        val html = """<div class="b-text_with_paragraphs">Отряд героя <a href="/characters/1" class="bubbled b-link">Химмеля</a> вернулся.<br><br>Фрирен &mdash; эльф.</div>"""
        val text = RichTextParser.parseHtml(html)
        assertEquals(listOf("Отряд героя Химмеля вернулся.", "Фрирен — эльф."), text.blocks.map { it.plain })
    }

    @Test
    fun bbCodeWikiLinksAndTagsAreCleaned() {
        val text = RichTextParser.parseBbCode("Скучающий [[Синигами]] [character=75]Рюк[/character] роняет тетрадь.\r\n\r\nЕё находит [b]Лайт[/b].")
        assertEquals(listOf("Скучающий Синигами Рюк роняет тетрадь.", "Её находит Лайт."), text.blocks.map { it.plain })
        val bold = (text.blocks[1] as RichBlock.Paragraph).spans.single { it.bold }
        assertEquals("Лайт", bold.text)
    }

    @Test
    fun shikimoriQuoteGetsAuthorAndRepliesAreDropped() {
        val html = """<a class="b-mention bubbled"><s>@</s><span>lg545</span></a>, <br>""" +
            """<blockquote class="b-quote-v2"><div class="quoteable"><a class="b-mention"><img src="x.png"><span>helltime</span></a></div>""" +
            """<div class="quote-content">Старый текст</div></blockquote>Ответ <img class="smiley" alt=":)"> тут""" +
            """<div class="b-replies translated-before">Ответы: <a>кто-то</a></div>"""
        val text = RichTextParser.parseHtml(html)
        val quote = text.blocks.filterIsInstance<RichBlock.Quote>().single()
        assertEquals("helltime", quote.author)
        assertEquals("Старый текст", quote.plain)
        assertEquals("@lg545,", text.blocks.first().plain)
        assertEquals("Ответ тут", text.blocks.last().plain)
        assertTrue(text.plain.indexOf("Ответы") < 0)
    }

    @Test
    fun yummySpoilerBecomesHiddenBlock() {
        val html = """<div>До</div><spoiler><button>Показать</button><div>Тайна</div></spoiler><div>После</div>"""
        val blocks = RichTextParser.parseHtml(html).blocks
        assertEquals(3, blocks.size)
        val spoiler = blocks[1] as RichBlock.Spoiler
        assertEquals("Тайна", spoiler.blocks.single().plain)
    }
}
