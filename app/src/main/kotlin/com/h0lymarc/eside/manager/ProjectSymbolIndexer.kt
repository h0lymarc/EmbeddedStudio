package com.h0lymarc.eside.manager

import android.content.Context
import java.io.File

enum class SymbolKind(val label: String) {
    CLASS("class"),
    STRUCT("struct"),
    ENUM("enum"),
    METHOD("method"),
    FIELD("field"),
    FUNCTION("func"),
    MACRO("macro"),
    HEADER("header"),
    DIRECTIVE("#")
}

data class CodeSymbol(
    val name: String,
    val kind: SymbolKind,
    val signature: String,
    val detail: String = "",
    val owner: String? = null,
    val filePath: String,
    val line: Int,
    val col: Int
)

data class ProjectIndex(
    val symbols: List<CodeSymbol> = emptyList(),
    val classesByName: Map<String, List<CodeSymbol>> = emptyMap(),
    val headerFiles: List<String> = emptyList(),
    val allKeywords: List<String> = emptyList()
)

enum class DiagnosticSeverity { ERROR, WARNING }

data class CodeDiagnostic(
    val path: String,
    val line: Int,
    val col: Int,
    val message: String,
    val severity: DiagnosticSeverity
)

sealed interface CompletionContext {
    data class MemberAccess(
        val typeName: String,
        val prefix: String,
        val replaceLen: Int
    ) : CompletionContext

    data class LocalInclude(
        val prefix: String,
        val replaceLen: Int
    ) : CompletionContext

    data class SystemInclude(
        val prefix: String,
        val replaceLen: Int
    ) : CompletionContext

    data class PreprocessorDirective(
        val prefix: String,
        val replaceLen: Int
    ) : CompletionContext
}

data class IntelliSenseSuggestion(
    val label: String,
    val insertText: String,
    val detail: String,
    val kind: SymbolKind,
    val replaceLen: Int
)

object ProjectSymbolIndexer {

    private val CODE_EXTENSIONS = setOf("ino", "h", "hpp", "hh", "hxx", "cpp", "cc", "cxx", "c")
    private val HEADER_EXTENSIONS = setOf("h", "hpp", "hh", "hxx")

    private val CONTROL_KEYWORDS = setOf(
        "if", "else", "for", "while", "switch", "case", "default", "return",
        "sizeof", "alignof", "decltype", "typedef", "using", "namespace",
        "public", "private", "protected", "class", "struct", "enum", "delete", "new"
    )

