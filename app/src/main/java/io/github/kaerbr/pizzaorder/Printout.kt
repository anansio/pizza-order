package io.github.kaerbr.pizzaorder

/**
 * The DantSu markup that [PrintStyle.format] produces, parsed just far enough to draw it on screen:
 * so the confirmation slip shows exactly what the printer gets, for every print style.
 * Covers what the styles use: [L]/[C]/[R], <b>, <font size='…'> and <qrcode>; other tags are dropped.
 */
object Printout {

    // Shared with [Printer], so paper and screen agree on the line width.
    /** Chars per line at normal size. */
    const val LINE_CHARS = 32
    /** Printable paper width in mm; QR sizes are in mm too. */
    const val PAPER_MM = 48f

    enum class Align { Left, Center, Right }
    enum class Size { Normal, Big, Tall, Wide }

    data class Run(val text: String, val bold: Boolean = false, val size: Size = Size.Normal)

    sealed interface Segment { val align: Align }
    data class TextSegment(override val align: Align, val runs: List<Run>) : Segment
    data class QrSegment(override val align: Align, val url: String, val sizeMm: Int) : Segment

    /** One printed line; no segments = an empty paper line. */
    data class Line(val segments: List<Segment>)

    internal val alignTag = Regex("""\[([LCR])]""")
    private val qrTag = Regex("""<qrcode(?:\s+size='(\d+)')?>(.*?)</qrcode>""")
    private val anyTag = Regex("""<(/?)(\w+)(?:\s+size='(\w+)')?[^>]*>""")

    fun parse(markup: String): List<Line> = markup.lines()
        .map(::parseLine)
        // The feed lines at the end only push the paper past the cutter.
        .dropLastWhile { it.segments.isEmpty() }

    private fun parseLine(line: String): Line {
        val segments = mutableListOf<Segment>()
        var align = Align.Left
        var start = 0
        fun flush(end: Int) {
            val chunk = line.substring(start, end)
            if (chunk.isNotEmpty()) segments += parseSegment(align, chunk)
        }
        for (m in alignTag.findAll(line)) {
            flush(m.range.first)
            align = when (m.groupValues[1]) { "C" -> Align.Center; "R" -> Align.Right; else -> Align.Left }
            start = m.range.last + 1
        }
        flush(line.length)
        return Line(segments.filter { it !is TextSegment || it.runs.any { r -> r.text.isNotBlank() } })
    }

    private fun parseSegment(align: Align, chunk: String): Segment {
        qrTag.find(chunk)?.let { return QrSegment(align, it.groupValues[2], it.groupValues[1].toIntOrNull() ?: 20) }
        val runs = mutableListOf<Run>()
        var bold = false
        var size = Size.Normal
        var start = 0
        fun flush(end: Int) {
            if (end > start) runs += Run(chunk.substring(start, end), bold, size)
        }
        for (m in anyTag.findAll(chunk)) {
            flush(m.range.first)
            val closing = m.groupValues[1] == "/"
            when (m.groupValues[2]) {
                "b" -> bold = !closing
                "font" -> size = if (closing) Size.Normal else when (m.groupValues[3]) {
                    "big" -> Size.Big; "tall" -> Size.Tall; "wide" -> Size.Wide; else -> Size.Normal
                }
            }
            start = m.range.last + 1
        }
        flush(chunk.length)
        return TextSegment(align, runs)
    }
}
