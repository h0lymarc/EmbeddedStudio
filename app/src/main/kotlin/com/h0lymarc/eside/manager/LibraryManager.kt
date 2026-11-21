package com.h0lymarc.eside.manager

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class ArduinoLibrary(
    val name: String,
    val version: String,
    val author: String,
    val sentence: String,
    val category: String,
    val architectures: List<String>,
    val url: String,
    val archiveFileName: String,
    val size: Long,
    val checksum: String,
    val dependencies: List<String>
) {
    fun supports(board: Board): Boolean {
        if (architectures.isEmpty() || "*" in architectures) return true
        val arch = when (board) {
            Board.ARDUINO -> "avr"
            Board.ESP32 -> "esp32"
        }
        return architectures.any { it.equals(arch, ignoreCase = true) }
    }
}

data class InstalledLibrary(
    val name: String,
    val version: String,
    val folder: File,
    val headers: List<String>
)

object LibraryManager {

    private const val INDEX_URL = "https://downloads.arduino.cc/libraries/library_index.json.gz"
    private const val META_FILE = ".eside_meta.json"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 45_000

    fun baseDir(context: Context): File =
        File(context.filesDir, "arduino").apply { mkdirs() }

    /** Pass this folder to `arduino-cli compile --libraries <path>` when wiring ArduinoCompiler. */
    fun librariesDir(context: Context): File =
        File(baseDir(context), "libraries").apply { mkdirs() }

    private fun cacheFile(context: Context): File =
        File(baseDir(context), "library_index_cache.json")

