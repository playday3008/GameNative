package app.gamenative.utils.installscript

import app.gamenative.data.AppInfo
import app.gamenative.data.SteamApp
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import java.io.File
import timber.log.Timber

object InstallScriptExecutor {

    private const val CLAIMED_OS_TYPE = "10"

    data class RunProcessCommand(
        val executable: String,
        val hasRunKey: String?,
    )

    fun collectScripts(
        steamApp: SteamApp,
        appInfo: AppInfo,
        gameDir: File,
        installDir: String,
        language: String,
    ): List<InstallScript> {
        val downloadedDepotIds = appInfo.downloadedDepots.toSet()
        return steamApp.depots
            .filter { (depotId, depot) ->
                depotId in downloadedDepotIds && depot.installScript.isNotEmpty()
            }
            .mapNotNull { (_, depot) ->
                val scriptFile = File(gameDir, depot.installScript)
                if (!scriptFile.exists()) {
                    Timber.w("InstallScript file not found: ${scriptFile.absolutePath}")
                    return@mapNotNull null
                }
                InstallScriptParser.parse(scriptFile, installDir, language)
            }
    }

    fun applyRegistryKeys(container: Container, scripts: List<InstallScript>, language: String) {
        val systemRegFile = File(container.rootDir, ".wine/system.reg")
        val userRegFile = File(container.rootDir, ".wine/user.reg")

        val systemActions = mutableListOf<Pair<String, RegistryValues>>()
        val userActions = mutableListOf<Pair<String, RegistryValues>>()

        for (script in scripts) {
            for (action in script.registryActions) {
                val mergedValues = mergeWithLanguage(action, language)
                val strippedKey = stripHivePrefix(action.keyPath)

                if (action.keyPath.startsWith("HKLM", ignoreCase = true) ||
                    action.keyPath.startsWith("HKEY_LOCAL_MACHINE", ignoreCase = true)
                ) {
                    systemActions.add(strippedKey to mergedValues)
                } else {
                    userActions.add(strippedKey to mergedValues)
                }
            }
        }

        if (systemActions.isNotEmpty() && systemRegFile.exists()) {
            writeRegistryValues(systemRegFile, systemActions)
        }
        if (userActions.isNotEmpty() && userRegFile.exists()) {
            writeRegistryValues(userRegFile, userActions)
        }
    }

    fun getRunProcessCommands(
        container: Container,
        scripts: List<InstallScript>,
        screenInfo: String,
        is64Bit: Boolean,
    ): List<RunProcessCommand> {
        val systemRegFile = File(container.rootDir, ".wine/system.reg")
        val commands = mutableListOf<RunProcessCommand>()

        for (script in scripts) {
            for (action in script.runProcessActions) {
                if (!matchesOS(action.requirementOS, is64Bit)) continue
                if (hasAlreadyRun(systemRegFile, action)) continue

                val cmdLine = if (action.command.isNotEmpty()) {
                    "${action.process} ${action.command}"
                } else {
                    action.process
                }
                val wrapped = wrapAsGuestExecutable(cmdLine, screenInfo)

                val effectiveHasRunKey = action.hasRunKey
                    ?: "Software\\GameNative\\InstallScript\\${script.sourcePath.hashCode()}\\${action.name}"

                commands.add(RunProcessCommand(wrapped, effectiveHasRunKey))
            }
        }
        return commands
    }

    fun markRunProcessComplete(container: Container, hasRunKey: String) {
        val systemRegFile = File(container.rootDir, ".wine/system.reg")
        if (!systemRegFile.exists()) return
        try {
            WineRegistryEditor(systemRegFile).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                val keyPath = hasRunKey.substringBeforeLast("\\")
                val valueName = hasRunKey.substringAfterLast("\\")
                editor.setDwordValue(stripHivePrefix(keyPath), valueName, 1)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to mark run process complete: $hasRunKey")
        }
    }

    internal fun mergeWithLanguage(action: RegistryAction, language: String): RegistryValues {
        val langOverride = action.languageOverrides[language.lowercase()]
            ?: return action.values
        return RegistryValues(
            strings = action.values.strings + langOverride.strings,
            dwords = action.values.dwords + langOverride.dwords,
        )
    }

    internal fun matchesOS(requirement: OSRequirement?, is64Bit: Boolean): Boolean {
        if (requirement == null) return true
        if (requirement.is64BitWindows != null && requirement.is64BitWindows != is64Bit) return false
        if (requirement.osType != null && requirement.osType != CLAIMED_OS_TYPE) return false
        return true
    }

    internal fun stripHivePrefix(keyPath: String): String {
        return keyPath
            .removePrefix("HKLM\\")
            .removePrefix("HKEY_LOCAL_MACHINE\\")
            .removePrefix("HKCU\\")
            .removePrefix("HKEY_CURRENT_USER\\")
    }

    private fun hasAlreadyRun(systemRegFile: File, action: RunProcessAction): Boolean {
        if (action.hasRunKey == null) return false
        if (!systemRegFile.exists()) return false
        return try {
            WineRegistryEditor(systemRegFile).use { editor ->
                val keyPath = action.hasRunKey.substringBeforeLast("\\")
                val valueName = action.hasRunKey.substringAfterLast("\\")
                val currentValue = editor.getDwordValue(
                    stripHivePrefix(keyPath), valueName, 0,
                ) ?: 0
                currentValue >= maxOf(1, action.minimumHasRunValue)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun writeRegistryValues(regFile: File, actions: List<Pair<String, RegistryValues>>) {
        try {
            WineRegistryEditor(regFile).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                for ((key, values) in actions) {
                    for ((name, value) in values.strings) {
                        val regName = if (name == "(Default)") null else name
                        editor.setStringValue(key, regName, value)
                    }
                    for ((name, value) in values.dwords) {
                        val regName = if (name == "(Default)") null else name
                        editor.setDwordValue(key, regName, value.toInt())
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to write InstallScript registry values")
        }
    }

    private fun wrapAsGuestExecutable(cmdChain: String, screenInfo: String): String {
        val wrapped = "winhandler.exe cmd /c \"$cmdChain & taskkill /F /IM explorer.exe & wineserver -k\""
        return "wine explorer /desktop=shell,$screenInfo $wrapped"
    }
}
