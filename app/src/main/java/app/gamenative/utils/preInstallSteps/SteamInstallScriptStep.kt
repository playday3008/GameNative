package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import app.gamenative.service.SteamService
import com.winlator.container.Container
import `in`.dragonbra.javasteam.enums.EDepotFileFlag
import `in`.dragonbra.javasteam.types.DepotManifest
import `in`.dragonbra.javasteam.types.KeyValue
import timber.log.Timber
import java.io.File

object SteamInstallScriptStep : PreInstallStep {
    private const val TAG = "SteamInstallScriptStep"
    private const val DEPOT_DIR = ".DepotDownloader"

    override val marker: Marker = Marker.STEAM_INSTALL_SCRIPT_RUN

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        return gameSource == GameSource.STEAM &&
            !MarkerUtils.hasMarker(gameDirPath, Marker.STEAM_INSTALL_SCRIPT_RUN)
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val scriptFiles = findInstallScriptFiles(appId, gameDir)
        if (scriptFiles.isEmpty()) {
            Timber.tag(TAG).i("No install script files found for appId=%s", appId)
            return null
        }

        val allCommands = mutableListOf<String>()
        for (scriptFile in scriptFiles) {
            try {
                val content = scriptFile.readText()
                val kv = KeyValue.loadFromString(content) ?: continue
                allCommands.addAll(InstallScriptInterpreter.buildAllCommands(kv))
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to parse install script: %s", scriptFile.name)
            }
        }

        return if (allCommands.isEmpty()) null else allCommands.joinToString(" & ")
    }

    private fun findInstallScriptFiles(appId: String, gameDir: File): List<File> {
        val gameId = appId.toIntOrNull()
        if (gameId != null) {
            val steamApp = SteamService.getAppInfoOf(gameId)
            if (steamApp?.installScriptOverride == true && steamApp.installScript.isNotEmpty()) {
                Timber.tag(TAG).i("Using override install script: %s", steamApp.installScript)
                val overrideFile = File(gameDir, steamApp.installScript.replace('\\', '/'))
                return if (overrideFile.isFile) listOf(overrideFile) else emptyList()
            }
        }

        return findInstallScriptFilesFromManifests(gameDir)
    }

    private fun findInstallScriptFilesFromManifests(gameDir: File): List<File> {
        val depotDir = File(gameDir, DEPOT_DIR)
        if (!depotDir.isDirectory) return emptyList()

        val manifestFiles = depotDir.listFiles { _, name ->
            name.endsWith(".manifest")
        } ?: return emptyList()

        val scriptFileNames = mutableSetOf<String>()

        for (manifestFile in manifestFiles) {
            try {
                val manifest = DepotManifest.loadFromFile(manifestFile.absolutePath)
                    ?: continue
                manifest.files
                    ?.filter { fileData ->
                        fileData.flags?.contains(EDepotFileFlag.InstallScript) == true
                    }
                    ?.forEach { fileData ->
                        scriptFileNames.add(fileData.fileName)
                    }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to load manifest: %s", manifestFile.name)
            }
        }

        return scriptFileNames.mapNotNull { fileName ->
            val hostPath = File(gameDir, fileName.replace('\\', '/'))
            if (hostPath.isFile) hostPath else null
        }
    }
}