    private val CLASS_OR_STRUCT_REGEX =
        Regex("""\b(class|struct)\s+([A-Za-z_][A-Za-z0-9_]*)\b(?:\s*final\b)?(?:\s*:\s*[^{;]+)?\s*\{""")
    private val ENUM_REGEX =
        Regex("""\benum\s+(?:class\s+|struct\s+)?([A-Za-z_][A-Za-z0-9_]*)\b(?:\s*:\s*[^{;]+)?\s*\{""")
    private val DEFINE_REGEX =
        Regex("""^\s*#\s*define\s+([A-Za-z_][A-Za-z0-9_]*)(?:\(([^)]*)\))?""")
    private val INCLUDE_LOCAL_REGEX =
        Regex("""^\s*#\s*include\s*"([^"]+)"""")
    private val INCLUDE_SYS_REGEX =
        Regex("""^\s*#\s*include\s*<([^>]+)>""")
    private val FUNCTION_REGEX =
        Regex("""(?:^|[;{}])\s*(?:template\s*<[^>]*>\s*)?(?:(?:inline|static|virtual|explicit|constexpr|const|unsigned|signed|long|short|struct|enum)\s+)*([A-Za-z_~][A-Za-z0-9_:<>*&\s]*?)\s+([A-Za-z_~][A-Za-z0-9_]*)\s*\(([^;{}]*)\)\s*(?:const\s*)?(?:override\s*)?(?:final\s*)?(?:=\s*0\s*)?(?:\{|;)""")
    private val FIELD_REGEX =
        Regex("""^\s*(?:(?:static|const|constexpr|mutable|volatile|unsigned|signed|long|short)\s+)*([A-Za-z_][A-Za-z0-9_:<>]*\s*[*&]?)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\[[^\]]*\])?\s*(?:=[^;]*)?;""")
    private val VAR_DECL_REGEX =
        Regex("""(?:^|[;{}])\s*(?:(?:const|static|volatile|extern|mutable)\s+)*([A-Za-z_][A-Za-z0-9_:<>]*)\s*[*&]?\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:\([^;{}]*\)|\[[^\]]*\]|=\s*[^;]+)?\s*;""")

    fun indexProject(
        context: Context,
        projectDir: File?,
        activePath: String?,
        activeText: String,
        drafts: Map<String, String>
    ): ProjectIndex {
        if (projectDir == null || !projectDir.isDirectory) return ProjectIndex()

        val allSymbols = ArrayList<CodeSymbol>()
        val headerFiles = ArrayList<String>()
        val referencedSysHeaders = LinkedHashSet<String>()

        val projectFiles = ProjectManager.listFiles(projectDir)
        for (file in projectFiles) {
            val ext = file.extension.lowercase()
            if (ext !in CODE_EXTENSIONS) continue

            val rel = runCatching { file.relativeTo(projectDir).path.replace('\\', '/') }
                .getOrElse { file.name }
            if (ext in HEADER_EXTENSIONS) {
                headerFiles += rel
            }

            val path = file.absolutePath
            val text = when {
                path == activePath -> activeText
                path in drafts -> drafts.getValue(path)
                file.length() > 512_000L -> continue
                else -> runCatching { file.readText() }.getOrNull() ?: continue
            }

            allSymbols += parseFileSymbols(path, text)

            INCLUDE_SYS_REGEX.findAll(text).forEach { m ->
                referencedSysHeaders += m.groupValues[1].trim()
            }
        }

        // Also index #include <...> headers from installed libraries (fast, targeted to included headers)
        if (referencedSysHeaders.isNotEmpty()) {
            val libDirs = LibraryManager.librariesDir(context)
                .listFiles { f -> f.isDirectory }
                .orEmpty()
            val visited = HashSet<String>()
            for (headerName in referencedSysHeaders) {
                for (libDir in libDirs) {
                    val candidate = File(libDir, headerName).takeIf { it.isFile }
                        ?: File(libDir, "src/$headerName").takeIf { it.isFile }
                        ?: continue
                    val canonical = candidate.canonicalPath
                    if (!visited.add(canonical) || candidate.length() > 512_000L) continue
                    val text = runCatching { candidate.readText() }.getOrNull() ?: continue
                    allSymbols += parseFileSymbols(canonical, text)
                }
            }
        }

        val classesByName = LinkedHashMap<String, MutableList<CodeSymbol>>()
        for (sym in allSymbols) {
            val owner = sym.owner ?: continue
            classesByName.getOrPut(owner) { ArrayList() }.add(sym)
        }
        // Ensure empty classes/structs are still registered as known types
        for (sym in allSymbols) {
            if (sym.kind == SymbolKind.CLASS || sym.kind == SymbolKind.STRUCT) {
                classesByName.putIfAbsent(sym.name, ArrayList())
            }
        }

        val keywords = allSymbols.asSequence()
            .map { it.name }
            .filter { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
            .distinct()
            .sorted()
            .toList()

        return ProjectIndex(
            symbols = allSymbols,
            classesByName = classesByName,
            headerFiles = headerFiles.distinct().sorted(),
            allKeywords = keywords
        )
    }

    fun parseFileSymbols(filePath: String, rawText: String): List<CodeSymbol> {
        val out = ArrayList<CodeSymbol>()
        val lineStarts = computeLineStarts(rawText)
        val cleaned = stripCommentsAndStrings(rawText)

        // 1. Macros (#define) from raw lines (skip include guards ending in _H / _HPP)
        rawText.lineSequence().forEachIndexed { lineIdx, line ->
            val m = DEFINE_REGEX.find(line) ?: return@forEachIndexed
            val name = m.groupValues[1]
            if (name.endsWith("_H") || name.endsWith("_HPP")) return@forEachIndexed
            val params = m.groups[2]?.value
            val sig = if (params != null) "#define $name($params)" else "#define $name"
            val col = line.indexOf(name).coerceAtLeast(0)
            out += CodeSymbol(
                name = name,
                kind = SymbolKind.MACRO,
                signature = sig,
                detail = "macro",
                filePath = filePath,
                line = lineIdx,
                col = col
            )
        }

        // 2. Classes, structs, enums and their members
        val classRanges = ArrayList<IntRange>()

        for (match in CLASS_OR_STRUCT_REGEX.findAll(cleaned)) {
            val keyword = match.groupValues[1]
            val className = match.groupValues[2]
            val kind = if (keyword == "struct") SymbolKind.STRUCT else SymbolKind.CLASS
            val nameOffset = match.range.first + match.value.indexOf(className)
            val (line, col) = offsetToLineCol(lineStarts, nameOffset)
            out += CodeSymbol(
                name = className,
                kind = kind,
                signature = "$keyword $className",
                detail = keyword,
                filePath = filePath,
                line = line,
                col = col
            )

            val openBrace = cleaned.indexOf('{', match.range.first)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(cleaned, openBrace) else -1
            if (openBrace >= 0 && closeBrace > openBrace) {
                classRanges += openBrace..closeBrace
                extractClassMembers(
                    rawText = rawText,
                    cleaned = cleaned,
                    lineStarts = lineStarts,
                    filePath = filePath,
                    className = className,
                    defaultPublic = (keyword == "struct"),
                    bodyStart = openBrace + 1,
                    bodyEnd = closeBrace,
                    out = out
                )
            }
        }

        for (match in ENUM_REGEX.findAll(cleaned)) {
            val enumName = match.groupValues[1]
            val nameOffset = match.range.first + match.value.indexOf(enumName)
            val (line, col) = offsetToLineCol(lineStarts, nameOffset)
            out += CodeSymbol(
                name = enumName,
                kind = SymbolKind.ENUM,
                signature = "enum $enumName",
                detail = "enum",
                filePath = filePath,
                line = line,
                col = col
            )
            val openBrace = cleaned.indexOf('{', match.range.first)
            val closeBrace = if (openBrace >= 0) findMatchingBrace(cleaned, openBrace) else -1
            if (openBrace >= 0 && closeBrace > openBrace) {
                classRanges += openBrace..closeBrace
                val body = cleaned.substring(openBrace + 1, closeBrace)
                Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\b\s*(?:=[^,}]*)?(?:,|$)""").findAll(body).forEach { em ->
                    val valName = em.groupValues[1]
                    if (valName !in CONTROL_KEYWORDS) {
                        val off = openBrace + 1 + em.range.first + em.value.indexOf(valName)
                        val (el, ec) = offsetToLineCol(lineStarts, off)
                        out += CodeSymbol(
                            name = valName,
                            kind = SymbolKind.FIELD,
                            signature = "$enumName::$valName",
                            detail = "enum value",
                            owner = enumName,
                            filePath = filePath,
                            line = el,
                            col = ec
                        )
                    }
                }
            }
        }

        // 3. Top-level free functions (outside class/struct/enum braces)
        for (match in FUNCTION_REGEX.findAll(cleaned)) {
            val offset = match.range.first
            if (classRanges.any { offset in it }) continue
            val retType = match.groupValues[1].trim().replace(Regex("\\s+"), " ")
            val fnName = match.groupValues[2].trim()
            val params = match.groupValues[3].trim().replace(Regex("\\s+"), " ")
            if (fnName in CONTROL_KEYWORDS || retType in CONTROL_KEYWORDS) continue
            if (fnName.startsWith("~")) continue

            val nameOffset = match.range.first + match.value.indexOf(fnName)
            val (line, col) = offsetToLineCol(lineStarts, nameOffset)
            out += CodeSymbol(
                name = fnName,
                kind = SymbolKind.FUNCTION,
                signature = "$retType $fnName($params)",
                detail = retType,
                filePath = filePath,
                line = line,
                col = col
            )
        }

        return out
    }

    private fun extractClassMembers(
        rawText: String,
        cleaned: String,
        lineStarts: List<Int>,
        filePath: String,
        className: String,
        defaultPublic: Boolean,
        bodyStart: Int,
        bodyEnd: Int,
        out: MutableList<CodeSymbol>
    ) {
        var isPublic = defaultPublic
        var braceDepth = 0
        var idx = bodyStart
        val statementBuf = StringBuilder()
        var statementStartOffset = bodyStart

        while (idx < bodyEnd) {
            val ch = cleaned[idx]
            if (ch == '{') {
                if (braceDepth == 0) {
                    val header = statementBuf.toString().trim()
                    parseMemberHeader(header, className, isPublic, filePath, lineStarts, statementStartOffset, out)
                    statementBuf.clear()
                }
                braceDepth++
            } else if (ch == '}') {
                if (braceDepth > 0) braceDepth--
                if (braceDepth == 0) {
                    statementBuf.clear()
                    statementStartOffset = idx + 1
                }
            } else if (braceDepth == 0) {
                if (statementBuf.isEmpty() && !ch.isWhitespace()) {
                    statementStartOffset = idx
                }
                statementBuf.append(ch)
                val current = statementBuf.toString().trim()
                when {
                    current == "public:" -> {
                        isPublic = true
                        statementBuf.clear()
                    }
                    current == "private:" || current == "protected:" -> {
                        isPublic = false
                        statementBuf.clear()
                    }
                    ch == ';' -> {
                        parseMemberHeader(current, className, isPublic, filePath, lineStarts, statementStartOffset, out)
                        statementBuf.clear()
                    }
                    ch == ':' && (current.endsWith("public:") || current.endsWith("private:") || current.endsWith("protected:")) -> {
                        isPublic = current.endsWith("public:")
                        statementBuf.clear()
                    }
                }
            }
            idx++
        }
    }

    private fun parseMemberHeader(
        statement: String,
        className: String,
        isPublic: Boolean,
        filePath: String,
        lineStarts: List<Int>,
        startOffset: Int,
        out: MutableList<CodeSymbol>
    ) {
        if (!isPublic || statement.isBlank()) return
        val normalized = statement.replace(Regex("\\s+"), " ").trim()

        // Constructor: MyClass(...)
        val ctorMatch = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)""").find(normalized)
        if (ctorMatch != null && ctorMatch.groupValues[1] == className) {
            val params = ctorMatch.groupValues[2].trim()
            val (line, col) = offsetToLineCol(lineStarts, startOffset)
            out += CodeSymbol(
                name = className,
                kind = SymbolKind.METHOD,
                signature = "$className($params)",
                detail = "constructor",
                owner = className,
                filePath = filePath,
                line = line,
                col = col
            )
            return
        }

        // Method: ReturnType name(params)
        val methodMatch = Regex(
            """^(?:(?:virtual|static|inline|explicit|constexpr|const|unsigned|signed|long|short)\s+)*([A-Za-z_][A-Za-z0-9_:<>*&\s]*?)\s+([A-Za-z_~][A-Za-z0-9_]*)\s*\(([^)]*)\)"""
        ).find(normalized)
        if (methodMatch != null) {
            val ret = methodMatch.groupValues[1].trim()
            val name = methodMatch.groupValues[2].trim()
            val params = methodMatch.groupValues[3].trim()
            if (name !in CONTROL_KEYWORDS && !name.startsWith("~") && ret !in CONTROL_KEYWORDS) {
                val offset = startOffset + statement.indexOf(name).coerceAtLeast(0)
                val (line, col) = offsetToLineCol(lineStarts, offset)
                out += CodeSymbol(
                    name = name,
                    kind = SymbolKind.METHOD,
                    signature = "$name($params)",
                    detail = ret,
                    owner = className,
                    filePath = filePath,
                    line = line,
                    col = col
                )
            }
            return
        }

        // Field: Type fieldName;
        val fieldMatch = FIELD_REGEX.find(normalized)
        if (fieldMatch != null) {
            val type = fieldMatch.groupValues[1].trim()
            val name = fieldMatch.groupValues[2].trim()
            if (name !in CONTROL_KEYWORDS && type !in CONTROL_KEYWORDS) {
                val offset = startOffset + statement.indexOf(name).coerceAtLeast(0)
                val (line, col) = offsetToLineCol(lineStarts, offset)
                out += CodeSymbol(
                    name = name,
                    kind = SymbolKind.FIELD,
                    signature = name,
                    detail = type,
                    owner = className,
                    filePath = filePath,
                    line = line,
                    col = col
                )
            }
        }
    }

    fun inferVariableTypes(text: String, knownTypes: Set<String>): Map<String, String> {
        val cleaned = stripCommentsAndStrings(text)
        val map = LinkedHashMap<String, String>()
        for (m in VAR_DECL_REGEX.findAll(cleaned)) {
            val typeName = m.groupValues[1].substringAfterLast("::").substringBefore('<').trim()
            val varName = m.groupValues[2].trim()
            if (typeName in knownTypes && varName !in CONTROL_KEYWORDS) {
                map[varName] = typeName
            }
        }
        return map
    }

    fun detectCompletionContext(
        text: String,
        cursorLine: Int,
        cursorCol: Int,
        index: ProjectIndex
    ): CompletionContext? {
        val lines = text.lines()
        val lineText = lines.getOrNull(cursorLine) ?: return null
        val beforeCursor = lineText.substring(0, cursorCol.coerceIn(0, lineText.length))

        // #include "..."
        Regex("""#\s*include\s*"([^"]*)$""").find(beforeCursor)?.let { m ->
            val prefix = m.groupValues[1]
            return CompletionContext.LocalInclude(prefix, prefix.length)
        }

        // #include <...>
        Regex("""#\s*include\s*<([^>]*)$""").find(beforeCursor)?.let { m ->
            val prefix = m.groupValues[1]
            return CompletionContext.SystemInclude(prefix, prefix.length)
        }

        // #... preprocessor directive (e.g. #, #i, #inc, #def)
        Regex("""^\s*#([A-Za-z_]*)$""").find(beforeCursor)?.let { m ->
            val prefix = m.groupValues[1]
            return CompletionContext.PreprocessorDirective(prefix, prefix.length)
        }

        // ClassName::member
        Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*::\s*([A-Za-z0-9_]*)$""").find(beforeCursor)?.let { m ->
            val typeName = m.groupValues[1]
            val prefix = m.groupValues[2]
            if (typeName in index.classesByName) {
                return CompletionContext.MemberAccess(typeName, prefix, prefix.length)
            }
        }

        // obj.member or ptr->member
        Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*(?:\.|->)\s*([A-Za-z0-9_]*)$""").find(beforeCursor)?.let { m ->
            val ident = m.groupValues[1]
            val prefix = m.groupValues[2]
            val vars = inferVariableTypes(text, index.classesByName.keys)
            val resolvedType = vars[ident] ?: if (ident in index.classesByName) ident else null
            if (resolvedType != null) {
                return CompletionContext.MemberAccess(resolvedType, prefix, prefix.length)
            }
        }

        return null
    }

    fun buildSuggestions(
        ctx: CompletionContext?,
        index: ProjectIndex,
        installedLibs: Map<String, InstalledLibrary>
    ): List<IntelliSenseSuggestion> {
        if (ctx == null) return emptyList()
        return when (ctx) {
            is CompletionContext.MemberAccess -> {
                val members = index.classesByName[ctx.typeName].orEmpty()
                members
                    .filter { ctx.prefix.isEmpty() || it.name.startsWith(ctx.prefix, ignoreCase = true) }
                    .distinctBy { it.signature }
                    .take(30)
                    .map { sym ->
                        val insert = if (sym.kind == SymbolKind.METHOD) "${sym.name}()" else sym.name
                        IntelliSenseSuggestion(
                            label = sym.signature,
                            insertText = insert,
                            detail = sym.detail,
                            kind = sym.kind,
                            replaceLen = ctx.replaceLen
                        )
                    }
            }
            is CompletionContext.LocalInclude -> {
                index.headerFiles
                    .filter { ctx.prefix.isEmpty() || it.contains(ctx.prefix, ignoreCase = true) }
                    .take(30)
                    .map { header ->
                        IntelliSenseSuggestion(
                            label = header,
                            insertText = "$header\"",
                            detail = "project header",
                            kind = SymbolKind.HEADER,
                            replaceLen = ctx.replaceLen
                        )
                    }
            }
            is CompletionContext.SystemInclude -> {
                installedLibs.values
                    .flatMap { lib -> lib.headers.map { it to lib.name } }
                    .filter { (h, _) -> ctx.prefix.isEmpty() || h.contains(ctx.prefix, ignoreCase = true) }
                    .distinctBy { it.first }
                    .take(30)
                    .map { (header, libName) ->
                        IntelliSenseSuggestion(
                            label = header,
                            insertText = "$header>",
                            detail = libName,
                            kind = SymbolKind.HEADER,
                            replaceLen = ctx.replaceLen
                        )
                    }
            }
            is CompletionContext.PreprocessorDirective -> {
                val directives = listOf(
                    Triple("#include \"...\"", "include \"\"", "local header"),
                    Triple("#include <...>", "include <>", "library header"),
                    Triple("#define", "define ", "macro / constant"),
                    Triple("#pragma once", "pragma once", "header guard"),
                    Triple("#ifdef", "ifdef ", "if defined"),
                    Triple("#ifndef", "ifndef ", "if not defined"),
                    Triple("#if", "if ", "conditional"),
                    Triple("#elif", "elif ", "else if"),
                    Triple("#else", "else", "else branch"),
                    Triple("#endif", "endif", "end #if"),
                    Triple("#undef", "undef ", "undefine macro")
                )
                directives
                    .filter { (label, _, _) ->
                        ctx.prefix.isEmpty() ||
                            label.removePrefix("#").startsWith(ctx.prefix, ignoreCase = true)
                    }
                    .map { (label, insert, detail) ->
                        IntelliSenseSuggestion(
                            label = label,
                            insertText = insert,
                            detail = detail,
                            kind = SymbolKind.DIRECTIVE,
                            replaceLen = ctx.replaceLen
                        )
                    }
            }
        }
    }

    // ---- Fast static C/C++/Arduino diagnostics ----

    fun analyzeDiagnostics(
        projectDir: File?,
        openPath: String?,
        openText: String,
        index: ProjectIndex
    ): List<CodeDiagnostic> {
        if (openPath == null) return emptyList()
        val diagnostics = ArrayList<CodeDiagnostic>()
        val file = File(openPath)

        // 1. Structural bracket, quote, and block-comment checks
        diagnostics += checkLexicalAndBraces(openPath, openText)

        // 1b. Missing semicolon checks
        diagnostics += checkMissingSemicolons(openPath, openText)

        // 2. Check #include "..." paths resolve inside project
        if (projectDir != null) {
            openText.lineSequence().forEachIndexed { lineIdx, line ->
                val m = INCLUDE_LOCAL_REGEX.find(line) ?: return@forEachIndexed
                val target = m.groupValues[1].trim()
                val exists = File(file.parentFile, target).isFile ||
                    File(projectDir, target).isFile ||
                    File(projectDir, "src/$target").isFile
                if (!exists) {
                    diagnostics += CodeDiagnostic(
                        path = openPath,
                        line = lineIdx,
                        col = line.indexOf(target).coerceAtLeast(0),
                        message = "Header \"$target\" not found in project",
                        severity = DiagnosticSeverity.ERROR
                    )
                }
            }
        }

        // 3. Warn if using a project class defined in another .h without including that header
        val includedLocalHeaders = INCLUDE_LOCAL_REGEX.findAll(openText)
            .map { File(it.groupValues[1]).name.lowercase() }
            .toSet()
        val cleaned = stripCommentsAndStrings(openText)
        val lineStarts = computeLineStarts(openText)

        val externalClasses = index.symbols.filter {
            (it.kind == SymbolKind.CLASS || it.kind == SymbolKind.STRUCT) &&
                it.filePath != openPath &&
                File(it.filePath).extension.lowercase() in HEADER_EXTENSIONS
        }
        for (cls in externalClasses) {
            val headerName = File(cls.filePath).name
            if (headerName.lowercase() in includedLocalHeaders) continue
            val usage = Regex("""\b${Regex.escape(cls.name)}\b""").find(cleaned) ?: continue
            val (line, col) = offsetToLineCol(lineStarts, usage.range.first)
            diagnostics += CodeDiagnostic(
                path = openPath,
                line = line,
                col = col,
                message = "'${cls.name}' is defined in $headerName (missing #include \"$headerName\"?)",
                severity = DiagnosticSeverity.WARNING
            )
        }

        // 4. Main .ino sketch requires setup() and loop()
        if (projectDir != null && ProjectManager.isMainSketch(projectDir, file)) {
            if (!Regex("""\bvoid\s+setup\s*\(""").containsMatchIn(cleaned)) {
                diagnostics += CodeDiagnostic(
                    path = openPath,
                    line = 0,
                    col = 0,
                    message = "Main sketch is missing void setup()",
                    severity = DiagnosticSeverity.WARNING
                )
            }
            if (!Regex("""\bvoid\s+loop\s*\(""").containsMatchIn(cleaned)) {
                diagnostics += CodeDiagnostic(
                    path = openPath,
                    line = 0,
                    col = 0,
                    message = "Main sketch is missing void loop()",
                    severity = DiagnosticSeverity.WARNING
                )
            }
        }

        return diagnostics.sortedWith(compareBy({ it.line }, { it.col }))
    }

    private fun checkLexicalAndBraces(path: String, text: String): List<CodeDiagnostic> {
        val out = ArrayList<CodeDiagnostic>()
        data class Brace(val ch: Char, val line: Int, val col: Int)
        val stack = ArrayDeque<Brace>()

        var line = 0
        var col = 0
        var i = 0
        val n = text.length
        var inLineComment = false
        var inBlockComment = false
        var blockCommentStartLine = 0
        var blockCommentStartCol = 0
        var inString = false
        var stringStartLine = 0
        var stringStartCol = 0
        var inChar = false

        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else '\u0000'

            when {
                inLineComment -> {
                    if (c == '\n') inLineComment = false
                }
                inBlockComment -> {
                    if (c == '*' && next == '/') {
                        inBlockComment = false
                        i++
                        col++
                    }
                }
                inString -> {
                    if (c == '\\' && next != '\u0000') {
                        i++
                        col++
                    } else if (c == '"') {
                        inString = false
                    } else if (c == '\n') {
                        out += CodeDiagnostic(path, stringStartLine, stringStartCol, "Unclosed string literal", DiagnosticSeverity.ERROR)
                        inString = false
                    }
                }
                inChar -> {
                    if (c == '\\' && next != '\u0000') {
                        i++
                        col++
                    } else if (c == '\'' || c == '\n') {
                        inChar = false
                    }
                }
                c == '/' && next == '/' -> {
                    inLineComment = true
                    i++
                    col++
                }
                c == '/' && next == '*' -> {
                    inBlockComment = true
                    blockCommentStartLine = line
                    blockCommentStartCol = col
                    i++
                    col++
                }
                c == '"' -> {
                    inString = true
                    stringStartLine = line
                    stringStartCol = col
                }
                c == '\'' -> {
                    inChar = true
                }
                c == '{' || c == '(' || c == '[' -> {
                    stack.addLast(Brace(c, line, col))
                }
                c == '}' || c == ')' || c == ']' -> {
                    val expected = when (c) {
                        '}' -> '{'
                        ')' -> '('
                        else -> '['
                    }
                    val top = stack.lastOrNull()
                    if (top == null) {
                        out += CodeDiagnostic(path, line, col, "Unexpected '$c'", DiagnosticSeverity.ERROR)
                    } else if (top.ch != expected) {
                        out += CodeDiagnostic(
                            path,
                            line,
                            col,
                            "Mismatched '$c' (expected closing for '${top.ch}' at line ${top.line + 1})",
                            DiagnosticSeverity.ERROR
                        )
                        stack.removeLast()
                    } else {
                        stack.removeLast()
                    }
                }
            }

            if (c == '\n') {
                line++
                col = 0
            } else {
                col++
            }
            i++
        }

        if (inBlockComment) {
            out += CodeDiagnostic(path, blockCommentStartLine, blockCommentStartCol, "Unclosed /* block comment", DiagnosticSeverity.ERROR)
        }
        if (inString) {
            out += CodeDiagnostic(path, stringStartLine, stringStartCol, "Unclosed string literal", DiagnosticSeverity.ERROR)
        }
        while (stack.isNotEmpty()) {
            val unclosed = stack.removeLast()
            out += CodeDiagnostic(path, unclosed.line, unclosed.col, "Unclosed '${unclosed.ch}'", DiagnosticSeverity.ERROR)
        }

        return out
    }

