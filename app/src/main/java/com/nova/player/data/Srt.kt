package com.nova.player.data

data class Cue(val startMs: Long, val endMs: Long, val text: String)

private fun ts(ms: Long): String {
    val h = ms / 3_600_000; val m = ms / 60_000 % 60; val s = ms / 1000 % 60; val x = ms % 1000
    return "%02d:%02d:%02d,%03d".format(h, m, s, x)
}

fun toSrt(cues: List<Cue>): String = buildString {
    cues.forEachIndexed { i, c ->
        append(i + 1).append('\n').append(ts(c.startMs)).append(" --> ").append(ts(c.endMs)).append('\n')
        append(c.text.trim()).append("\n\n")
    }
}

private fun parseTs(t: String): Long {
    val p = t.trim().replace('.', ',').split(':', ',')
    if (p.size < 4) return 0
    return p[0].toLong() * 3_600_000 + p[1].toLong() * 60_000 + p[2].toLong() * 1000 + p[3].padEnd(3, '0').take(3).toLong()
}

fun parseSrt(text: String): List<Cue> =
    text.replace("\r", "").trim().split(Regex("\n\\s*\n")).mapNotNull { block ->
        val lines = block.lines()
        val ti = lines.indexOfFirst { "-->" in it }
        if (ti < 0) return@mapNotNull null
        val (a, b) = lines[ti].split("-->").map { it.trim().substringBefore(' ') }
        val body = lines.drop(ti + 1).joinToString("\n").trim()
        if (body.isEmpty()) null else runCatching { Cue(parseTs(a), parseTs(b), body) }.getOrNull()
    }
