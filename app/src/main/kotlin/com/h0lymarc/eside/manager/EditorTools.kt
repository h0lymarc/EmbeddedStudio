package com.h0lymarc.eside.manager

import android.content.Context
import android.content.res.Configuration
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.registry.IThemeSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class EditorTheme(val id: String, val label: String, val dark: Boolean)

internal data class ThemeAsset(
    val id: String,
    val dark: Boolean,
    val fileNames: List<String>
)

object EditorThemes {
    val menuChoices = listOf(
        EditorTheme("system", "System", false),
        EditorTheme("darcula", "Darcula", true),
        EditorTheme("intellij", "IntelliJ IDEA", false),
        EditorTheme("github", "GitHub", false)
    )

    internal val assets = listOf(
        ThemeAsset("darcula", true, listOf("darcula.json")),
        ThemeAsset("intellij", false, listOf("intellij.json", "intellij_idea.json", "intellij_light.json")),
        ThemeAsset("github_dark", true, listOf("github_dark.json", "github-dark.json")),
        ThemeAsset("github_light", false, listOf("github_light.json", "github-light.json"))
    )
}

enum class Board(val label: String, val fqbn: String) {
    ARDUINO("Arduino (AVR)", "arduino:avr:uno"),
    ESP32("ESP32", "esp32:esp32:esp32")
}

object EditorPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("editor", Context.MODE_PRIVATE)

    fun theme(c: Context): String = prefs(c).getString("theme", "system") ?: "system"
    fun setTheme(c: Context, id: String) = prefs(c).edit().putString("theme", id).apply()

    fun board(c: Context): Board =
        runCatching { Board.valueOf(prefs(c).getString("board", "ARDUINO")!!) }
            .getOrDefault(Board.ARDUINO)

    fun setBoard(c: Context, b: Board) = prefs(c).edit().putString("board", b.name).apply()

    /**
     * The exact board picked in the Boards tab (for example "ESP32-S3 Dev Module"), saved with
     * all its parts. Falls back to the default board of the saved family.
     */
    fun selectedBoard(c: Context): SelectedBoard {
        val p = prefs(c)
        val pkg = p.getString("sel_pkg", null)
        val arch = p.getString("sel_arch", null)
        val id = p.getString("sel_id", null)
        val name = p.getString("sel_name", null)
        val fqbn = p.getString("sel_fqbn", null)
        if (pkg != null && arch != null && id != null && name != null && fqbn != null) {
            return SelectedBoard(pkg, arch, id, name, fqbn)
        }
        return SelectedBoard(
            packageName = "",
            architecture = "",
            boardId = "",
            name = "No board selected",
            fqbn = ""
        )
    }

    fun setSelectedBoard(c: Context, b: SelectedBoard) {
        prefs(c).edit()
            .putString("sel_pkg", b.packageName)
            .putString("sel_arch", b.architecture)
            .putString("sel_id", b.boardId)
            .putString("sel_name", b.name)
            .putString("sel_fqbn", b.fqbn)
            .putString("board", b.family.name)   // keep the old family setting in sync
            .apply()
    }

    fun boardOptions(c: Context, baseFqbn: String): Map<String, String> {
        if (baseFqbn.isBlank()) return emptyMap()
        val raw = prefs(c).getString("board_opts_$baseFqbn", null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val map = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optString(k)
            }
            map
        }.getOrDefault(emptyMap())
    }

    fun setBoardOption(c: Context, baseFqbn: String, menuId: String, optionId: String) {
        if (baseFqbn.isBlank()) return
        val next = boardOptions(c, baseFqbn).toMutableMap()
        next[menuId] = optionId
        setBoardOptions(c, baseFqbn, next)
    }

    fun setBoardOptions(c: Context, baseFqbn: String, options: Map<String, String>) {
        if (baseFqbn.isBlank()) return
        val obj = JSONObject()
        options.forEach { (k, v) -> obj.put(k, v) }
        prefs(c).edit().putString("board_opts_$baseFqbn", obj.toString()).apply()
    }

    fun clearBoardOptions(c: Context, baseFqbn: String) {
        if (baseFqbn.isBlank()) return
        prefs(c).edit().remove("board_opts_$baseFqbn").apply()
    }
}

object TextMateSetup {
    private var ready = false

    /** id -> (asset path, isDark) for every theme file found in assets. Nothing is parsed yet. */
    private val found = LinkedHashMap<String, Pair<String, Boolean>>()

    /** ids already parsed and registered in ThemeRegistry. */
    private val loaded = HashSet<String>()

    private val warmStarted = AtomicBoolean(false)

    /** Choices shown in the editor theme menu. Filled by init(). */
    @Volatile
    var available: List<EditorTheme> = emptyList()
        private set

