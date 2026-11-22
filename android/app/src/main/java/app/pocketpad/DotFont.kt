package app.pocketpad

/** 5x7 dot-matrix glyphs: one Int per row, bit 4 = leftmost dot. Generated; edit with care. */
object DotFont {
    private val glyphs: Map<Char, IntArray> = mapOf(
        '0' to intArrayOf(14, 17, 19, 21, 25, 17, 14),
        '1' to intArrayOf(4, 12, 4, 4, 4, 4, 14),
        '2' to intArrayOf(14, 17, 1, 2, 4, 8, 31),
        '3' to intArrayOf(31, 2, 4, 2, 1, 17, 14),
        '4' to intArrayOf(2, 6, 10, 18, 31, 2, 2),
        '5' to intArrayOf(31, 16, 30, 1, 1, 17, 14),
        '6' to intArrayOf(6, 8, 16, 30, 17, 17, 14),
        '7' to intArrayOf(31, 1, 2, 4, 8, 8, 8),
        '8' to intArrayOf(14, 17, 17, 14, 17, 17, 14),
        '9' to intArrayOf(14, 17, 17, 15, 1, 2, 12),
        'A' to intArrayOf(14, 17, 17, 31, 17, 17, 17),
        'B' to intArrayOf(30, 17, 17, 30, 17, 17, 30),
        'C' to intArrayOf(14, 17, 16, 16, 16, 17, 14),
        'D' to intArrayOf(28, 18, 17, 17, 17, 18, 28),
        'E' to intArrayOf(31, 16, 16, 30, 16, 16, 31),
        'F' to intArrayOf(31, 16, 16, 30, 16, 16, 16),
        'G' to intArrayOf(14, 17, 16, 23, 17, 17, 15),
        'H' to intArrayOf(17, 17, 17, 31, 17, 17, 17),
        'I' to intArrayOf(14, 4, 4, 4, 4, 4, 14),
        'J' to intArrayOf(7, 2, 2, 2, 2, 18, 12),
        'K' to intArrayOf(17, 18, 20, 24, 20, 18, 17),
        'L' to intArrayOf(16, 16, 16, 16, 16, 16, 31),
        'M' to intArrayOf(17, 27, 21, 21, 17, 17, 17),
        'N' to intArrayOf(17, 25, 21, 19, 17, 17, 17),
        'O' to intArrayOf(14, 17, 17, 17, 17, 17, 14),
        'P' to intArrayOf(30, 17, 17, 30, 16, 16, 16),
        'Q' to intArrayOf(14, 17, 17, 17, 21, 18, 13),
        'R' to intArrayOf(30, 17, 17, 30, 20, 18, 17),
        'S' to intArrayOf(15, 16, 16, 14, 1, 1, 30),
        'T' to intArrayOf(31, 4, 4, 4, 4, 4, 4),
        'U' to intArrayOf(17, 17, 17, 17, 17, 17, 14),
        'V' to intArrayOf(17, 17, 17, 17, 17, 10, 4),
        'W' to intArrayOf(17, 17, 17, 21, 21, 27, 17),
        'X' to intArrayOf(17, 17, 10, 4, 10, 17, 17),
        'Y' to intArrayOf(17, 17, 10, 4, 4, 4, 4),
        'Z' to intArrayOf(31, 1, 2, 4, 8, 16, 31),
        ' ' to intArrayOf(0, 0, 0, 0, 0, 0, 0),
        '.' to intArrayOf(0, 0, 0, 0, 0, 0, 4),
        ':' to intArrayOf(0, 4, 4, 0, 4, 4, 0),
        '-' to intArrayOf(0, 0, 0, 31, 0, 0, 0),
        '+' to intArrayOf(0, 4, 4, 31, 4, 4, 0),
        '/' to intArrayOf(1, 1, 2, 4, 8, 16, 16),
        '>' to intArrayOf(16, 8, 4, 2, 4, 8, 16),
        '<' to intArrayOf(1, 2, 4, 8, 4, 2, 1),
        '_' to intArrayOf(0, 0, 0, 0, 0, 0, 31),
    )

    private val blank = IntArray(7)

    fun rows(c: Char): IntArray = glyphs[c.uppercaseChar()] ?: blank
}
