package com.h0lymarc.eside.manager

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class PlatformBoard(
    val id: String,
    val name: String,
    val fqbn: String,
    val architecture: String
)

data class BoardPlatform(
    val packageName: String,
    val maintainer: String,
    val name: String,
    val architecture: String,
    val version: String,
    val category: String,
    val url: String,
    val archiveFileName: String,
    val checksum: String,
    val size: Long,
    val boardNames: List<String>
) {
    val id: String get() = "$packageName:$architecture"

    val isAvrOrEsp32: Boolean
        get() = architecture.equals("avr", ignoreCase = true) ||
            architecture.contains("esp32", ignoreCase = true)
}

data class InstalledPlatform(
    val packageName: String,
    val architecture: String,
    val name: String,
    val version: String,
    val installDir: File,
    val boards: List<PlatformBoard>,
    val builtInLibs: List<String>
) {
    val id: String get() = "$packageName:$architecture"
}

data class SelectedBoard(
    val packageName: String,
    val architecture: String,
    val boardId: String,
    val name: String,
    val fqbn: String
) {
    val family: Board
        get() = if (architecture.contains("esp32", ignoreCase = true)) Board.ESP32 else Board.ARDUINO
}

data class BoardMenuChoice(
    val id: String,
    val label: String
)

data class BoardMenuGroup(
    val id: String,
    val label: String,
    val options: List<BoardMenuChoice>,
    val defaultOptionId: String
)

data class BoardBuildConfig(
    val fqbn: String,
    val effectiveFqbn: String,
    val packageName: String,
    val architecture: String,
    val boardId: String,
    val boardName: String,
    val mcu: String,
    val fCpu: String,
    val boardDefine: String,
    val coreName: String,
    val variantName: String,
    val uploadSpeed: String,
    val codeDebugLevel: String,
    val flashMode: String,
    val flashFreq: String,
    val flashSize: String,
    val partitionScheme: String,
    val psramMode: String,
    val optimizeLevel: String,
    val appliedOptions: Map<String, String>,
    val extraFlags: List<String>,
    val uploadMaximumSize: Long,
    val uploadMaximumDataSize: Long,
    val platformName: String = "",
    val platformVersion: String = "",
    val uploadProtocol: String = "",
    val uploadTool: String = "",
    val vidPids: List<String> = emptyList(),
    val boardsTxtPath: String = "",
    val platformDir: File?,
    val coreDir: File?,
    val variantDir: File?,
    val compilerCCmd: String = "",
    val compilerCppCmd: String = "",
    val compilerArCmd: String = "",
    val compilerObjcopyCmd: String = "",
    val compilerSizeCmd: String = "",
    val platformCFlags: List<String> = emptyList(),
    val platformCppFlags: List<String> = emptyList(),
    val platformAsmFlags: List<String> = emptyList(),
    val platformLinkFlags: List<String> = emptyList()
)

object BoardManager {

    private val DEFAULT_INDEX_URLS = listOf(
        "https://downloads.arduino.cc/packages/package_index.json",
        "https://espressif.github.io/arduino-esp32/package_esp32_index.json",
        "https://arduino.esp8266.com/stable/package_esp8266com_index.json",
        "https://github.com/earlephilhower/arduino-pico/releases/download/global/package_rp2040_index.json",
        "https://github.com/stm32duino/BoardManagerFiles/raw/main/package_stmicroelectronics_index.json",
        "https://adafruit.github.io/arduino-board-index/package_adafruit_index.json",
        "https://raw.githubusercontent.com/sparkfun/Arduino_Boards/main/IDE_Board_Manager/package_sparkfun_index.json",
        "https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json"
    )

    fun customIndexUrls(context: Context): List<String> {
        val raw = context.getSharedPreferences("editor", Context.MODE_PRIVATE)
            .getString("custom_board_index_urls", "")
            .orEmpty()
        return raw.lines()
            .map { it.trim() }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            .distinct()
    }

    fun setCustomIndexUrls(context: Context, urls: List<String>) {
        val cleaned = urls.map { it.trim() }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            .distinct()
        context.getSharedPreferences("editor", Context.MODE_PRIVATE)
            .edit()
            .putString("custom_board_index_urls", cleaned.joinToString("\n"))
            .apply()
    }

    fun allIndexUrls(context: Context): List<String> =
        (DEFAULT_INDEX_URLS + customIndexUrls(context)).distinct()

    fun defaultBoard(@Suppress("UNUSED_PARAMETER") family: Board = Board.ARDUINO): SelectedBoard =
        SelectedBoard(
            packageName = "",
            architecture = "",
            boardId = "",
            name = "No board selected",
            fqbn = ""
        )

    fun packagesDir(context: Context): File =
        File(context.filesDir, "arduino/packages").apply { mkdirs() }

    private fun cacheFile(context: Context): File =
        File(context.filesDir, "arduino/board_index_cache.json").apply { parentFile?.mkdirs() }

    // ---- Catalog (remote fetch + local cache) ----

    suspend fun loadCachedIndex(context: Context): List<BoardPlatform> = withContext(Dispatchers.IO) {
        val file = cacheFile(context)
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val bArr = o.optJSONArray("boards") ?: JSONArray()
                BoardPlatform(
                    packageName = o.getString("pkg"),
                    maintainer = o.optString("maintainer"),
                    name = o.getString("name"),
                    architecture = o.getString("arch"),
                    version = o.getString("ver"),
                    category = o.optString("cat", "Contributed"),
                    url = o.getString("url"),
                    archiveFileName = o.optString("file"),
                    checksum = o.optString("sum"),
                    size = o.optLong("size", 0L),
                    boardNames = List(bArr.length()) { j -> bArr.getString(j) }
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchRemoteIndex(context: Context): Result<List<BoardPlatform>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val urls = allIndexUrls(context)
                val parsedMaps = coroutineScope {
                    urls.map { indexUrl ->
                        async(Dispatchers.IO) {
                            runCatching {
                                val local = LinkedHashMap<String, BoardPlatform>()
                                val conn = openRedirectingConnection(indexUrl, timeoutMs = 25_000)
                                try {
                                    if (conn.responseCode !in 200..299) {
                                        error("HTTP ${conn.responseCode} from $indexUrl")
                                    }
                                    val stream: InputStream =
                                        if (conn.contentEncoding.equals("gzip", ignoreCase = true)) {
                                            GZIPInputStream(BufferedInputStream(conn.inputStream, 32 * 1024))
                                        } else {
                                            BufferedInputStream(conn.inputStream, 32 * 1024)
                                        }
                                    stream.use { parsePackageIndex(it, local) }
                                } finally {
                                    conn.disconnect()
                                }
                                local
                            }
                        }
                    }.awaitAll()
                }

                val best = LinkedHashMap<String, BoardPlatform>()
                var anySucceeded = false
                var lastError: Throwable? = null
                for (res in parsedMaps) {
                    res.onSuccess { local ->
                        anySucceeded = true
                        for ((key, candidate) in local) {
                            val current = best[key]
                            if (current == null || isBetterVersion(candidate.version, current.version)) {
                                best[key] = candidate
                            }
                        }
                    }.onFailure { err ->
                        lastError = err
                    }
                }

                if (!anySucceeded) {
                    throw lastError ?: IllegalStateException("Could not reach board indexes")
                }

                val list = best.values.sortedWith(
                    compareBy<BoardPlatform>(
                        {
                            when {
                                it.packageName == "arduino" && it.architecture == "avr" -> 0
                                it.architecture == "esp32" -> 1
                                it.packageName == "arduino" -> 2
                                it.architecture in setOf("rp2040", "esp8266", "STM32", "stm32") -> 3
                                else -> 4
                            }
                        },
                        { it.name.lowercase() }
                    )
                )
                saveCache(context, list)
                list
            }
        }