    /**
     * Cheap setup: registers the asset provider, notes which theme files exist and loads the
     * grammar list. No theme is parsed here; themes are parsed one at a time, when first needed
     * (see [prepare]).
     */
    @Synchronized
    fun init(context: Context) {
        if (ready) return
        val assets = context.applicationContext.assets
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))

        found.clear()
        loaded.clear()

        val present = runCatching { assets.list("textmate")?.toSet() }.getOrNull().orEmpty()
        for (spec in EditorThemes.assets) {
            val name = spec.fileNames.firstOrNull { it in present } ?: continue
            found[spec.id] = "textmate/$name" to spec.dark
        }

        available = EditorThemes.menuChoices.filter { choice ->
            when (choice.id) {
                "system" -> found.isNotEmpty()
                "github" -> "github_dark" in found || "github_light" in found
                else -> choice.id in found
            }
        }

        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        ready = true
    }

    /** Parses and registers one theme, once. */
    @Synchronized
    private fun ensureLoaded(id: String) {
        if (id in loaded) return
        val (path, dark) = found[id] ?: return
        val stream = FileProviderRegistry.getInstance().tryGetInputStream(path) ?: return
        val source = IThemeSource.fromInputStream(stream, path, null)
        ThemeRegistry.getInstance().loadTheme(ThemeModel(source, id).apply { isDark = dark })
        loaded += id
    }

    /**
     * Everything slow, meant for a background thread: init, then load only the theme that is
     * about to be shown. Returns the id to pass to [applyTheme].
     */
    @Synchronized
    fun prepare(context: Context, choice: String, systemDark: Boolean): String {
        init(context)
        val id = resolve(choice, systemDark)
        ensureLoaded(id)
        return id
    }

    /**
     * Starts loading in the background so the editor doesn't have to wait for it: theme, grammar
     * list and the C++ grammar (the slowest part). Safe to call from several places.
     */
    fun warmUp(context: Context) {
        if (!warmStarted.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread {
            runCatching {
                val night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                val dark = night == Configuration.UI_MODE_NIGHT_YES
                // Hold the lock for the whole warm-up: the editor's own prepare() then waits for
                // this instead of racing it, and finds everything cached.
                synchronized(this@TextMateSetup) {
                    prepare(app, EditorPrefs.theme(app), dark)
                    val lang = TextMateLanguage.create("source.cpp", true)
                    runCatching { lang.destroy() }
                }
            }
        }.apply {
            name = "textmate-warmup"
            start()
        }
    }

    /**
     * Resolves user choice into a ThemeRegistry id:
     * - "system": Darcula (dark) / IntelliJ IDEA (light)
     * - "github": GitHub Dark (dark) / GitHub Light (light)
     */
    @Synchronized
    fun resolve(choice: String, systemDark: Boolean): String {
        val wanted = when (choice) {
            "system" -> if (systemDark) "darcula" else "intellij"
            "github" -> if (systemDark) "github_dark" else "github_light"
            else -> choice
        }
        return when {
            wanted in found -> wanted
            else -> found.entries.firstOrNull { it.value.second == systemDark }?.key
                ?: found.keys.firstOrNull()
                ?: wanted
        }
    }

    /** Switches the active theme. [prepare] normally loaded it already; if not, it loads here. */
    fun applyTheme(id: String) {
        ensureLoaded(id)
        ThemeRegistry.getInstance().setTheme(id)
    }
}

object ArduinoCompiler {

    data class Result(val success: Boolean, val log: String)

    private data class ResolvedLib(
        val name: String,
        val includeDirs: List<File>,
        val sourceFiles: List<File>
    )

    private data class GccToolchain(
        val gcc: File,
        val gpp: File,
        val ar: File,
        val objcopy: File,
        val size: File?
    )

    private val INCLUDE_REGEX = Regex("""^\s*#\s*include\s*[<"]([^>"]+)[>"]""", RegexOption.MULTILINE)
    private val FUNC_DEF_REGEX = Regex(
        """^(?:template\s*<[^>]+>\s*)?(?:(?:static|inline|virtual|constexpr|extern)\s+)*""" +
            """([A-Za-z_][A-Za-z0-9_:<>*&\s]*?)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^;{}()]*)\)\s*(?:const\s*)?\{"""
    )

    suspend fun compile(projectDir: File, fqbn: String = "arduino:avr:uno"): Result =
        withContext(Dispatchers.IO) {
            val sketch = File(projectDir, "${projectDir.name}.ino")
            if (!sketch.exists()) {
                return@withContext Result(false, "Missing ${sketch.name} in ${projectDir.name}")
            }
            Result(
                false,
                "Pass Context + SelectedBoard to ArduinoCompiler.compile(context, dir, selectedBoard) " +
                    "to resolve boards.txt for $fqbn."
            )
        }

