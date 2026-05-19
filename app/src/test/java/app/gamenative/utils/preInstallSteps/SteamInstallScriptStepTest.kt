package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class SteamInstallScriptStepTest {
    private lateinit var container: Container
    private lateinit var gameDir: File

    @Before
    fun setUp() {
        container = mockk(relaxed = true)
        gameDir = createTempDirectory(prefix = "steam-installscript-test").toFile()
    }

    @Test
    fun appliesTo_returnsTrue_forSteamWithoutMarker() {
        assertTrue(SteamInstallScriptStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
    }

    @Test
    fun appliesTo_returnsFalse_whenMarkerExists() {
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.STEAM_INSTALL_SCRIPT_RUN)
        assertFalse(SteamInstallScriptStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
    }

    @Test
    fun appliesTo_returnsFalse_forNonSteamSources() {
        assertFalse(SteamInstallScriptStep.appliesTo(container, GameSource.GOG, gameDir.absolutePath))
        assertFalse(SteamInstallScriptStep.appliesTo(container, GameSource.CUSTOM_GAME, gameDir.absolutePath))
    }

    @Test
    fun buildCommand_returnsNull_whenNoDepotDir() {
        val result = SteamInstallScriptStep.buildCommand(container, "12345", GameSource.STEAM, gameDir, gameDir.absolutePath)
        assertNull(result)
    }

    @Test
    fun buildCommand_returnsNull_whenNoManifestFiles() {
        File(gameDir, ".DepotDownloader").mkdirs()
        val result = SteamInstallScriptStep.buildCommand(container, "12345", GameSource.STEAM, gameDir, gameDir.absolutePath)
        assertNull(result)
    }
}
