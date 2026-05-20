package app.gamenative.utils.installscript

import app.gamenative.data.AppInfo
import app.gamenative.data.DepotInfo
import app.gamenative.data.SteamApp
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import app.gamenative.service.SteamService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.EnumSet
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class InstallScriptExecutorTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDirectory(prefix = "installscript-executor-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun makeDepotInfo(depotId: Int, installScript: String = ""): DepotInfo {
        return DepotInfo(
            depotId = depotId,
            dlcAppId = SteamService.INVALID_APP_ID,
            depotFromApp = SteamService.INVALID_APP_ID,
            sharedInstall = false,
            osList = EnumSet.of(OS.windows),
            osArch = OSArch.Arch32,
            manifests = emptyMap(),
            encryptedManifests = emptyMap(),
            installScript = installScript,
        )
    }

    private fun makeSteamApp(depots: Map<Int, DepotInfo>): SteamApp {
        return SteamApp(id = 12345, depots = depots)
    }

    private fun makeAppInfo(downloadedDepots: List<Int>): AppInfo {
        return AppInfo(id = 12345, downloadedDepots = downloadedDepots)
    }

    private fun writeVdfScript(fileName: String): File {
        val vdf = """
            "InstallScript"
            {
                "Registry"
                {
                    "HKLM\Software\Test\App"
                    {
                        "String"
                        {
                            "InstallPath"    "%INSTALLDIR%"
                        }
                    }
                }
            }
        """.trimIndent()
        val file = File(tempDir, fileName)
        file.writeText(vdf)
        return file
    }

    // --- collectScripts tests ---

    @Test
    fun collectScripts_returnsScriptsFromDownloadedDepots() {
        val scriptFileName = "installscript.vdf"
        writeVdfScript(scriptFileName)

        val depotId = 101
        val depots = mapOf(depotId to makeDepotInfo(depotId, scriptFileName))
        val steamApp = makeSteamApp(depots)
        val appInfo = makeAppInfo(listOf(depotId))

        val scripts = InstallScriptExecutor.collectScripts(
            steamApp = steamApp,
            appInfo = appInfo,
            gameDir = tempDir,
            installDir = "C:\\Games\\TestGame",
            language = "english",
        )

        assertEquals(1, scripts.size)
        assertTrue(scripts[0].registryActions.isNotEmpty())
    }

    @Test
    fun collectScripts_skipsDepotsNotDownloaded() {
        val scriptFileName = "installscript.vdf"
        writeVdfScript(scriptFileName)

        val depotId = 101
        val depots = mapOf(depotId to makeDepotInfo(depotId, scriptFileName))
        val steamApp = makeSteamApp(depots)
        val appInfo = makeAppInfo(emptyList()) // not downloaded

        val scripts = InstallScriptExecutor.collectScripts(
            steamApp = steamApp,
            appInfo = appInfo,
            gameDir = tempDir,
            installDir = "C:\\Games\\TestGame",
            language = "english",
        )

        assertTrue(scripts.isEmpty())
    }

    // --- mergeWithLanguage tests ---

    @Test
    fun mergeWithLanguage_appliesOverrides() {
        val action = RegistryAction(
            keyPath = "HKLM\\Software\\Test",
            values = RegistryValues(
                strings = mapOf("BaseName" to "BaseValue", "OtherName" to "OtherValue"),
                dwords = mapOf("BaseCount" to 1L),
            ),
            languageOverrides = mapOf(
                "french" to RegistryValues(
                    strings = mapOf("BaseName" to "FrenchValue"),
                    dwords = mapOf("FrenchCount" to 42L),
                ),
            ),
        )

        val merged = InstallScriptExecutor.mergeWithLanguage(action, "french")

        assertEquals("FrenchValue", merged.strings["BaseName"])
        assertEquals("OtherValue", merged.strings["OtherName"])
        assertEquals(1L, merged.dwords["BaseCount"])
        assertEquals(42L, merged.dwords["FrenchCount"])
    }

    @Test
    fun mergeWithLanguage_returnsBaseWhenNoOverride() {
        val action = RegistryAction(
            keyPath = "HKLM\\Software\\Test",
            values = RegistryValues(
                strings = mapOf("Name" to "Value"),
            ),
            languageOverrides = mapOf(
                "french" to RegistryValues(strings = mapOf("Name" to "FrenchValue")),
            ),
        )

        val merged = InstallScriptExecutor.mergeWithLanguage(action, "german")

        assertEquals("Value", merged.strings["Name"])
        assertFalse(merged.strings.containsKey("FrenchValue"))
    }

    // --- matchesOS tests ---

    @Test
    fun matchesOS_acceptsWhenNoRequirement() {
        assertTrue(InstallScriptExecutor.matchesOS(null, is64Bit = false))
        assertTrue(InstallScriptExecutor.matchesOS(null, is64Bit = true))
    }

    @Test
    fun matchesOS_rejects32BitWhen64BitRequired() {
        val requirement = OSRequirement(is64BitWindows = true)
        assertTrue(InstallScriptExecutor.matchesOS(requirement, is64Bit = true))
        assertFalse(InstallScriptExecutor.matchesOS(requirement, is64Bit = false))
    }

    @Test
    fun matchesOS_rejectsWrongOsType() {
        val requirement = OSRequirement(osType = "7")
        assertFalse(InstallScriptExecutor.matchesOS(requirement, is64Bit = false))
    }

    @Test
    fun matchesOS_acceptsMatchingOsType() {
        val requirement = OSRequirement(osType = "10")
        assertTrue(InstallScriptExecutor.matchesOS(requirement, is64Bit = false))
    }

    // --- stripHivePrefix tests ---

    @Test
    fun stripHivePrefix_stripsHKLM() {
        assertEquals("Software\\Test", InstallScriptExecutor.stripHivePrefix("HKLM\\Software\\Test"))
    }

    @Test
    fun stripHivePrefix_stripsHKCU() {
        assertEquals("Software\\Test", InstallScriptExecutor.stripHivePrefix("HKCU\\Software\\Test"))
    }

    @Test
    fun stripHivePrefix_stripsFullNames() {
        assertEquals(
            "Software\\Test",
            InstallScriptExecutor.stripHivePrefix("HKEY_LOCAL_MACHINE\\Software\\Test"),
        )
        assertEquals(
            "Software\\Test",
            InstallScriptExecutor.stripHivePrefix("HKEY_CURRENT_USER\\Software\\Test"),
        )
    }
}