    private fun checkMissingSemicolons(path: String, text: String): List<CodeDiagnostic> {
        val out = ArrayList<CodeDiagnostic>()
        val cleaned = stripCommentsAndStrings(text)
        val rawLines = text.lines()
        val cleanLines = cleaned.lines()

        val lineParenEnd = IntArray(cleanLines.size)
        val lineBraceEnd = IntArray(cleanLines.size)
        var pDepth = 0
        var bDepth = 0
        for (i in cleanLines.indices) {
            for (ch in cleanLines[i]) {
                when (ch) {
                    '(', '[' -> pDepth++
                    ')', ']' -> if (pDepth > 0) pDepth--
                    '{' -> bDepth++
                    '}' -> if (bDepth > 0) bDepth--
                }
            }
            lineParenEnd[i] = pDepth
            lineBraceEnd[i] = bDepth
        }

        val controlLineRegex = Regex(
            """^(if|else\s+if|else|for|while|switch|do|try|catch|class|struct|enum|namespace|template)\b.*"""
        )
        val accessLabelRegex = Regex("""^(public|private|protected)\s*:$""")
        val caseLabelRegex = Regex("""^(case\b.*|default)\s*:$""")
        val trailingOpRegex = Regex("""([+\-*/%=<>!&|^~?:,.]|->|::|<<|>>|\\)$""")
        val leadingContRegex = Regex("""^(\{|->|\.|&&|\|\||<<|>>|\?|:|\)|\]|,)""")

        for (i in cleanLines.indices) {
            val rawTrim = rawLines[i].trim()
            if (rawTrim.startsWith("#") || rawTrim.endsWith("\\")) continue

            val line = cleanLines[i].trim()
            if (line.isEmpty() || lineParenEnd[i] > 0) continue

            val lastChar = line.last()
            if (lastChar in charArrayOf(';', '{', '}', ':', ',', '(', '[', '\\')) continue
            if (controlLineRegex.matches(line) || accessLabelRegex.matches(line) || caseLabelRegex.matches(line)) continue
            if (!line.endsWith("++") && !line.endsWith("--") && trailingOpRegex.containsMatchIn(line)) continue

            var nextLine = ""
            var j = i + 1
            while (j < cleanLines.size) {
                val cand = cleanLines[j].trim()
                if (cand.isNotEmpty()) {
                    nextLine = cand
                    break
                }
                j++
            }
            if (leadingContRegex.containsMatchIn(nextLine)) continue
            if (lastChar == ')' && nextLine.startsWith("{")) continue
            if (nextLine.startsWith("}") && lineBraceEnd[i] > 0 && !line.contains(' ') && !line.contains('(')) continue

            val col = rawLines[i].trimEnd().length.coerceAtLeast(0)
            out += CodeDiagnostic(
                path = path,
                line = i,
                col = col,
                message = "Missing ';'",
                severity = DiagnosticSeverity.ERROR
            )
        }
        return out
    }