    /**
     * Resolves the user's [SelectedBoard] from `boards.txt` + `platform.txt`, prepares `.ino.cpp`
     * and used libraries, and invokes `avr-gcc` / `avr-g++` / `avr-ar` / `avr-objcopy` if bundled.
     */
    suspend fun compile(
        context: Context,
        projectDir: File,
        board: SelectedBoard,
        selectedOptions: Map<String, String> = EditorPrefs.boardOptions(context, board.fqbn)
    ): Result = withContext(Dispatchers.IO) {
        val sketch = File(projectDir, "${projectDir.name}.ino")
        if (!sketch.exists()) {
            return@withContext Result(false, "Missing ${sketch.name} in ${projectDir.name}")
        }

        val config = BoardManager.resolveBuildConfig(context, board, selectedOptions)
        val archLower = config.architecture.lowercase()
        val isEsp32 = "esp32" in archLower
        val isEsp8266 = "esp8266" in archLower
        val isAvrFamily = archLower == "avr" || archLower == "megaavr"

        val coreDir = config.coreDir
        val variantDir = config.variantDir
        val missingVariant = config.variantName.isNotBlank() && (variantDir == null || !variantDir.isDirectory)
        if (coreDir == null || !coreDir.isDirectory || missingVariant) {
            val coreLabel = config.platformName.ifBlank { "${config.packageName}:${config.architecture}" }
            return@withContext Result(
                false,
                buildString {
                    appendLine("$coreLabel core is not downloaded yet.")
                    appendLine("Resolved FQBN: ${config.effectiveFqbn}")
                    appendLine("Target: arch=${config.architecture}  mcu=${config.mcu.ifBlank { "-" }}  F_CPU=${config.fCpu.ifBlank { "-" }}  debug=${config.codeDebugLevel}  upload=${config.uploadSpeed.ifBlank { "-" }}")
                    appendLine()
                    append("Open Drawer -> Boards and download \"$coreLabel\" so cores/${config.coreName} and variants/${config.variantName.ifBlank { "-" }} are available.")
                }
            )
        }

        val buildRoot = File(projectDir, ".build/${config.boardId}").apply { mkdirs() }
        val sketchBuildDir = File(buildRoot, "sketch").apply { mkdirs() }
        val coreBuildDir = File(buildRoot, "core").apply { mkdirs() }
        val libsBuildDir = File(buildRoot, "libs").apply { mkdirs() }
        val outDir = File(buildRoot, "out").apply { mkdirs() }

        // 1. Preprocess .ino -> .ino.cpp
        val inoCpp = prepareInoCpp(projectDir, sketch, sketchBuildDir)

        // 2. Discover used core & installed libraries from #include directives
        val usedLibs = resolveUsedLibraries(context, projectDir, inoCpp, config.platformDir)

        // 3. Build include dirs + common defines from SelectedBoard + selected board options
        val includeDirs = LinkedHashSet<File>().apply {
            add(sketchBuildDir)
            add(projectDir)
            File(projectDir, "src").takeIf { it.isDirectory }?.let { add(it) }
            add(coreDir)
            if (variantDir != null && variantDir.isDirectory) add(variantDir)
            usedLibs.forEach { lib -> addAll(lib.includeDirs) }
        }.toList()

        val commonDefines = buildList {
            if (config.fCpu.isNotBlank()) add("-DF_CPU=${config.fCpu}")
            add("-DARDUINO=10819")
            if (config.boardDefine.isNotBlank()) add("-DARDUINO_${config.boardDefine}")
            if (config.architecture.isNotBlank()) add("-DARDUINO_ARCH_${config.architecture.uppercase()}")
            if (isEsp32) add("-DESP32=ESP32")
            if (isEsp8266) add("-DESP8266")
            addAll(config.extraFlags)
        }.distinct()
        val includeFlags = includeDirs.map { "-I${it.absolutePath}" }
        val optFlag = "-${config.optimizeLevel}"
        val avrMcu = config.mcu.ifBlank { "atmega328p" }

        fun mergeArchFlags(platformFlags: List<String>, defaults: List<String>): List<String> {
            val cleaned = platformFlags
                .filterNot { it in setOf("-Os", "-O0", "-O1", "-O2", "-O3", "-Og") }
                .filterNot { it.startsWith("-DF_CPU=") || it.startsWith("-DARDUINO=") || it.startsWith("-DARDUINO_ARCH_") }
            val seed = if (cleaned.isNotEmpty()) cleaned else defaults
            return (listOf("-c", "-g", optFlag, "-MMD") + seed + commonDefines + includeFlags).distinct()
        }

        val cBaseFlags = when {
            isAvrFamily -> mergeArchFlags(
                config.platformCFlags,
                listOf("-w", "-std=gnu11", "-ffunction-sections", "-fdata-sections", "-flto", "-fno-fat-lto-objects", "-mmcu=$avrMcu")
            )
            isEsp32 -> mergeArchFlags(
                config.platformCFlags,
                listOf("-w", "-std=gnu17", "-ffunction-sections", "-fdata-sections")
            )
            isEsp8266 -> mergeArchFlags(
                config.platformCFlags,
                listOf("-w", "-std=gnu17", "-mlongcalls", "-ffunction-sections", "-fdata-sections")
            )
            else -> mergeArchFlags(
                config.platformCFlags,
                buildList {
                    addAll(listOf("-w", "-std=gnu11", "-ffunction-sections", "-fdata-sections"))
                    if (config.mcu.isNotBlank() && "cortex" in config.mcu.lowercase()) {
                        add("-mcpu=${config.mcu}")
                        add("-mthumb")
                    }
                }
            )
        }

        val cppBaseFlags = when {
            isAvrFamily -> mergeArchFlags(
                config.platformCppFlags,
                listOf("-w", "-std=gnu++11", "-fpermissive", "-fno-exceptions", "-ffunction-sections", "-fdata-sections", "-fno-threadsafe-statics", "-flto", "-mmcu=$avrMcu")
            )
            isEsp32 -> mergeArchFlags(
                config.platformCppFlags,
                listOf("-w", "-std=gnu++2b", "-fno-exceptions", "-fno-rtti", "-ffunction-sections", "-fdata-sections")
            )
            isEsp8266 -> mergeArchFlags(
                config.platformCppFlags,
                listOf("-w", "-std=gnu++17", "-mlongcalls", "-fno-exceptions", "-fno-rtti", "-ffunction-sections", "-fdata-sections")
            )
            else -> mergeArchFlags(
                config.platformCppFlags,
                buildList {
                    addAll(listOf("-w", "-std=gnu++17", "-fno-exceptions", "-fno-rtti", "-ffunction-sections", "-fdata-sections"))
                    if (config.mcu.isNotBlank() && "cortex" in config.mcu.lowercase()) {
                        add("-mcpu=${config.mcu}")
                        add("-mthumb")
                    }
                }
            )
        }

        val asmBaseFlags = when {
            isAvrFamily -> mergeArchFlags(
                config.platformAsmFlags,
                listOf("-x", "assembler-with-cpp", "-flto", "-mmcu=$avrMcu")
            )
            else -> mergeArchFlags(
                config.platformAsmFlags,
                listOf("-x", "assembler-with-cpp")
            )
        }

        // Save build_plan.json so external / JNI runners can inspect or execute the exact build
        writeBuildPlanJson(
            File(buildRoot, "build_plan.json"),
            config,
            inoCpp,
            includeDirs,
            usedLibs,
            cBaseFlags,
            cppBaseFlags
        )

        val toolchain = findToolchain(context, config)
        if (toolchain == null) {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val toolBinDir = File(context.filesDir, "toolchain/bin")
            val cCmd = config.compilerCCmd.ifBlank { "avr-gcc" }
            val cppCmd = config.compilerCppCmd.ifBlank { "avr-g++" }
            return@withContext Result(
                false,
                buildString {
                    appendLine("$cCmd binaries not bundled yet (board resolution succeeded).")
                    appendLine()
                    appendLine("Board: ${config.boardName}")
                    appendLine("FQBN: ${config.effectiveFqbn}")
                    appendLine("Core: ${config.platformName.ifBlank { config.packageName }} (${config.architecture})")
                    appendLine("MCU: ${config.mcu.ifBlank { "-" }}  Optimize: $optFlag  Toolchain: $cCmd")
                    appendLine("Clock: -DF_CPU=${config.fCpu.ifBlank { "-" }}  Debug: ${config.codeDebugLevel}  Upload Speed: ${config.uploadSpeed.ifBlank { "-" }}")
                    if (config.boardDefine.isNotBlank()) {
                        appendLine("Board macro: -DARDUINO_${config.boardDefine}")
                    }
                    if (config.flashMode.isNotBlank() || config.flashSize.isNotBlank() || config.partitionScheme.isNotBlank()) {
                        appendLine("Flash: ${config.flashMode.ifBlank { "-" }} @ ${config.flashFreq.ifBlank { "-" }}, ${config.flashSize.ifBlank { "-" }}  Partition: ${config.partitionScheme.ifBlank { "-" }}  PSRAM: ${config.psramMode.ifBlank { "disabled" }}")
                    }
                    appendLine("Core path: ${coreDir.absolutePath}")
                    if (variantDir != null) appendLine("Variant path: ${variantDir.absolutePath}")
                    if (config.extraFlags.isNotEmpty()) {
                        appendLine("Extra flags: ${config.extraFlags.joinToString(" ")}")
                    }
                    if (usedLibs.isNotEmpty()) {
                        appendLine("Libraries: ${usedLibs.joinToString { it.name }}")
                    }
                    appendLine("Preprocessed: ${inoCpp.name}")
                    appendLine("Build plan: ${File(buildRoot, "build_plan.json").relativeTo(projectDir).path}")
                    appendLine()
                    appendLine("Sample $cppCmd command:")
                    appendLine("$cppCmd ${cppBaseFlags.joinToString(" ")} ${inoCpp.name} -o ${projectDir.name}.ino.cpp.o")
                    appendLine()
                    append("Place ARM64 binaries in jniLibs/<abi>/ (lib$cCmd.so, lib$cppCmd.so, lib${config.compilerArCmd}.so, lib${config.compilerObjcopyCmd}.so -> $nativeDir) or $toolBinDir.")
                }
            )
        }

        // 4. Compile core -> core.a (cached by FQBN/MCU/F_CPU/board config)
        val log = StringBuilder()
        log.appendLine("Board: ${config.boardName} (${config.effectiveFqbn})")
        log.appendLine("Target: arch=${config.architecture}  mcu=${config.mcu.ifBlank { "-" }}  -DF_CPU=${config.fCpu.ifBlank { "-" }}  $optFlag  debug=${config.codeDebugLevel}  upload=${config.uploadSpeed.ifBlank { "-" }}")

        val envPaths = listOfNotNull(
            toolchain.gcc.parentFile?.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            File(context.filesDir, "toolchain/bin").takeIf { it.isDirectory }?.absolutePath,
            File(context.filesDir, "toolchain/${config.architecture}/bin").takeIf { it.isDirectory }?.absolutePath,
            File(context.filesDir, "toolchain/libexec/gcc/${config.architecture}").takeIf { it.isDirectory }?.absolutePath
        ).distinct()

        val coreStampFile = File(coreBuildDir, "core.stamp")
        val coreStamp = "${config.effectiveFqbn}|${config.mcu}|${config.fCpu}|${config.optimizeLevel}|${config.codeDebugLevel}|${config.extraFlags.joinToString()}"
        val coreClean = !coreStampFile.exists() || coreStampFile.readText() != coreStamp
        if (coreClean) {
            coreBuildDir.listFiles()?.forEach { it.deleteRecursively() }
            coreBuildDir.mkdirs()
        }

        val coreArchive = File(coreBuildDir, "core.a")
        val coreSources = collectSources(coreDir)
        var coreRebuilt = false
        val coreObjs = ArrayList<File>()

        for (src in coreSources) {
            val rel = src.relativeTo(coreDir).path.replace(File.separatorChar, '_')
            val obj = File(coreBuildDir, "$rel.o")
            coreObjs += obj
            if (obj.exists() && obj.lastModified() >= src.lastModified() && !coreClean) continue
            coreRebuilt = true
            val res = compileSourceFile(toolchain, src, obj, cBaseFlags, cppBaseFlags, asmBaseFlags, projectDir, envPaths)
            if (res.first != 0) {
                log.appendLine("Core compile failed: ${src.name}")
                log.append(res.second)
                return@withContext Result(false, log.toString())
            }
        }
        if (coreRebuilt || !coreArchive.exists()) {
            coreArchive.delete()
            val arCmd = listOf(toolchain.ar.absolutePath, "rcs", coreArchive.absolutePath) +
                coreObjs.map { it.absolutePath }
            val arRes = runProcess(arCmd, projectDir, envPaths)
            if (arRes.first != 0) {
                log.appendLine("Archiving core.a failed:")
                log.append(arRes.second)
                return@withContext Result(false, log.toString())
            }
            coreStampFile.writeText(coreStamp)
        }

        // 5. Compile used libraries
        val objectFiles = ArrayList<File>()
        for (lib in usedLibs) {
            val libOutDir = File(libsBuildDir, lib.name.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { mkdirs() }
            for (src in lib.sourceFiles) {
                val obj = File(libOutDir, "${src.name}_${src.absolutePath.hashCode().toUInt()}.o")
                objectFiles += obj
                if (obj.exists() && obj.lastModified() >= src.lastModified() && !coreClean) continue
                val res = compileSourceFile(toolchain, src, obj, cBaseFlags, cppBaseFlags, asmBaseFlags, projectDir, envPaths)
                if (res.first != 0) {
                    log.appendLine("Library compile failed (${lib.name}/${src.name}):")
                    log.append(res.second)
                    return@withContext Result(false, log.toString())
                }
            }
        }

        // 6. Compile sketch .ino.cpp + project .c/.cpp/.S sources
        val sketchSources = buildList {
            add(inoCpp)
            addAll(collectProjectSources(projectDir))
        }
        for (src in sketchSources) {
            val obj = File(sketchBuildDir, "${src.name}_${src.absolutePath.hashCode().toUInt()}.o")
            objectFiles += obj
            val res = compileSourceFile(toolchain, src, obj, cBaseFlags, cppBaseFlags, asmBaseFlags, projectDir, envPaths)
            if (res.first != 0) {
                log.appendLine("Compile error in ${src.name}:")
                log.append(res.second)
                return@withContext Result(false, log.toString())
            }
        }

        // 7. Link sketch.elf
        val elfFile = File(outDir, "${projectDir.name}.ino.elf")
        val linkCmd = buildList {
            add(toolchain.gcc.absolutePath)
            if (config.platformLinkFlags.isNotEmpty()) {
                addAll(config.platformLinkFlags)
            } else if (isAvrFamily) {
                addAll(listOf("-w", optFlag, "-g", "-flto", "-fuse-linker-plugin", "-Wl,--gc-sections", "-mmcu=$avrMcu"))
            } else {
                addAll(listOf("-w", optFlag, "-g", "-Wl,--gc-sections"))
                if (config.mcu.isNotBlank() && "cortex" in config.mcu.lowercase()) {
                    add("-mcpu=${config.mcu}")
                    add("-mthumb")
                }
            }
            add("-o")
            add(elfFile.absolutePath)
            objectFiles.forEach { add(it.absolutePath) }
            add(coreArchive.absolutePath)
            add("-L${outDir.absolutePath}")
            add("-lm")
        }
        val linkRes = runProcess(linkCmd, projectDir, envPaths)
        if (linkRes.first != 0) {
            log.appendLine("Link failed:")
            log.append(linkRes.second)
            return@withContext Result(false, log.toString())
        }

        // 8. Produce .hex / .bin firmware outputs
        val hexFile = File(outDir, "${projectDir.name}.ino.hex")
        val binFile = File(outDir, "${projectDir.name}.ino.bin")
        if (isAvrFamily) {
            val eepFile = File(outDir, "${projectDir.name}.ino.eep")
            runProcess(
                listOf(
                    toolchain.objcopy.absolutePath,
                    "-O", "ihex", "-j", ".eeprom",
                    "--set-section-flags=.eeprom=alloc,load",
                    "--no-change-warnings", "--change-section-lma", ".eeprom=0",
                    elfFile.absolutePath, eepFile.absolutePath
                ),
                projectDir,
                envPaths
            )
            val hexRes = runProcess(
                listOf(
                    toolchain.objcopy.absolutePath,
                    "-O", "ihex", "-R", ".eeprom",
                    elfFile.absolutePath, hexFile.absolutePath
                ),
                projectDir,
                envPaths
            )
            if (hexRes.first != 0) {
                log.appendLine("${toolchain.objcopy.name} failed:")
                log.append(hexRes.second)
                return@withContext Result(false, log.toString())
            }
        } else {
            runProcess(
                listOf(toolchain.objcopy.absolutePath, "-O", "ihex", elfFile.absolutePath, hexFile.absolutePath),
                projectDir,
                envPaths
            )
            val binRes = runProcess(
                listOf(toolchain.objcopy.absolutePath, "-O", "binary", elfFile.absolutePath, binFile.absolutePath),
                projectDir,
                envPaths
            )
            if (binRes.first != 0 && !hexFile.exists()) {
                log.appendLine("${toolchain.objcopy.name} failed:")
                log.append(binRes.second)
                return@withContext Result(false, log.toString())
            }
        }

        // 9. Optional size summary
        toolchain.size?.let { sizeBin ->
            val sizeRes = runProcess(listOf(sizeBin.absolutePath, "-A", elfFile.absolutePath), projectDir, envPaths)
            if (sizeRes.first == 0) {
                log.appendLine()
                log.appendLine(formatSizeSummary(sizeRes.second, config))
            }
        }

        val outArtifact = if (binFile.exists() && !isAvrFamily) binFile else hexFile
        log.appendLine("Output: ${outArtifact.relativeTo(projectDir).path} (${outArtifact.length()} bytes)")
        Result(true, log.toString().trim())
    }

    private fun prepareInoCpp(projectDir: File, mainIno: File, sketchBuildDir: File): File {
        val otherInos = projectDir.listFiles { f ->
            f.isFile && f.extension.equals("ino", ignoreCase = true) && f.absolutePath != mainIno.absolutePath
        }?.sortedBy { it.name.lowercase() }.orEmpty()

        val combined = buildString {
            appendLine("#line 1 \"${mainIno.name}\"")
            appendLine(mainIno.readText())
            for (extra in otherInos) {
                appendLine("#line 1 \"${extra.name}\"")
                appendLine(extra.readText())
            }
        }

        val hasArduinoInclude = Regex("""^\s*#\s*include\s*[<"]Arduino\.h[>"]""", RegexOption.MULTILINE)
            .containsMatchIn(combined)
        val prototypes = extractTopLevelPrototypes(combined)

        val cppContent = buildString {
            if (!hasArduinoInclude) {
                appendLine("#include <Arduino.h>")
            }
            if (prototypes.isNotEmpty()) {
                prototypes.forEach { appendLine(it) }
            }
            append(combined)
        }

        val out = File(sketchBuildDir, "${mainIno.name}.cpp")
        if (!out.exists() || out.readText() != cppContent) {
            out.writeText(cppContent)
        }
        return out
    }

    private fun extractTopLevelPrototypes(code: String): List<String> {
        val out = LinkedHashSet<String>()
        val lines = code.lines()
        var depth = 0
        var inBlockComment = false

        for (raw in lines) {
            val trimmed = raw.trim()
            if (depth == 0 && !inBlockComment && trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val match = FUNC_DEF_REGEX.find(trimmed)
                if (match != null) {
                    val ret = match.groupValues[1].trim()
                    val name = match.groupValues[2].trim()
                    val params = match.groupValues[3].trim()
                    val retWords = ret.split(Regex("\\s+"))
                    val blocked = setOf("if", "for", "while", "switch", "else", "return", "class", "struct", "typedef", "using", "namespace")
                    if (ret.isNotEmpty() &&
                        retWords.none { it in blocked } &&
                        name !in setOf("if", "for", "while", "switch", "catch")
                    ) {
                        out += "$ret $name($params);"
                    }
                }
            }

            // Track brace depth conservatively outside comments & strings
            var i = 0
            var inString = false
            var inChar = false
            var esc = false
            while (i < raw.length) {
                val ch = raw[i]
                val next = raw.getOrNull(i + 1)
                if (inBlockComment) {
                    if (ch == '*' && next == '/') {
                        inBlockComment = false
                        i += 2
                        continue
                    }
                    i++
                    continue
                }
                if (esc) {
                    esc = false
                    i++
                    continue
                }
                if (inString) {
                    if (ch == '\\') esc = true else if (ch == '"') inString = false
                    i++
                    continue
                }
                if (inChar) {
                    if (ch == '\\') esc = true else if (ch == '\'') inChar = false
                    i++
                    continue
                }
                if (ch == '/' && next == '/') break
                if (ch == '/' && next == '*') {
                    inBlockComment = true
                    i += 2
                    continue
                }
                when (ch) {
                    '"' -> inString = true
                    '\'' -> inChar = true
                    '{' -> depth++
                    '}' -> if (depth > 0) depth--
                }
                i++
            }
        }
        return out.toList()
    }

    private fun resolveUsedLibraries(
        context: Context,
        projectDir: File,
        inoCpp: File,
        platformDir: File?
    ): List<ResolvedLib> {
        val candidates = ArrayList<Pair<String, File>>()
        platformDir?.let { pDir ->
            File(pDir, "libraries").listFiles { f -> f.isDirectory }?.forEach { libDir ->
                candidates += libDir.name to libDir
            }
        }
        LibraryManager.librariesDir(context).listFiles { f -> f.isDirectory }?.forEach { libDir ->
            candidates += libDir.name to libDir
        }
        if (candidates.isEmpty()) return emptyList()

        // Index top-level header filename -> candidate library
        val headerToLib = LinkedHashMap<String, Pair<String, File>>()
        for (pair in candidates) {
            val libDir = pair.second
            val srcDir = File(libDir, "src").takeIf { it.isDirectory } ?: libDir
            srcDir.listFiles { f -> f.isFile && (f.extension.equals("h", true) || f.extension.equals("hpp", true)) }
                ?.forEach { h -> headerToLib.putIfAbsent(h.name, pair) }
        }

        val pendingIncludes = ArrayDeque<String>()
        val scanFiles = collectProjectHeaderAndSourceFiles(projectDir) + inoCpp
        for (file in scanFiles) {
            runCatching {
                INCLUDE_REGEX.findAll(file.readText()).forEach { m ->
                    pendingIncludes.addLast(m.groupValues[1].substringAfterLast('/'))
                }
            }
        }

        val resolved = LinkedHashMap<String, ResolvedLib>()
        val seenHeaders = HashSet<String>()
        while (pendingIncludes.isNotEmpty()) {
            val header = pendingIncludes.removeFirst()
            if (!seenHeaders.add(header)) continue
            val pair = headerToLib[header] ?: continue
            val (name, folder) = pair
            if (name in resolved) continue

            val srcRoot = File(folder, "src").takeIf { it.isDirectory } ?: folder
            val incDirs = buildList {
                add(srcRoot)
                val util = File(srcRoot, "utility")
                if (util.isDirectory) add(util)
            }
            val srcs = collectSources(srcRoot, skipSubdirs = setOf("examples", "extras", "test", "tests"))
            resolved[name] = ResolvedLib(name, incDirs, srcs)

            // Scan headers inside this library for transitive dependencies (e.g. Wire, SPI)
            srcRoot.walkTopDown()
                .filter { it.isFile && (it.extension.equals("h", true) || it.extension.equals("hpp", true)) }
                .forEach { libHeader ->
                    runCatching {
                        INCLUDE_REGEX.findAll(libHeader.readText()).forEach { m ->
                            pendingIncludes.addLast(m.groupValues[1].substringAfterLast('/'))
                        }
                    }
                }
        }
        return resolved.values.toList()
    }

    private fun collectProjectHeaderAndSourceFiles(projectDir: File): List<File> =
        projectDir.walkTopDown()
            .onEnter { dir -> !dir.name.startsWith(".") }
            .filter {
                it.isFile && it.extension.lowercase() in setOf("ino", "c", "cpp", "cc", "h", "hpp", "s")
            }
            .toList()

    private fun collectProjectSources(projectDir: File): List<File> =
        projectDir.walkTopDown()
            .onEnter { dir -> !dir.name.startsWith(".") }
            .filter {
                it.isFile && it.extension.lowercase() in setOf("c", "cpp", "cc", "s")
            }
            .toList()

    private fun collectSources(dir: File, skipSubdirs: Set<String> = emptySet()): List<File> =
        dir.walkTopDown()
            .onEnter { sub -> sub == dir || sub.name.lowercase() !in skipSubdirs }
            .filter { it.isFile && it.extension.lowercase() in setOf("c", "cpp", "cc", "s") }
            .sortedBy { it.absolutePath }
            .toList()

    private fun findToolchain(context: Context, config: BoardBuildConfig): GccToolchain? {
        fun locate(vararg names: String): File? {
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val binDir = File(context.filesDir, "toolchain/bin")
            val archBinDir = File(context.filesDir, "toolchain/${config.architecture}/bin")
            for (n in names.filter { it.isNotBlank() }) {
                val asSo = File(nativeDir, "lib${n}.so")
                if (asSo.isFile && asSo.canExecute()) return asSo
                for (dir in listOf(binDir, archBinDir)) {
                    val candidate = File(dir, n)
                    if (candidate.isFile) {
                        if (!candidate.canExecute()) runCatching { candidate.setExecutable(true) }
                        if (candidate.canExecute()) return candidate
                    }
                }
            }
            return null
        }

        val cCmd = config.compilerCCmd.ifBlank { "avr-gcc" }
        val cppCmd = config.compilerCppCmd.ifBlank { cCmd.replace("-gcc", "-g++") }
        val prefix = cCmd.removeSuffix("-gcc")
        val arCmd = config.compilerArCmd.ifBlank { "$prefix-ar" }
        val objcopyCmd = config.compilerObjcopyCmd.ifBlank { "$prefix-objcopy" }
        val sizeCmd = config.compilerSizeCmd.ifBlank { "$prefix-size" }

        val gcc = locate(cCmd) ?: return null
        val gpp = locate(cppCmd) ?: return null
        val ar = locate(arCmd, "$prefix-gcc-ar", "$prefix-ar") ?: return null
        val objcopy = locate(objcopyCmd) ?: return null
        val size = locate(sizeCmd)
        return GccToolchain(gcc, gpp, ar, objcopy, size)
    }

    private fun compileSourceFile(
        toolchain: GccToolchain,
        src: File,
        obj: File,
        cFlags: List<String>,
        cppFlags: List<String>,
        asmFlags: List<String>,
        workDir: File,
        envPaths: List<String>
    ): Pair<Int, String> {
        val (compiler, flags) = when (src.extension.lowercase()) {
            "c" -> toolchain.gcc to cFlags
            "s" -> toolchain.gcc to asmFlags
            else -> toolchain.gpp to cppFlags
        }
        val cmd = buildList {
            add(compiler.absolutePath)
            addAll(flags)
            add(src.absolutePath)
            add("-o")
            add(obj.absolutePath)
        }
        return runProcess(cmd, workDir, envPaths)
    }

    private fun runProcess(
        command: List<String>,
        workDir: File,
        extraPaths: List<String>
    ): Pair<Int, String> {
        return runCatching {
            val pb = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
            val env = pb.environment()
            val curPath = env["PATH"].orEmpty()
            val joinedPaths = (extraPaths + curPath.split(':')).filter { it.isNotBlank() }.distinct().joinToString(":")
            env["PATH"] = joinedPaths
            val curLd = env["LD_LIBRARY_PATH"].orEmpty()
            env["LD_LIBRARY_PATH"] = (extraPaths + curLd.split(':')).filter { it.isNotBlank() }.distinct().joinToString(":")

            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            if (!proc.waitFor(120, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return -1 to "Timed out running ${File(command.first()).name}"
            }
            proc.exitValue() to output
        }.getOrElse { err ->
            -1 to (err.message ?: "Failed to start ${command.firstOrNull()}")
        }
    }

    private fun formatSizeSummary(raw: String, config: BoardBuildConfig): String {
        var textBytes = 0L
        var dataBytes = 0L
        var bssBytes = 0L
        raw.lines().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val size = parts[1].toLongOrNull() ?: return@forEach
                when (parts[0]) {
                    ".text" -> textBytes = size
                    ".data" -> dataBytes = size
                    ".bss" -> bssBytes = size
                }
            }
        }
        val flashUsed = textBytes + dataBytes
        val ramUsed = dataBytes + bssBytes
        if (flashUsed == 0L && ramUsed == 0L) return raw.trim()
        val flashPct = if (config.uploadMaximumSize > 0) (flashUsed * 100 / config.uploadMaximumSize) else 0
        val ramPct = if (config.uploadMaximumDataSize > 0) (ramUsed * 100 / config.uploadMaximumDataSize) else 0
        return buildString {
            appendLine("Sketch uses $flashUsed bytes ($flashPct%) of program storage space. Maximum is ${config.uploadMaximumSize} bytes.")
            append("Global variables use $ramUsed bytes ($ramPct%) of dynamic memory, leaving ${(config.uploadMaximumDataSize - ramUsed).coerceAtLeast(0)} bytes for local variables.")
        }
    }