    private fun parsePackageIndex(
        stream: InputStream,
        best: MutableMap<String, BoardPlatform>
    ) {
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.isLenient = true
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "packages" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        readPackage(reader, best)
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        }
    }

    private fun readPackage(
        reader: JsonReader,
        best: MutableMap<String, BoardPlatform>
    ) {
        var pkgName = ""
        var maintainer = ""
        val rawPlatforms = mutableListOf<BoardPlatform>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> pkgName = reader.nextStringOrEmpty()
                "maintainer" -> maintainer = reader.nextStringOrEmpty()
                "platforms" -> if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        readPlatform(reader)?.let { rawPlatforms += it }
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (pkgName.isBlank()) return
        for (p in rawPlatforms) {
            val candidate = p.copy(
                packageName = pkgName,
                maintainer = maintainer
            )
            if (candidate.url.isBlank() || candidate.architecture.isBlank()) continue
            val key = candidate.id
            val current = best[key]
            if (current == null || isBetterVersion(candidate.version, current.version)) {
                best[key] = candidate
            }
        }
    }

    private fun readPlatform(reader: JsonReader): BoardPlatform? {
        var name = ""
        var arch = ""
        var version = ""
        var category = "Contributed"
        var url = ""
        var archiveFileName = ""
        var checksum = ""
        var size = 0L
        val boardNames = mutableListOf<String>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = reader.nextStringOrEmpty()
                "architecture" -> arch = reader.nextStringOrEmpty()
                "version" -> version = reader.nextStringOrEmpty()
                "category" -> category = reader.nextStringOrEmpty()
                "url" -> url = reader.nextStringOrEmpty()
                "archiveFileName" -> archiveFileName = reader.nextStringOrEmpty()
                "checksum" -> checksum = reader.nextStringOrEmpty()
                "size" -> size = reader.nextLongSafe()
                "boards" -> if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var bName = ""
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() == "name") bName = reader.nextStringOrEmpty()
                            else reader.skipValue()
                        }
                        reader.endObject()
                        if (bName.isNotBlank()) boardNames += bName
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (name.isBlank() || arch.isBlank() || url.isBlank()) return null
        return BoardPlatform(
            packageName = "",
            maintainer = "",
            name = name,
            architecture = arch,
            version = version,
            category = category,
            url = url,
            archiveFileName = archiveFileName,
            checksum = checksum,
            size = size,
            boardNames = boardNames
        )
    }

    private fun saveCache(context: Context, list: List<BoardPlatform>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().apply {
                put("pkg", p.packageName)
                put("maintainer", p.maintainer)
                put("name", p.name)
                put("arch", p.architecture)
                put("ver", p.version)
                put("cat", p.category)
                put("url", p.url)
                put("file", p.archiveFileName)
                put("sum", p.checksum)
                put("size", p.size)
                put("boards", JSONArray(p.boardNames))
            })
        }
        cacheFile(context).writeText(arr.toString())
    }

    // ---- Install / Uninstall / Parse boards.txt ----

    suspend fun install(context: Context, platform: BoardPlatform): Result<InstalledPlatform> =
        withContext(Dispatchers.IO) {
            runCatching {
                val targetDir = File(
                    packagesDir(context),
                    "${platform.packageName}/hardware/${platform.architecture}/${platform.version}"
                )
                val parentArchDir = targetDir.parentFile!!

                val (downloadUrl, verifyChecksum) = resolveArchiveUrl(platform)
                val cleanUrl = downloadUrl.substringBefore('?').substringBefore('#')
                val tempArchive = File(context.cacheDir, "board_${System.currentTimeMillis()}.tmp")
                val stageDir = File(context.cacheDir, "board_stage_${System.currentTimeMillis()}")

                try {
                    val conn = openRedirectingConnection(downloadUrl, timeoutMs = 45_000)
                    val digest = MessageDigest.getInstance("SHA-256")
                    try {
                        if (conn.responseCode !in 200..299) {
                            error("Download failed (HTTP ${conn.responseCode})")
                        }
                        BufferedInputStream(conn.inputStream).use { input ->
                            FileOutputStream(tempArchive).use { out ->
                                val buf = ByteArray(16 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    digest.update(buf, 0, n)
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }

                    if (verifyChecksum) {
                        val expected = platform.checksum.removePrefix("SHA-256:").lowercase()
                        if (expected.isNotBlank()) {
                            val actual = digest.digest().joinToString("") { "%02x".format(it) }
                            if (actual != expected) {
                                error("Checksum mismatch for ${platform.name}")
                            }
                        }
                    }

                    stageDir.mkdirs()
                    when {
                        cleanUrl.endsWith(".tar.gz", ignoreCase = true) ||
                            cleanUrl.endsWith(".tgz", ignoreCase = true) ->
                            extractTarGz(tempArchive, stageDir)
                        cleanUrl.endsWith(".zip", ignoreCase = true) ->
                            extractZip(tempArchive, stageDir)
                        else ->
                            error("Unsupported archive format: ${cleanUrl.substringAfterLast('/')}")
                    }

                    val contentRoot = unwrapSingleRootDir(stageDir)
                    // Only remove previous versions once the new archive is verified and extracted
                    parentArchDir.listFiles()?.forEach { it.deleteRecursively() }
                    targetDir.parentFile?.mkdirs()
                    if (!contentRoot.renameTo(targetDir)) {
                        contentRoot.copyRecursively(targetDir, overwrite = true)
                    }

                    File(targetDir, "installed.json").writeText(
                        JSONObject().apply {
                            put("packageName", platform.packageName)
                            put("architecture", platform.architecture)
                            put("name", platform.name)
                            put("version", platform.version)
                        }.toString()
                    )

                    readInstalledPlatform(
                        targetDir,
                        platform.packageName,
                        platform.architecture
                    ) ?: error("Installed folder missing platform files")
                } finally {
                    tempArchive.delete()
                    stageDir.deleteRecursively()
                }
            }
        }

    /**
     * Extracts .zip / .tar.gz / .tgz natively, and maps official Arduino .tar.bz2 cores
     * to their matching GitHub release .zip tags so any official core installs on-device.
     */
    private fun resolveArchiveUrl(platform: BoardPlatform): Pair<String, Boolean> {
        val url = platform.url
        val cleanUrl = url.substringBefore('?').substringBefore('#')
        if (cleanUrl.endsWith(".zip", ignoreCase = true) ||
            cleanUrl.endsWith(".tar.gz", ignoreCase = true) ||
            cleanUrl.endsWith(".tgz", ignoreCase = true)
        ) {
            return url to true
        }
        if (platform.packageName.equals("arduino", ignoreCase = true)) {
            val archLower = platform.architecture.lowercase()
            val repo = when {
                archLower == "avr" -> "ArduinoCore-avr"
                archLower == "samd" -> "ArduinoCore-samd"
                archLower == "megaavr" -> "ArduinoCore-megaavr"
                archLower == "sam" -> "ArduinoCore-sam"
                archLower.startsWith("renesas") -> "ArduinoCore-renesas"
                archLower.startsWith("mbed") -> "ArduinoCore-mbed"
                else -> null
            }
            if (repo != null) {
                return "https://github.com/arduino/$repo/archive/refs/tags/${platform.version}.zip" to false
            }
        }
        val ext = when {
            cleanUrl.endsWith(".tar.bz2", ignoreCase = true) -> ".tar.bz2"
            cleanUrl.endsWith(".tar.xz", ignoreCase = true) -> ".tar.xz"
            else -> ".${cleanUrl.substringAfterLast('.', "unknown")}"
        }
        error("Archive format ($ext) is not supported yet; only .zip and .tar.gz archives can be extracted on-device")
    }

    suspend fun uninstall(context: Context, packageName: String, architecture: String): Boolean =
        withContext(Dispatchers.IO) {
            val archDir = File(packagesDir(context), "$packageName/hardware/$architecture")
            !archDir.exists() || archDir.deleteRecursively()
        }

    suspend fun listInstalled(context: Context): Map<String, InstalledPlatform> =
        withContext(Dispatchers.IO) {
            val root = packagesDir(context)
            val result = LinkedHashMap<String, InstalledPlatform>()
            val pkgDirs = root.listFiles { f -> f.isDirectory } ?: return@withContext result

            for (pkgDir in pkgDirs) {
                val hwDir = File(pkgDir, "hardware")
                val archDirs = hwDir.listFiles { f -> f.isDirectory } ?: continue
                for (archDir in archDirs) {
                    val verDir = archDir.listFiles { f -> f.isDirectory }?.firstOrNull() ?: continue
                    val inst = readInstalledPlatform(verDir, pkgDir.name, archDir.name) ?: continue
                    result[inst.id] = inst
                }
            }
            result
        }

    private fun readInstalledPlatform(
        verDir: File,
        packageName: String,
        architecture: String
    ): InstalledPlatform? {
        if (!verDir.isDirectory) return null
        val metaFile = File(verDir, "installed.json")
        val meta = runCatching {
            if (metaFile.exists()) JSONObject(metaFile.readText()) else null
        }.getOrNull()

        val name = meta?.optString("name")?.takeIf { it.isNotBlank() }
            ?: "$packageName:$architecture"
        val version = meta?.optString("version")?.takeIf { it.isNotBlank() }
            ?: verDir.name

        val boardsTxt = File(verDir, "boards.txt")
        val boards = if (boardsTxt.exists()) {
            parseBoardsTxt(boardsTxt, packageName, architecture)
        } else {
            emptyList()
        }

        val builtInLibs = File(verDir, "libraries")
            .listFiles { f -> f.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

        return InstalledPlatform(
            packageName = packageName,
            architecture = architecture,
            name = name,
            version = version,
            installDir = verDir,
            boards = boards,
            builtInLibs = builtInLibs
        )
    }

    fun parseBoardsTxt(
        file: File,
        packageName: String,
        architecture: String
    ): List<PlatformBoard> {
        val boards = LinkedHashMap<String, PlatformBoard>()
        val hiddenIds = HashSet<String>()
        runCatching {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEachLine
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                if (key.endsWith(".hide") && key.count { it == '.' } == 1 && value.equals("true", ignoreCase = true)) {
                    hiddenIds += key.removeSuffix(".hide")
                } else if (key.endsWith(".name") && key.count { it == '.' } == 1 && value.isNotBlank()) {
                    val boardId = key.removeSuffix(".name")
                    boards[boardId] = PlatformBoard(
                        id = boardId,
                        name = value,
                        fqbn = "$packageName:$architecture:$boardId",
                        architecture = architecture
                    )
                }
            }
        }
        return boards.values
            .filterNot { it.id in hiddenIds }
            .sortedBy { it.name.lowercase() }
    }

    fun isBoardInstalled(
        installed: Map<String, InstalledPlatform>,
        board: SelectedBoard
    ): Boolean {
        if (board.fqbn.isBlank()) return false
        return installed.values.any { inst ->
            inst.packageName == board.packageName &&
                inst.architecture == board.architecture &&
                inst.boards.any { it.fqbn == board.fqbn }
        }
    }

    fun installedBoards(installed: Map<String, InstalledPlatform>): List<SelectedBoard> {
        val out = LinkedHashMap<String, SelectedBoard>()
        for (platform in installed.values) {
            for (b in platform.boards) {
                out[b.fqbn] = SelectedBoard(
                    packageName = platform.packageName,
                    architecture = platform.architecture,
                    boardId = b.id,
                    name = b.name,
                    fqbn = b.fqbn
                )
            }
        }
        return out.values.sortedBy { it.name.lowercase() }
    }

    fun selectableBoards(installed: Map<String, InstalledPlatform>): List<SelectedBoard> =
        installedBoards(installed)

    private val KNOWN_MENU_LABELS = mapOf(
        "cpu" to "Processor",
        "CPUFreq" to "CPU Frequency",
        "FlashFreq" to "Flash Frequency",
        "FlashMode" to "Flash Mode",
        "FlashSize" to "Flash Size",
        "PartitionScheme" to "Partition Scheme",
        "DebugLevel" to "Core Debug Level",
        "UploadSpeed" to "Upload Speed",
        "PSRAM" to "PSRAM",
        "USBMode" to "USB Mode",
        "CDCOnBoot" to "USB CDC On Boot",
        "MSCOnBoot" to "USB MSC On Boot",
        "DFUOnBoot" to "USB DFU On Boot",
        "LoopCore" to "Arduino Runs On",
        "EventsCore" to "Events Run On",
        "EraseFlash" to "Erase All Flash Before Sketch Upload",
        "JTAGAdapter" to "JTAG Adapter",
        "Optimize" to "Compiler Optimization"
    )

    private fun findPlatformDir(context: Context, packageName: String, architecture: String): File? {
        val archRoot = File(packagesDir(context), "$packageName/hardware/$architecture")
        return archRoot.listFiles { f -> f.isDirectory }
            ?.sortedWith { a, b -> compareVersions(b.name, a.name) }
            ?.firstOrNull()
    }

    /**
     * Loads Arduino IDE Tools menu groups from the downloaded core's offline `boards.txt`,
     * and ensures CPU Frequency, Upload Speed, Core Debug Level, and Compiler Optimization
     * remain editable (anchored to the board's own `boards.txt` defaults) for boards like
     * Arduino Uno that don't declare `menu.*` subkeys in `boards.txt`.
     */
    suspend fun loadBoardMenus(
        context: Context,
        board: SelectedBoard
    ): List<BoardMenuGroup> = withContext(Dispatchers.IO) {
        if (board.fqbn.isBlank()) return@withContext emptyList()
        val platformDir = findPlatformDir(context, board.packageName, board.architecture)
            ?: return@withContext emptyList()
        val boardsTxt = File(platformDir, "boards.txt")
        if (!boardsTxt.isFile) return@withContext emptyList()

        val menuLabels = LinkedHashMap<String, String>()
        val menuOptions = LinkedHashMap<String, LinkedHashMap<String, String>>()
        val baseProps = LinkedHashMap<String, String>()
        val entries = readKeyValueFile(boardsTxt)
        val prefix = "${board.boardId}."
        val menuPrefix = "${board.boardId}.menu."

        for ((key, value) in entries) {
            if (key.startsWith("menu.") && key.count { it == '.' } == 1) {
                val menuId = key.removePrefix("menu.")
                if (value.isNotBlank()) menuLabels[menuId] = value
            } else if (key.startsWith(menuPrefix)) {
                val rest = key.removePrefix(menuPrefix)
                val dot = rest.indexOf('.')
                if (dot > 0) {
                    val menuId = rest.substring(0, dot)
                    val remainder = rest.substring(dot + 1)
                    // Option display label is `<boardId>.menu.<menuId>.<optId>=Label` (no extra dot)
                    if ('.' !in remainder && value.isNotBlank()) {
                        val opts = menuOptions.getOrPut(menuId) { LinkedHashMap() }
                        opts.putIfAbsent(remainder, value)
                    }
                }
            } else if (key.startsWith(prefix)) {
                baseProps[key.removePrefix(prefix)] = value
            }
        }

        val groups = ArrayList<BoardMenuGroup>()
        val seenIds = HashSet<String>()

        for ((menuId, opts) in menuOptions) {
            if (opts.isEmpty()) continue
            seenIds += menuId
            val list = opts.entries.map { BoardMenuChoice(it.key, it.value) }
            groups += BoardMenuGroup(
                id = menuId,
                label = menuLabels[menuId] ?: KNOWN_MENU_LABELS[menuId] ?: menuId,
                options = list,
                defaultOptionId = list.first().id
            )
        }

        val archLower = board.architecture.lowercase()
        val isEsp32 = "esp32" in archLower
        val isEsp8266 = "esp8266" in archLower
        val isAvrFamily = archLower == "avr" || archLower == "megaavr"
        val isRp2040 = "rp2040" in archLower || "rp2350" in archLower
        val idLower = board.boardId.lowercase()

        if ("CPUFreq" !in seenIds) {
            val baseFcpu = baseProps["build.f_cpu"].orEmpty().trim()
            val choices = LinkedHashMap<String, String>()
            if (isEsp32) {
                val baseMhz = normalizeFreqToMhz(baseFcpu)
                val candidates = if (idLower.contains("c3") || idLower.contains("c6")) {
                    listOf("160" to "160 MHz (WiFi)", "80" to "80 MHz (WiFi)", "40" to "40 MHz")
                } else {
                    listOf("240" to "240 MHz (WiFi/BT)", "160" to "160 MHz (WiFi/BT)", "80" to "80 MHz (WiFi/BT)", "40" to "40 MHz")
                }
                if (baseMhz != null) {
                    val mhzKey = baseMhz.toString()
                    choices[mhzKey] = "$mhzKey MHz (Default)"
                }
                for ((id, lbl) in candidates) {
                    choices.putIfAbsent(id, lbl)
                }
            } else {
                val normalizedBase = normalizeFreqToken(baseFcpu)
                if (normalizedBase != null) {
                    choices[normalizedBase] = "${formatFreqShort(normalizedBase)} (Default)"
                }
                val standard = when {
                    isAvrFamily -> listOf(
                        "20000000L" to "20 MHz",
                        "16000000L" to "16 MHz",
                        "12000000L" to "12 MHz",
                        "8000000L" to "8 MHz",
                        "4000000L" to "4 MHz",
                        "1000000L" to "1 MHz"
                    )
                    isEsp8266 -> listOf(
                        "80000000L" to "80 MHz",
                        "160000000L" to "160 MHz"
                    )
                    isRp2040 -> listOf(
                        "133000000L" to "133 MHz",
                        "125000000L" to "125 MHz",
                        "200000000L" to "200 MHz",
                        "100000000L" to "100 MHz",
                        "48000000L" to "48 MHz"
                    )
                    else -> listOf(
                        "480000000L" to "480 MHz",
                        "240000000L" to "240 MHz",
                        "168000000L" to "168 MHz",
                        "120000000L" to "120 MHz",
                        "80000000L" to "80 MHz",
                        "72000000L" to "72 MHz",
                        "64000000L" to "64 MHz",
                        "48000000L" to "48 MHz",
                        "24000000L" to "24 MHz",
                        "16000000L" to "16 MHz"
                    )
                }
                for ((id, lbl) in standard) {
                    choices.putIfAbsent(id, lbl)
                }
            }
            if (choices.isNotEmpty()) {
                val list = choices.entries.map { BoardMenuChoice(it.key, it.value) }
                groups += BoardMenuGroup(
                    id = "CPUFreq",
                    label = "CPU Frequency",
                    options = list,
                    defaultOptionId = list.first().id
                )
            }
        }

        if ("UploadSpeed" !in seenIds) {
            val baseSpeed = baseProps["upload.speed"].orEmpty().trim()
            val choices = LinkedHashMap<String, String>()
            if (baseSpeed.isNotBlank()) {
                choices[baseSpeed] = "$baseSpeed baud (Default)"
            }
            val standardSpeeds = if (isEsp32) {
                listOf("921600", "460800", "256000", "230400", "115200", "57600")
            } else {
                listOf("115200", "57600", "38400", "19200", "9600", "250000", "460800", "921600")
            }
            for (spd in standardSpeeds) {
                choices.putIfAbsent(spd, "$spd baud")
            }
            val list = choices.entries.map { BoardMenuChoice(it.key, it.value) }
            groups += BoardMenuGroup(
                id = "UploadSpeed",
                label = "Upload Speed",
                options = list,
                defaultOptionId = list.first().id
            )
        }

        if ("DebugLevel" !in seenIds) {
            groups += BoardMenuGroup(
                id = "DebugLevel",
                label = "Core Debug Level",
                options = listOf(
                    BoardMenuChoice("none", "None (0)"),
                    BoardMenuChoice("error", "Error (1)"),
                    BoardMenuChoice("warn", "Warn (2)"),
                    BoardMenuChoice("info", "Info (3)"),
                    BoardMenuChoice("debug", "Debug (4)"),
                    BoardMenuChoice("verbose", "Verbose (5)")
                ),
                defaultOptionId = "none"
            )
        }

        if ("Optimize" !in seenIds) {
            groups += BoardMenuGroup(
                id = "Optimize",
                label = "Compiler Optimization",
                options = listOf(
                    BoardMenuChoice("Os", "Smallest (-Os)"),
                    BoardMenuChoice("O2", "Fast (-O2)"),
                    BoardMenuChoice("Og", "Debug (-Og)"),
                    BoardMenuChoice("O0", "None (-O0)")
                ),
                defaultOptionId = "Os"
            )
        }

        groups
    }

    private fun normalizeFreqToMhz(raw: String): Long? {
        val digits = raw.trim().removeSuffix("UL").removeSuffix("ul").removeSuffix("L").removeSuffix("l")
        val hz = digits.toLongOrNull() ?: return null
        return if (hz >= 1_000_000L) hz / 1_000_000L else null
    }

    private fun normalizeFreqToken(raw: String): String? {
        val cleaned = raw.trim().removeSuffix("UL").removeSuffix("ul").removeSuffix("L").removeSuffix("l")
        val hz = cleaned.toLongOrNull() ?: return null
        if (hz <= 0L) return null
        return "${hz}L"
    }

    private fun formatFreqShort(token: String): String {
        val hz = token.removeSuffix("L").toLongOrNull() ?: return token
        return if (hz >= 1_000_000L && hz % 1_000_000L == 0L) {
            "${hz / 1_000_000L} MHz"
        } else if (hz >= 1000L && hz % 1000L == 0L) {
            "${hz / 1000L} kHz"
        } else {
            "$hz Hz"
        }
    }

    fun buildEffectiveFqbn(
        baseFqbn: String,
        menus: List<BoardMenuGroup>,
        selectedOptions: Map<String, String>
    ): String {
        if (baseFqbn.isBlank()) return ""
        val customParts = ArrayList<String>()
        for (menu in menus) {
            val chosen = selectedOptions[menu.id]
            if (!chosen.isNullOrBlank() && chosen != menu.defaultOptionId) {
                customParts += "${menu.id}=$chosen"
            }
        }
        return if (customParts.isEmpty()) baseFqbn else "$baseFqbn:${customParts.joinToString(",")}"
    }

    /**
     * Reads [BoardBuildConfig] strictly from the downloaded core's offline files
     * (`boards.txt`, `platform.txt`, `installed.json`). Returns `null` if the core
     * is not downloaded locally.
     */
    suspend fun loadOfflineBoardInfo(
        context: Context,
        board: SelectedBoard,
        selectedOptions: Map<String, String> = emptyMap()
    ): BoardBuildConfig? = withContext(Dispatchers.IO) {
        if (board.fqbn.isBlank()) return@withContext null
        val platformDir = findPlatformDir(context, board.packageName, board.architecture)
            ?: return@withContext null
        if (!File(platformDir, "boards.txt").isFile) return@withContext null
        resolveBuildConfig(context, board, selectedOptions)
    }

    /**
     * Resolves an installed board's exact offline `boards.txt` + `platform.txt` settings
     * (MCU, F_CPU, core folder, variant folder, board macro, USB VID/PID, upload settings,
     * extra flags, memory limits) combined with user-selected [selectedOptions].
     */
    suspend fun resolveBuildConfig(
        context: Context,
        board: SelectedBoard,
        selectedOptions: Map<String, String> = emptyMap()
    ): BoardBuildConfig = withContext(Dispatchers.IO) {
        val platformDir = findPlatformDir(context, board.packageName, board.architecture)
        val menus = loadBoardMenus(context, board)
        val effectiveOptions = LinkedHashMap<String, String>()
        for (menu in menus) {
            val rawSelected = selectedOptions[menu.id]
            val allowCustom = menu.id == "CPUFreq" || menu.id == "UploadSpeed"
            val picked = rawSelected?.takeIf { optId ->
                optId.isNotBlank() && (
                    menu.options.any { it.id == optId } ||
                        (allowCustom && optId.removeSuffix("L").removeSuffix("l").all { it.isDigit() })
                    )
            } ?: menu.defaultOptionId
            effectiveOptions[menu.id] = picked
        }

        val props = LinkedHashMap<String, String>()
        val menuOverrides = LinkedHashMap<String, String>()
        var platformName = ""
        var platformVersion = platformDir?.name.orEmpty()
        var boardsTxtPath = ""

        if (platformDir != null) {
            val metaFile = File(platformDir, "installed.json")
            if (metaFile.isFile) {
                runCatching {
                    val meta = JSONObject(metaFile.readText())
                    platformName = meta.optString("name", "").ifBlank { platformName }
                    platformVersion = meta.optString("version", "").ifBlank { platformVersion }
                }
            }

            val platformTxt = File(platformDir, "platform.txt")
            if (platformTxt.isFile) {
                readKeyValueFile(platformTxt).forEach { (k, v) ->
                    if (k == "name" && v.isNotBlank()) platformName = v
                    else if (k == "version" && v.isNotBlank()) platformVersion = v
                    props[k] = v
                }
            }

            val boardsTxt = File(platformDir, "boards.txt")
            if (boardsTxt.isFile) {
                boardsTxtPath = boardsTxt.absolutePath
                val boardEntries = readKeyValueFile(boardsTxt)
                val prefix = "${board.boardId}."
                val menuPrefix = "${board.boardId}.menu."

                for ((key, value) in boardEntries) {
                    if (!key.startsWith(prefix)) continue
                    if (key.startsWith(menuPrefix)) {
                        // Format: <boardId>.menu.<menuId>.<optId>.<propKey>
                        val rest = key.removePrefix(menuPrefix)
                        val parts = rest.split('.', limit = 3)
                        if (parts.size == 3) {
                            val menuId = parts[0]
                            val optId = parts[1]
                            val propKey = parts[2]
                            val activeOpt = effectiveOptions[menuId]
                            if (activeOpt == optId) {
                                menuOverrides[propKey] = value
                            }
                        }
                    } else {
                        props[key.removePrefix(prefix)] = value
                    }
                }
                // Selected menu options override board base settings (e.g. menu.CPUFreq -> build.f_cpu)
                props.putAll(menuOverrides)
            }
        }

        val isEsp32 = board.architecture.contains("esp32", ignoreCase = true)
        props.putIfAbsent("build.usb_manufacturer", "\"Unknown\"")

        val mcu = expandPlaceholders(props["build.mcu"].orEmpty(), props)
        var fCpu = expandPlaceholders(props["build.f_cpu"].orEmpty(), props)
        if ("build.f_cpu" !in menuOverrides || !selectedOptions["CPUFreq"].isNullOrBlank()) {
            effectiveOptions["CPUFreq"]?.let { freqOpt ->
                val digits = freqOpt.trim().removeSuffix("UL").removeSuffix("ul").removeSuffix("L").removeSuffix("l")
                val num = digits.toLongOrNull()
                if (num != null && num > 0L) {
                    fCpu = if (num in 1L..1000L) {
                        "${num * 1_000_000L}L"
                    } else {
                        "${num}L"
                    }
                }
            }
        }

        val boardDefine = expandPlaceholders(props["build.board"].orEmpty(), props)
        val coreName = expandPlaceholders(props["build.core"].orEmpty(), props)
        val variantName = expandPlaceholders(props["build.variant"].orEmpty(), props)
        val extraFlagsRaw = expandPlaceholders(props["build.extra_flags"].orEmpty(), props)

        val debugLevel = if ("build.code_debug" in menuOverrides || "build.code_debug" in props) {
            props["build.code_debug"].orEmpty()
        } else {
            when (effectiveOptions["DebugLevel"]) {
                "error" -> "1"
                "warn" -> "2"
                "info" -> "3"
                "debug" -> "4"
                "verbose" -> "5"
                else -> "0"
            }
        }
        var uploadSpeed = expandPlaceholders(props["upload.speed"].orEmpty(), props)
        if ("upload.speed" !in menuOverrides || !selectedOptions["UploadSpeed"].isNullOrBlank()) {
            effectiveOptions["UploadSpeed"]?.takeIf { it.isNotBlank() }?.let { chosenSpeed ->
                uploadSpeed = chosenSpeed
            }
        }
        val uploadProtocol = expandPlaceholders(props["upload.protocol"].orEmpty(), props)
        val uploadTool = expandPlaceholders(
            props["upload.tool"] ?: props["upload.tool.default"].orEmpty(),
            props
        )
        val flashMode = expandPlaceholders(
            props["build.flash_mode"] ?: effectiveOptions["FlashMode"].orEmpty(),
            props
        )
        val flashFreq = expandPlaceholders(
            props["build.flash_freq"] ?: effectiveOptions["FlashFreq"].orEmpty(),
            props
        )
        val flashSize = expandPlaceholders(
            props["build.flash_size"] ?: effectiveOptions["FlashSize"].orEmpty(),
            props
        )
        val partitionScheme = expandPlaceholders(
            props["build.partitions"] ?: effectiveOptions["PartitionScheme"].orEmpty(),
            props
        )
        val psramMode = expandPlaceholders(
            props["build.psram_mode"] ?: effectiveOptions["PSRAM"].orEmpty(),
            props
        )
        val optimizeLevel = effectiveOptions["Optimize"] ?: "Os"

        val parsedExtra = splitShellFlags(extraFlagsRaw).toMutableList()
        val extraCppDefs = splitShellFlags(expandPlaceholders(props["build.defines"].orEmpty(), props))
        for (flag in extraCppDefs) {
            if (flag !in parsedExtra) parsedExtra += flag
        }
        if (debugLevel.isNotBlank() && debugLevel != "0" &&
            parsedExtra.none { it.startsWith("-DCORE_DEBUG_LEVEL=") }
        ) {
            parsedExtra += "-DCORE_DEBUG_LEVEL=$debugLevel"
        }
        if (isEsp32 && psramMode.isNotBlank() && !psramMode.equals("disabled", ignoreCase = true) &&
            parsedExtra.none { it == "-DBOARD_HAS_PSRAM" }
        ) {
            parsedExtra += "-DBOARD_HAS_PSRAM"
        }

        val vidPids = ArrayList<String>()
        val directVid = props["build.vid"].orEmpty()
        val directPid = props["build.pid"].orEmpty()
        if (directVid.isNotBlank() && directPid.isNotBlank()) {
            vidPids += "$directVid:$directPid"
        }
        var idx = 0
        while (idx < 16) {
            val v = props["vid.$idx"]
            val p = props["pid.$idx"]
            if (v == null && p == null) break
            if (!v.isNullOrBlank() && !p.isNullOrBlank()) {
                val pair = "$v:$p"
                if (pair !in vidPids) vidPids += pair
            }
            idx++
        }

        val maxFlash = props["upload.maximum_size"]?.toLongOrNull() ?: 0L
        val maxRam = props["upload.maximum_data_size"]?.toLongOrNull() ?: 0L

        val coreDir = resolveCoreOrVariantDir(context, platformDir, board.architecture, "cores", coreName)
        val variantDir = resolveCoreOrVariantDir(context, platformDir, board.architecture, "variants", variantName)

        val defaultPrefix = defaultToolchainPrefix(board.architecture, mcu)
        fun cleanCmd(rawKey: String, fallback: String): String {
            val resolved = expandPlaceholders(props[rawKey].orEmpty(), props)
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
            return if (resolved.isNotBlank() && '{' !in resolved) resolved else fallback
        }
        val compilerCCmd = cleanCmd("compiler.c.cmd", "$defaultPrefix-gcc")
        val compilerCppCmd = cleanCmd("compiler.cpp.cmd", "$defaultPrefix-g++")
        val compilerArCmd = cleanCmd("compiler.ar.cmd", "$defaultPrefix-ar")
        val compilerObjcopyCmd = cleanCmd("compiler.objcopy.cmd", "$defaultPrefix-objcopy")
        val compilerSizeCmd = cleanCmd("compiler.size.cmd", "$defaultPrefix-size")

        val platformCFlags = sanitizePlatformFlags(
            splitShellFlags(expandPlaceholders(props["compiler.c.flags"].orEmpty(), props)) +
                splitShellFlags(expandPlaceholders(props["compiler.c.extra_flags"].orEmpty(), props))
        )
        val platformCppFlags = sanitizePlatformFlags(
            splitShellFlags(expandPlaceholders(props["compiler.cpp.flags"].orEmpty(), props)) +
                splitShellFlags(expandPlaceholders(props["compiler.cpp.extra_flags"].orEmpty(), props))
        )
        val platformAsmFlags = sanitizePlatformFlags(
            splitShellFlags(expandPlaceholders(props["compiler.S.flags"].orEmpty(), props))
        )
        val platformLinkFlags = sanitizePlatformFlags(
            splitShellFlags(expandPlaceholders(props["compiler.c.elf.flags"].orEmpty(), props)) +
                splitShellFlags(expandPlaceholders(props["compiler.ldflags"].orEmpty(), props))
        )

        BoardBuildConfig(
            fqbn = board.fqbn,
            effectiveFqbn = buildEffectiveFqbn(board.fqbn, menus, effectiveOptions),
            packageName = board.packageName,
            architecture = board.architecture,
            boardId = board.boardId,
            boardName = props["name"] ?: board.name,
            mcu = mcu,
            fCpu = fCpu,
            boardDefine = boardDefine,
            coreName = coreName,
            variantName = variantName,
            uploadSpeed = uploadSpeed,
            codeDebugLevel = debugLevel,
            flashMode = flashMode,
            flashFreq = flashFreq,
            flashSize = flashSize,
            partitionScheme = partitionScheme,
            psramMode = psramMode,
            optimizeLevel = optimizeLevel,
            appliedOptions = effectiveOptions,
            extraFlags = parsedExtra,
            uploadMaximumSize = maxFlash,
            uploadMaximumDataSize = maxRam,
            platformName = platformName,
            platformVersion = platformVersion,
            uploadProtocol = uploadProtocol,
            uploadTool = uploadTool,
            vidPids = vidPids,
            boardsTxtPath = boardsTxtPath,
            platformDir = platformDir,
            coreDir = coreDir,
            variantDir = variantDir,
            compilerCCmd = compilerCCmd,
            compilerCppCmd = compilerCppCmd,
            compilerArCmd = compilerArCmd,
            compilerObjcopyCmd = compilerObjcopyCmd,
            compilerSizeCmd = compilerSizeCmd,
            platformCFlags = platformCFlags,
            platformCppFlags = platformCppFlags,
            platformAsmFlags = platformAsmFlags,
            platformLinkFlags = platformLinkFlags
        )
    }

    private fun defaultToolchainPrefix(architecture: String, mcu: String): String {
        val arch = architecture.lowercase()
        val m = mcu.lowercase()
        return when {
            arch == "avr" || arch == "megaavr" -> "avr"
            "esp32" in arch -> when {
                "c3" in m || "c6" in m || "h2" in m || "p4" in m -> "riscv32-esp-elf"
                "s2" in m -> "xtensa-esp32s2-elf"
                "s3" in m -> "xtensa-esp32s3-elf"
                else -> "xtensa-esp32-elf"
            }
            "esp8266" in arch -> "xtensa-lx106-elf"
            "riscv" in arch || "ch32v" in arch || "gd32v" in arch -> "riscv-none-elf"
            else -> "arm-none-eabi"
        }
    }

    private fun sanitizePlatformFlags(flags: List<String>): List<String> {
        val out = ArrayList<String>()
        var skipNext = false
        for (i in flags.indices) {
            if (skipNext) {
                skipNext = false
                continue
            }
            val token = flags[i].trim()
            if (token.isEmpty() || '{' in token) continue
            if (token in setOf("-I", "-L", "-iprefix", "-isystem", "-o", "-T")) {
                val next = flags.getOrNull(i + 1)?.trim().orEmpty()
                if (next.isEmpty() || next.startsWith("-") || '{' in next) {
                    continue
                }
                if (token == "-iprefix" || token == "-T") {
                    skipNext = true
                    continue
                }
            }
            if (token.startsWith("@") || token.startsWith("-iprefix") || token.startsWith("-T")) continue
            out += token
        }
        return out
    }

    private fun resolveCoreOrVariantDir(
        context: Context,
        platformDir: File?,
        architecture: String,
        folder: String,
        refName: String
    ): File? {
        if (refName.isBlank()) return null
        if (':' in refName) {
            val refPkg = refName.substringBefore(':')
            val name = refName.substringAfter(':')
            val refVerDir = findPlatformDir(context, refPkg, architecture)
            return refVerDir?.let { File(it, "$folder/$name") }
        }
        return platformDir?.let { File(it, "$folder/$refName") }
    }

    private fun readKeyValueFile(file: File): List<Pair<String, String>> {
        val entries = ArrayList<Pair<String, String>>()
        runCatching {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEachLine
                entries += line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }
        }
        return entries
    }

    private fun expandPlaceholders(input: String, props: Map<String, String>): String {
        var current = input
        val regex = Regex("""\{([A-Za-z0-9_.-]+)\}""")
        repeat(6) {
            var changed = false
            current = regex.replace(current) { match ->
                val replacement = props[match.groupValues[1]]
                if (replacement != null) {
                    changed = true
                    replacement
                } else {
                    ""
                }
            }
            if (!changed) return current.trim()
        }
        return current.trim()
    }

    private fun splitShellFlags(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (ch in raw.trim()) {
            when {
                quote != null && ch == quote -> {
                    quote = null
                }
                quote == null && (ch == '\'' || ch == '"') -> {
                    quote = ch
                }
                quote == null && ch.isWhitespace() -> {
                    if (cur.isNotEmpty()) {
                        out += cur.toString()
                        cur.clear()
                    }
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out.filter { it.isNotBlank() }
    }

    /** Reads keywords.txt from core built-in libraries (Wire, SPI, WiFi, Preferences, etc.). */
    suspend fun installedCoreKeywords(context: Context): List<String> = withContext(Dispatchers.IO) {
        val out = LinkedHashSet<String>()
        val root = packagesDir(context)
        val pkgDirs = root.listFiles { f -> f.isDirectory } ?: return@withContext emptyList()
        for (pkgDir in pkgDirs) {
            val archDirs = File(pkgDir, "hardware").listFiles { f -> f.isDirectory } ?: continue
            for (archDir in archDirs) {
                val verDir = archDir.listFiles { f -> f.isDirectory }?.firstOrNull() ?: continue
                val libDirs = File(verDir, "libraries").listFiles { f -> f.isDirectory } ?: continue
                for (libDir in libDirs) {
                    val kw = File(libDir, "keywords.txt")
                    if (!kw.exists()) continue
                    runCatching {
                        kw.forEachLine { raw ->
                            val line = raw.trim()
                            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                            val token = line.split(Regex("\\s+")).firstOrNull()?.trim() ?: return@forEachLine
                            if (token.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) out += token
                        }
                    }
                }
            }
        }
        out.toList()
    }

    // ---- Helpers ----

    private fun extractZip(zipFile: File, destDir: File) {
        val destCanonical = destDir.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val outFile = File(destDir, entry.name)
                val outCanonical = outFile.canonicalPath
                if (!outCanonical.startsWith(destCanonical) && outCanonical != destDir.canonicalPath) {
                    error("Unsafe zip path: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
            }
        }
    }

    private fun extractTarGz(tarGzFile: File, destDir: File) {
        val destCanonical = destDir.canonicalPath + File.separator
        GZIPInputStream(BufferedInputStream(FileInputStream(tarGzFile))).use { gz ->
            val header = ByteArray(512)
            val buf = ByteArray(16 * 1024)
            var longName: String? = null

            while (true) {
                val read = readFully(gz, header)
                if (read < 512 || header.all { it == 0.toByte() }) break

                val rawName = readCString(header, 0, 100)
                val prefix = readCString(header, 345, 155)
                val sizeStr = readCString(header, 124, 12).trim()
                val size = sizeStr.toLongOrNull(8) ?: 0L
                val typeFlag = header[156].toInt().toChar()

                if (typeFlag == 'L') {
                    val nameBytes = ByteArray(size.toInt())
                    readFully(gz, nameBytes)
                    longName = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000', '\n')
                    skipTarPadding(gz, size)
                    continue
                }

                val entryPath = longName ?: if (prefix.isNotBlank()) "$prefix/$rawName" else rawName
                longName = null
                if (entryPath.isBlank()) {
                    skipBytes(gz, size)
                    skipTarPadding(gz, size)
                    continue
                }

                val outFile = File(destDir, entryPath)
                val outCanonical = outFile.canonicalPath
                if (!outCanonical.startsWith(destCanonical) && outCanonical != destDir.canonicalPath) {
                    error("Unsafe tar path: $entryPath")
                }

                when (typeFlag) {
                    '5' -> outFile.mkdirs()
                    '0', '\u0000' -> {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            var remaining = size
                            while (remaining > 0) {
                                val chunk = minOf(buf.size.toLong(), remaining).toInt()
                                val n = gz.read(buf, 0, chunk)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                    }
                    else -> skipBytes(gz, size)
                }
                skipTarPadding(gz, size)
            }
        }
    }

    private fun readFully(stream: InputStream, dst: ByteArray): Int {
        var offset = 0
        while (offset < dst.size) {
            val n = stream.read(dst, offset, dst.size - offset)
            if (n < 0) break
            offset += n
        }
        return offset
    }

    private fun skipBytes(stream: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(4096)
        while (remaining > 0) {
            val n = stream.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    private fun skipTarPadding(stream: InputStream, size: Long) {
        val rem = (512 - (size % 512)) % 512
        if (rem > 0) skipBytes(stream, rem)
    }

    private fun readCString(buf: ByteArray, offset: Int, len: Int): String {
        var end = offset
        val limit = offset + len
        while (end < limit && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    private fun unwrapSingleRootDir(stageDir: File): File {
        var cur = stageDir
        repeat(3) {
            if (File(cur, "boards.txt").isFile) return cur
            val children = cur.listFiles()
                ?.filterNot { it.name == "pax_global_header" || it.name == "__MACOSX" }
                ?: return cur
            if (children.size == 1 && children[0].isDirectory) {
                cur = children[0]
            } else {
                return cur
            }
        }
        return cur
    }

    private fun openRedirectingConnection(urlStr: String, timeoutMs: Int): HttpURLConnection {
        var current = urlStr
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "EmbeddedStudio-Android")
                setRequestProperty("Accept-Encoding", "gzip")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) error("Redirect without Location header")
                current = URL(URL(current), loc).toString()
            } else {
                return conn
            }
        }
        error("Too many redirects for $urlStr")
    }

    private fun JsonReader.nextStringOrEmpty(): String =
        if (peek() == JsonToken.NULL) {
            nextNull()
            ""
        } else {
            nextString()
        }

    private fun JsonReader.nextLongSafe(): Long =
        when (peek()) {
            JsonToken.NUMBER -> nextLong()
            JsonToken.STRING -> nextString().toLongOrNull() ?: 0L
            JsonToken.NULL -> {
                nextNull()
                0L
            }
            else -> {
                skipValue()
                0L
            }
        }

    private fun isBetterVersion(candidate: String, current: String): Boolean {
        val candPre = candidate.contains("-")
        val currPre = current.contains("-")
        if (candPre != currPre) return !candPre
        return compareVersions(candidate, current) > 0
    }

    private fun compareVersions(a: String, b: String): Int {
        val ap = a.substringBefore("-").split(".")
        val bp = b.substringBefore("-").split(".")
        val max = maxOf(ap.size, bp.size)
        for (i in 0 until max) {
            val av = ap.getOrNull(i)?.toIntOrNull() ?: 0
            val bv = bp.getOrNull(i)?.toIntOrNull() ?: 0
            if (av != bv) return av.compareTo(bv)
        }
        return a.compareTo(b)
    }
}
