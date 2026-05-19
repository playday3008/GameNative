package app.gamenative.utils

import `in`.dragonbra.javasteam.types.KeyValue
import timber.log.Timber

object InstallScriptInterpreter {
    private const val TAG = "InstallScriptInterpreter"

    fun buildAllCommands(kv: KeyValue): List<String> {
        return buildRegistryCommands(kv) + buildRunProcessCommands(kv)
    }

    fun expandEnvVars(str: String, wineUser: String = "steamuser"): String {
        var result = str
        val replacements = mapOf(
            "%INSTALLDIR%" to "A:\\",
            "%ROOTDRIVE%" to "C",
            "%APPDATA%" to "C:\\users\\$wineUser\\AppData\\Roaming",
            "%USER_MYDOCS%" to "C:\\users\\$wineUser\\Documents",
            "%COMMON_MYDOCS%" to "C:\\users\\Public\\Documents",
            "%LOCALAPPDATA%" to "C:\\users\\$wineUser\\AppData\\Local",
            "%WinDir%" to "C:\\windows",
            "%STEAMPATH%" to "C:\\Program Files (x86)\\Steam",
        )
        for ((variable, value) in replacements) {
            result = result.replace(variable, value, ignoreCase = true)
        }
        return result
    }

    fun buildRegistryCommands(kv: KeyValue): List<String> {
        val registry = kv["Registry"]
        if (registry.children.isEmpty()) return emptyList()

        val commands = mutableListOf<String>()

        for (pathEntry in registry.children) {
            val regPath = pathEntry.name ?: continue

            for (stringEntry in pathEntry["string"].children) {
                val valueName = stringEntry.name ?: continue
                val expandedValue = expandEnvVars(stringEntry.value ?: "")
                commands.add(
                    if (valueName == "(Default)") {
                        "wine reg add \"$regPath\" /ve /t REG_SZ /d \"$expandedValue\" /f"
                    } else {
                        "wine reg add \"$regPath\" /v \"$valueName\" /t REG_SZ /d \"$expandedValue\" /f"
                    }
                )
            }

            for (dwordEntry in pathEntry["dword"].children) {
                val valueName = dwordEntry.name ?: continue
                val value = dwordEntry.value ?: "0"
                commands.add(
                    if (valueName == "(Default)") {
                        "wine reg add \"$regPath\" /ve /t REG_DWORD /d $value /f"
                    } else {
                        "wine reg add \"$regPath\" /v \"$valueName\" /t REG_DWORD /d $value /f"
                    }
                )
            }
        }

        return commands
    }

    fun buildRunProcessCommands(kv: KeyValue): List<String> {
        val runProcess = kv["Run Process"]
        if (runProcess.children.isEmpty()) return emptyList()

        val commands = mutableListOf<String>()

        for (entry in runProcess.children) {
            val process = expandEnvVars(entry["process 1"].value ?: continue)
            val command = expandEnvVars(entry["command 1"].value ?: "")
            val hasRunKey = entry["HasRunKey"].value?.takeIf { it.isNotEmpty() }

            if (hasRunKey != null) {
                val lastSep = hasRunKey.lastIndexOf('\\')
                val parentKey: String
                val valueName: String
                if (lastSep >= 0) {
                    parentKey = hasRunKey.substring(0, lastSep)
                    valueName = hasRunKey.substring(lastSep + 1)
                } else {
                    parentKey = hasRunKey
                    valueName = ""
                }

                commands.add(
                    if (valueName.isEmpty()) {
                        "(wine reg query \"$parentKey\" /ve 2>nul | find \"0x\" >nul) || (\"$process\" $command && wine reg add \"$parentKey\" /ve /t REG_DWORD /d 1 /f)"
                    } else {
                        "(wine reg query \"$parentKey\" /v \"$valueName\" 2>nul | find \"0x\" >nul) || (\"$process\" $command && wine reg add \"$parentKey\" /v \"$valueName\" /t REG_DWORD /d 1 /f)"
                    }
                )
            } else {
                val cmdPart = if (command.isNotEmpty()) " $command" else ""
                commands.add("\"$process\"$cmdPart")
            }
        }

        return commands
    }

    fun buildFirewallCommands(kv: KeyValue): List<String> = emptyList()
}
