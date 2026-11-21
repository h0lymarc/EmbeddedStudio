package com.h0lymarc.eside.search

import com.h0lymarc.eside.*

import com.h0lymarc.eside.manager.ProjectManager
import java.io.File

private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

private fun isWholeWord(line: String, start: Int, end: Int): Boolean =
    (start == 0 || !isWordChar(line[start - 1])) &&
        (end >= line.length || !isWordChar(line[end]))

internal fun findMatches(
    path: String,
    text: String,
    query: String,
    matchCase: Boolean,
    wholeWord: Boolean,
    limit: Int
): List<SearchHit> {
    if (query.isEmpty() || limit <= 0) return emptyList()
    val hits = ArrayList<SearchHit>()
    var lineIndex = 0
    for (line in text.lineSequence()) {
        var from = 0
        while (from <= line.length - query.length) {
            val at = line.indexOf(query, from, ignoreCase = !matchCase)
            if (at < 0) break
            val end = at + query.length
            if (!wholeWord || isWholeWord(line, at, end)) {
                val leading = line.length - line.trimStart().length
                hits += SearchHit(
                    path = path,
                    line = lineIndex,
                    col = at,
                    length = query.length,
                    preview = line.trim().take(PREVIEW_CHARS),
                    previewStart = at - leading
                )
                if (hits.size >= limit) return hits
            }
            from = end
        }
        lineIndex++
    }
    return hits
}

private fun isSearchable(file: File): Boolean {
    val ext = file.extension.lowercase()
    return ext.isEmpty() || ext in SEARCH_EXTENSIONS
}

internal fun searchWholeProject(
    dir: File,
    activePath: String?,
    activeText: String,
    drafts: Map<String, String>,
    query: String,
    matchCase: Boolean,
    wholeWord: Boolean
): List<SearchHit> {
    val out = ArrayList<SearchHit>()
    for (file in ProjectManager.listFiles(dir)) {
        if (out.size >= MAX_HITS) break
        val path = file.absolutePath
        val text = when {
            path == activePath -> activeText
            path in drafts -> drafts.getValue(path)
            file.length() > MAX_SEARCH_FILE_BYTES || !isSearchable(file) -> continue
            else -> runCatching { file.readText() }.getOrNull() ?: continue
        }
        out += findMatches(path, text, query, matchCase, wholeWord, MAX_HITS - out.size)
    }
    return out
}

internal fun pickHitIndex(hits: List<SearchHit>, line: Int, col: Int, forward: Boolean): Int {
    if (hits.isEmpty()) return -1
    return if (forward) {
        val i = hits.indexOfFirst { it.line > line || (it.line == line && it.col >= col) }
        if (i < 0) 0 else i
    } else {
        val i = hits.indexOfLast { it.line < line || (it.line == line && it.col < col) }
        if (i < 0) hits.lastIndex else i
    }
}