    private fun writeBuildPlanJson(
        outFile: File,
        config: BoardBuildConfig,
        inoCpp: File,
        includeDirs: List<File>,
        usedLibs: List<ResolvedLib>,
        cFlags: List<String>,
        cppFlags: List<String>
    ) {
        runCatching {
            val optsObj = JSONObject().apply {
                config.appliedOptions.forEach { (k, v) -> put(k, v) }
            }
            val json = JSONObject().apply {
                put("fqbn", config.fqbn)
                put("effectiveFqbn", config.effectiveFqbn)
                put("boardName", config.boardName)
                put("architecture", config.architecture)
                put("mcu", config.mcu)
                put("fCpu", config.fCpu)
                put("boardDefine", config.boardDefine)
                put("uploadSpeed", config.uploadSpeed)
                put("codeDebugLevel", config.codeDebugLevel)
                put("flashMode", config.flashMode)
                put("flashFreq", config.flashFreq)
                put("flashSize", config.flashSize)
                put("partitionScheme", config.partitionScheme)
                put("psramMode", config.psramMode)
                put("optimizeLevel", config.optimizeLevel)
                put("boardOptions", optsObj)
                put("coreDir", config.coreDir?.absolutePath ?: "")
                put("variantDir", config.variantDir?.absolutePath ?: "")
                put("sketchCpp", inoCpp.absolutePath)
                put("libraries", JSONArray(usedLibs.map { it.name }))
                put("includeDirs", JSONArray(includeDirs.map { it.absolutePath }))
                put("cFlags", JSONArray(cFlags))
                put("cppFlags", JSONArray(cppFlags))
            }
            outFile.writeText(json.toString(2))
        }
    }
}