    private fun sanitizeFolderName(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_")

    // ---- Index loading & streaming parse ----

    suspend fun loadCachedIndex(context: Context): List<ArduinoLibrary> = withContext(Dispatchers.IO) {
        val file = cacheFile(context)
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i -> arr.getJSONObject(i).toLibrary() }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchRemoteIndex(context: Context): Result<List<ArduinoLibrary>> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(INDEX_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "EmbeddedStudio/1.0 (Android)")
            }
            try {
                val code = conn.responseCode
                check(code in 200..299) { "Server returned HTTP $code" }

                val latestByName = HashMap<String, ArduinoLibrary>(4096)
                GZIPInputStream(BufferedInputStream(conn.inputStream)).use { gz ->
                    JsonReader(BufferedReader(InputStreamReader(gz, Charsets.UTF_8))).use { reader ->
                        parseIndexStream(reader, latestByName)
                    }
                }

                val sorted = latestByName.values.sortedBy { it.name.lowercase() }
                writeCache(cacheFile(context), sorted)
                sorted
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun parseIndexStream(reader: JsonReader, out: MutableMap<String, ArduinoLibrary>) {
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "libraries") {
                reader.beginArray()
                while (reader.hasNext()) {
                    val lib = readLibraryEntry(reader) ?: continue
                    val prev = out[lib.name]
                    if (prev == null || compareVersions(lib.version, prev.version) > 0) {
                        out[lib.name] = lib
                    }
                }
                reader.endArray()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun readLibraryEntry(reader: JsonReader): ArduinoLibrary? {
        var name = ""
        var version = ""
        var author = ""
        var sentence = ""
        var category = "Uncategorized"
        var architectures = emptyList<String>()
        var url = ""
        var archiveFileName = ""
        var size = 0L
        var checksum = ""
        var dependencies = emptyList<String>()

        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                continue
            }
            when (key) {
                "name" -> name = reader.nextString()
                "version" -> version = reader.nextString()
                "author" -> author = reader.nextString()
                "sentence" -> sentence = reader.nextString()
                "category" -> category = reader.nextString()
                "architectures" -> architectures = readStringArray(reader)
                "url" -> url = reader.nextString()
                "archiveFileName" -> archiveFileName = reader.nextString()
                "size" -> size = reader.nextLong()
                "checksum" -> checksum = reader.nextString()
                "dependencies" -> dependencies = readDependencies(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (name.isBlank() || url.isBlank()) return null
        return ArduinoLibrary(
            name = name,
            version = version,
            author = author,
            sentence = sentence,
            category = category,
            architectures = architectures,
            url = url,
            archiveFileName = archiveFileName,
            size = size,
            checksum = checksum,
            dependencies = dependencies
        )
    }

    private fun readStringArray(reader: JsonReader): List<String> {
        val list = ArrayList<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.STRING) list += reader.nextString()
            else reader.skipValue()
        }
        reader.endArray()
        return list
    }

    private fun readDependencies(reader: JsonReader): List<String> {
        val list = ArrayList<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    var depName = ""
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "name" && reader.peek() == JsonToken.STRING) {
                            depName = reader.nextString()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (depName.isNotBlank()) list += depName
                }
                JsonToken.STRING -> list += reader.nextString()
                else -> reader.skipValue()
            }
        }
        reader.endArray()
        return list
    }

    private fun writeCache(file: File, libraries: List<ArduinoLibrary>) {
        val arr = JSONArray()
        for (lib in libraries) {
            arr.put(
                JSONObject().apply {
                    put("name", lib.name)
                    put("version", lib.version)
                    put("author", lib.author)
                    put("sentence", lib.sentence)
                    put("category", lib.category)
                    put("architectures", JSONArray(lib.architectures))
                    put("url", lib.url)
                    put("archiveFileName", lib.archiveFileName)
                    put("size", lib.size)
                    put("checksum", lib.checksum)
                    put("dependencies", JSONArray(lib.dependencies))
                }
            )
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    private fun JSONObject.toLibrary(): ArduinoLibrary {
        val archJson = optJSONArray("architectures") ?: JSONArray()
        val depsJson = optJSONArray("dependencies") ?: JSONArray()
        return ArduinoLibrary(
            name = getString("name"),
            version = optString("version"),
            author = optString("author"),
            sentence = optString("sentence"),
            category = optString("category", "Uncategorized"),
            architectures = List(archJson.length()) { archJson.getString(it) },
            url = getString("url"),
            archiveFileName = optString("archiveFileName"),
            size = optLong("size"),
            checksum = optString("checksum"),
            dependencies = List(depsJson.length()) { depsJson.getString(it) }
        )
    }

    // ---- Installed libraries ----

    suspend fun listInstalled(context: Context): Map<String, InstalledLibrary> = withContext(Dispatchers.IO) {
        val root = librariesDir(context)
        val out = LinkedHashMap<String, InstalledLibrary>()
        root.listFiles { f -> f.isDirectory }.orEmpty().forEach { dir ->
            val metaFile = File(dir, META_FILE)
            val info = if (metaFile.exists()) {
                runCatching {
                    val obj = JSONObject(metaFile.readText())
                    val headersJson = obj.optJSONArray("headers") ?: JSONArray()
                    InstalledLibrary(
                        name = obj.getString("name"),
                        version = obj.optString("version"),
                        folder = dir,
                        headers = List(headersJson.length()) { headersJson.getString(it) }
                    )
                }.getOrNull()
            } else {
                null
            }
            val resolved = info ?: readFromLibraryProperties(dir)
            out[resolved.name] = resolved
        }
        out
    }

    private fun readFromLibraryProperties(dir: File): InstalledLibrary {
        val propsFile = File(dir, "library.properties")
        val props = Properties()
        if (propsFile.exists()) {
            runCatching { propsFile.inputStream().use { props.load(it) } }
        }
        val name = props.getProperty("name")?.trim()?.takeIf { it.isNotEmpty() } ?: dir.name
        val version = props.getProperty("version")?.trim().orEmpty()
        val headers = detectHeaders(dir, name, props)
        return InstalledLibrary(name = name, version = version, folder = dir, headers = headers)
    }

    // ---- Download, verify, extract & dependencies ----

    suspend fun install(
        context: Context,
        library: ArduinoLibrary,
        catalog: List<ArduinoLibrary>
    ): Result<InstalledLibrary> = withContext(Dispatchers.IO) {
        runCatching {
            val catalogByName = catalog.associateBy { it.name }
            val installed = listInstalled(context).toMutableMap()
            installRecursive(context, library, catalogByName, installed, HashSet())
        }
    }

    private fun installRecursive(
        context: Context,
        library: ArduinoLibrary,
        catalogByName: Map<String, ArduinoLibrary>,
        installed: MutableMap<String, InstalledLibrary>,
        visiting: MutableSet<String>
    ): InstalledLibrary {
        if (!visiting.add(library.name)) {
            return installed[library.name]
                ?: InstalledLibrary(library.name, library.version, File(librariesDir(context), sanitizeFolderName(library.name)), emptyList())
        }

        // Install missing dependencies first
        for (depName in library.dependencies) {
            if (depName !in installed) {
                catalogByName[depName]?.let { depLib ->
                    installed[depName] = installRecursive(context, depLib, catalogByName, installed, visiting)
                }
            }
        }

        val zipFile = File.createTempFile("eside_lib_", ".zip", context.cacheDir)
        val stageDir = File(context.cacheDir, "lib_stage_${System.nanoTime()}")
        val targetDir = File(librariesDir(context), sanitizeFolderName(library.name))

        try {
            downloadWithSha256(library.url, zipFile, library.checksum)
            unzipStrippingRoot(zipFile, stageDir)

            val props = Properties().apply {
                val pf = File(stageDir, "library.properties")
                if (pf.exists()) runCatching { pf.inputStream().use { load(it) } }
            }
            val headers = detectHeaders(stageDir, library.name, props)

            File(stageDir, META_FILE).writeText(
                JSONObject().apply {
                    put("name", library.name)
                    put("version", library.version)
                    put("headers", JSONArray(headers))
                }.toString()
            )

            if (targetDir.exists()) targetDir.deleteRecursively()
            if (!stageDir.renameTo(targetDir)) {
                stageDir.copyRecursively(targetDir, overwrite = true)
            }

            val result = InstalledLibrary(
                name = library.name,
                version = library.version,
                folder = targetDir,
                headers = headers
            )
            installed[library.name] = result
            return result
        } finally {
            zipFile.delete()
            if (stageDir.exists()) stageDir.deleteRecursively()
        }
    }

    private fun downloadWithSha256(url: String, dest: File, expectedChecksum: String) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "EmbeddedStudio/1.0 (Android)")
        }
        try {
            val code = conn.responseCode
            check(code in 200..299) { "Download failed (HTTP $code)" }

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16 * 1024)
            BufferedInputStream(conn.inputStream).use { input ->
                dest.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }

            if (expectedChecksum.startsWith("SHA-256:", ignoreCase = true)) {
                val expected = expectedChecksum.substringAfter(':').trim().lowercase()
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == expected) { "Checksum mismatch (corrupted download)" }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun unzipStrippingRoot(zipFile: File, destDir: File) {
        destDir.mkdirs()

        // Detect a single shared root folder inside the zip (e.g., "Servo-1.2.1/")
        val entryNames = ArrayList<String>()
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entryNames += entry.name.trimStart('/')
                zis.closeEntry()
            }
        }
        val firstSegments = entryNames.mapNotNull { it.substringBefore('/', "").takeIf(String::isNotEmpty) }.toSet()
        val commonRoot = if (firstSegments.size == 1 && entryNames.all { '/' in it }) {
            firstSegments.first() + "/"
        } else {
            ""
        }

        val destCanonical = destDir.canonicalPath + File.separator
        val buffer = ByteArray(16 * 1024)

        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val rawName = entry.name.trimStart('/')
                val relPath = if (commonRoot.isNotEmpty() && rawName.startsWith(commonRoot)) {
                    rawName.removePrefix(commonRoot)
                } else {
                    rawName
                }
                if (relPath.isEmpty()) {
                    zis.closeEntry()
                    continue
                }

                val outFile = File(destDir, relPath)
                check(outFile.canonicalPath.startsWith(destCanonical)) { "Unsafe zip entry: $relPath" }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        while (true) {
                            val read = zis.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private fun detectHeaders(dir: File, libName: String, props: Properties): List<String> {
        val explicit = props.getProperty("includes")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.endsWith(".h", ignoreCase = true) || it.endsWith(".hpp", ignoreCase = true) }
            .orEmpty()
        if (explicit.isNotEmpty()) return explicit

        val srcDir = File(dir, "src").takeIf { it.isDirectory } ?: dir
        val headers = srcDir.listFiles { f ->
            f.isFile && (f.extension.equals("h", true) || f.extension.equals("hpp", true))
        }.orEmpty().map { it.name }.sorted()

        val preferred = headers.firstOrNull {
            it.substringBeforeLast('.').equals(libName.replace(" ", "_"), ignoreCase = true) ||
                it.substringBeforeLast('.').equals(libName.replace(" ", ""), ignoreCase = true)
        }
        return if (preferred != null) {
            listOf(preferred) + headers.filter { it != preferred }
        } else {
            headers
        }
    }

    suspend fun uninstall(context: Context, libraryName: String): Boolean = withContext(Dispatchers.IO) {
        val root = librariesDir(context)
        val rootCanonical = root.canonicalPath + File.separator
        val installed = listInstalled(context)[libraryName] ?: return@withContext false
        if (!installed.folder.canonicalPath.startsWith(rootCanonical)) return@withContext false
        installed.folder.deleteRecursively()
    }

    /** Copies the library sources into `<projectDir>/src/<LibName>/` so it is bundled with the project. */
    suspend fun copyToProjectSrc(
        context: Context,
        projectDir: File,
        libraryName: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val installed = listInstalled(context)[libraryName]
                ?: error("$libraryName is not installed")
            val libFolderName = sanitizeFolderName(libraryName)
            val dest = File(projectDir, "src/$libFolderName")
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()

            val srcRoot = File(installed.folder, "src").takeIf { it.isDirectory } ?: installed.folder
            srcRoot.walkTopDown().filter { it.isFile }.forEach { file ->
                val ext = file.extension.lowercase()
                if (ext in setOf("h", "hpp", "c", "cpp", "cc", "s")) {
                    val rel = file.relativeTo(srcRoot)
                    val out = File(dest, rel.path)
                    out.parentFile?.mkdirs()
                    file.copyTo(out, overwrite = true)
                }
            }
            installed.headers.map { "src/$libFolderName/$it" }
        }
    }

    // ---- Autocomplete keywords from installed libraries' keywords.txt ----

    suspend fun installedKeywords(context: Context): List<String> = withContext(Dispatchers.IO) {
        val tokens = LinkedHashSet<String>()
        librariesDir(context).listFiles { f -> f.isDirectory }.orEmpty().forEach { dir ->
            val kw = File(dir, "keywords.txt")
            if (!kw.exists()) return@forEach
            runCatching {
                kw.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                    val word = line.split(Regex("\\s+")).firstOrNull()?.trim().orEmpty()
                    if (word.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                        tokens += word
                    }
                }
            }
        }
        tokens.toList()
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-', '+')
        val pb = b.split('.', '-', '+')
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val sa = pa.getOrElse(i) { "0" }
            val sb = pb.getOrElse(i) { "0" }
            val na = sa.toLongOrNull()
            val nb = sb.toLongOrNull()
            val cmp = when {
                na != null && nb != null -> na.compareTo(nb)
                else -> sa.compareTo(sb)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}