    // ---- Helpers ----

    private fun stripCommentsAndStrings(text: String): String {
        val chars = text.toCharArray()
        var i = 0
        val n = chars.size
        var inLine = false
        var inBlock = false
        var inStr = false
        var inChar = false

        while (i < n) {
            val c = chars[i]
            val next = if (i + 1 < n) chars[i + 1] else '\u0000'
            when {
                inLine -> {
                    if (c == '\n') inLine = false else chars[i] = ' '
                }
                inBlock -> {
                    if (c == '*' && next == '/') {
                        chars[i] = ' '
                        chars[i + 1] = ' '
                        inBlock = false
                        i++
                    } else if (c != '\n') {
                        chars[i] = ' '
                    }
                }
                inStr -> {
                    if (c == '\\' && i + 1 < n) {
                        if (chars[i] != '\n') chars[i] = ' '
                        if (chars[i + 1] != '\n') chars[i + 1] = ' '
                        i++
                    } else if (c == '"') {
                        chars[i] = ' '
                        inStr = false
                    } else if (c != '\n') {
                        chars[i] = ' '
                    }
                }
                inChar -> {
                    if (c == '\\' && i + 1 < n) {
                        if (chars[i] != '\n') chars[i] = ' '
                        if (chars[i + 1] != '\n') chars[i + 1] = ' '
                        i++
                    } else if (c == '\'') {
                        chars[i] = ' '
                        inChar = false
                    } else if (c != '\n') {
                        chars[i] = ' '
                    }
                }
                c == '/' && next == '/' -> {
                    chars[i] = ' '
                    chars[i + 1] = ' '
                    inLine = true
                    i++
                }
                c == '/' && next == '*' -> {
                    chars[i] = ' '
                    chars[i + 1] = ' '
                    inBlock = true
                    i++
                }
                c == '"' -> {
                    chars[i] = ' '
                    inStr = true
                }
                c == '\'' -> {
                    chars[i] = ' '
                    inChar = true
                }
            }
            i++
        }
        return String(chars)
    }

    private fun findMatchingBrace(text: String, openBraceIndex: Int): Int {
        var depth = 0
        for (i in openBraceIndex until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun computeLineStarts(text: String): List<Int> {
        val starts = ArrayList<Int>()
        starts += 0
        for (i in text.indices) {
            if (text[i] == '\n') starts += i + 1
        }
        return starts
    }

    private fun offsetToLineCol(lineStarts: List<Int>, offset: Int): Pair<Int, Int> {
        var lo = 0
        var hi = lineStarts.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lineStarts[mid] <= offset) lo = mid + 1 else hi = mid - 1
        }
        val line = hi.coerceAtLeast(0)
        val col = (offset - lineStarts[line]).coerceAtLeast(0)
        return line to col
    }
}
