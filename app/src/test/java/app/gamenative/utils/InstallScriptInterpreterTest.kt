package app.gamenative.utils

import `in`.dragonbra.javasteam.types.KeyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InstallScriptInterpreterTest {

    // ── expandEnvVars ───────────────────────────────────────────────────

    @Test
    fun expandEnvVars_replacesAllKnownVariables() {
        assertEquals("A:\\", InstallScriptInterpreter.expandEnvVars("%INSTALLDIR%"))
        assertEquals("C", InstallScriptInterpreter.expandEnvVars("%ROOTDRIVE%"))
        assertEquals(
            "C:\\users\\steamuser\\AppData\\Roaming",
            InstallScriptInterpreter.expandEnvVars("%APPDATA%"),
        )
        assertEquals(
            "C:\\users\\steamuser\\Documents",
            InstallScriptInterpreter.expandEnvVars("%USER_MYDOCS%"),
        )
        assertEquals(
            "C:\\users\\Public\\Documents",
            InstallScriptInterpreter.expandEnvVars("%COMMON_MYDOCS%"),
        )
        assertEquals(
            "C:\\users\\steamuser\\AppData\\Local",
            InstallScriptInterpreter.expandEnvVars("%LOCALAPPDATA%"),
        )
        assertEquals("C:\\windows", InstallScriptInterpreter.expandEnvVars("%WinDir%"))
        assertEquals(
            "C:\\Program Files (x86)\\Steam",
            InstallScriptInterpreter.expandEnvVars("%STEAMPATH%"),
        )
    }

    @Test
    fun expandEnvVars_isCaseInsensitive() {
        assertEquals("A:\\", InstallScriptInterpreter.expandEnvVars("%installdir%"))
        assertEquals("A:\\", InstallScriptInterpreter.expandEnvVars("%INSTALLDIR%"))
        assertEquals("A:\\", InstallScriptInterpreter.expandEnvVars("%InstallDir%"))
    }

    @Test
    fun expandEnvVars_leavesUnknownVariablesAsIs() {
        assertEquals("%UNKNOWN%", InstallScriptInterpreter.expandEnvVars("%UNKNOWN%"))
        assertEquals(
            "prefix_%NOPE%_suffix",
            InstallScriptInterpreter.expandEnvVars("prefix_%NOPE%_suffix"),
        )
    }

    // ── buildRegistryCommands ───────────────────────────────────────────

    @Test
    fun buildRegistryCommands_stringValuesProduceRegSzCommands() {
        val kv = loadInstallScript(
            """
            "InstallScript"
            {
                "Registry"
                {
                    "HKLM\\Software\\TestApp"
                    {
                        "string"
                        {
                            "InstallPath"        "C:\\Games"
                        }
                    }
                }
            }
            """,
        )

        val commands = InstallScriptInterpreter.buildRegistryCommands(kv)

        assertEquals(1, commands.size)
        assertEquals(
            "wine reg add \"HKLM\\Software\\TestApp\" /v \"InstallPath\" /t REG_SZ /d \"C:\\Games\" /f",
            commands[0],
        )
    }

    @Test
    fun buildRegistryCommands_dwordValuesProduceRegDwordCommands() {
        val kv = loadInstallScript(
            """
            "InstallScript"
            {
                "Registry"
                {
                    "HKLM\\Software\\TestApp"
                    {
                        "dword"
                        {
                            "Version"        "42"
                        }
                    }
                }
            }
            """,
        )

        val commands = InstallScriptInterpreter.buildRegistryCommands(kv)

        assertEquals(1, commands.size)
        assertEquals(
            "wine reg add \"HKLM\\Software\\TestApp\" /v \"Version\" /t REG_DWORD /d 42 /f",
            commands[0],
        )
    }

    @Test
    fun buildRegistryCommands_defaultValueNameUsesVeFlag() {
        val kv = loadInstallScript(
            """
            "InstallScript"
            {
                "Registry"
                {
                    "HKLM\\Software\\TestApp"
                    {
                        "string"
                        {
                            "(Default)"        "myvalue"
                        }
                    }
                }
            }
            """,
        )

        val commands = InstallScriptInterpreter.buildRegistryCommands(kv)

        assertEquals(1, commands.size)
        assertEquals(
            "wine reg add \"HKLM\\Software\\TestApp\" /ve /t REG_SZ /d \"myvalue\" /f",
            commands[0],
        )
    }

    @Test
    fun buildRegistryCommands_expandsEnvVarsInValues() {
        val kv = loadInstallScript(
            """
            "InstallScript"
            {
                "Registry"
                {
                    "HKLM\\Software\\TestApp"
                    {
                        "string"
                        {
                            "Path"        "%INSTALLDIR%bin"
                        }
                    }
                }
            }
            """,
        )

        val commands = InstallScriptInterpreter.buildRegistryCommands(kv)

        assertEquals(1, commands.size)
        assertEquals(
            "wine reg add \"HKLM\\Software\\TestApp\" /v \"Path\" /t REG_SZ /d \"A:\\bin\" /f",
            commands[0],
        )
    }

    @Test
    fun buildRegistryCommands_returnsEmptyListWhenRegistryBlockMissing() {
        val kv = loadInstallScript(
            """
            "InstallScript"
            {
            }
            """,
        )

        val commands = InstallScriptInterpreter.buildRegistryCommands(kv)

        assertTrue(commands.isEmpty())
    }

    // ── buildRunProcessCommands ─────────────────────────────────────────

    @Test
    fun buildRunProcessCommands_withHasRunKeyGeneratesConditionalCommand() {
        val kv = loadInstallScript(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "0"
                    {
                        "process 1"        "C:\\setup.exe"
                        "command 1"        "/silent"
                        "HasRunKey"        "HKLM\\Software\\MyApp\\Setup\\Installed"
                    }
                }
            }
            """,
        )

        val commands = InstallScriptInterpreter.buildRunProcessCommands(kv)

        assertEquals(1, commands.size)
        assertEquals(
            "(wine reg query \"HKLM\\Software\\MyApp\\Setup\" /v \"Installed\" 2>nul | find \"0x\" >nul) || (\"C:\\setup.exe\" /silent && wine reg add \"HKLM\\Software\\MyApp\\Setup\" /v \"Installed\" /t REG_DWORD /d 1 /f)",
            commands[0],
        )
    }

    @Test
    fun buildRunProcessCommands_withoutHasRunKeyGeneratesUnconditionalCommand() {
        val kv = loadInstallScript(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "0"
                    {
                        "process 1"        "C:\\update.exe"
                        "command 1"        ""
                    }
                }
            }
            """,
        )

        val commands = InstallScriptInterpreter.buildRunProcessCommands(kv)

        assertEquals(1, commands.size)
        assertEquals("\"C:\\update.exe\"", commands[0])
    }

    @Test
    fun buildRunProcessCommands_expandsEnvVarsInProcessAndCommand() {
        val kv = loadInstallScript(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "0"
                    {
                        "process 1"        "%INSTALLDIR%setup.exe"
                        "command 1"        "/dir=%INSTALLDIR%"
                    }
                }
            }
            """,
        )

        val commands = InstallScriptInterpreter.buildRunProcessCommands(kv)

        assertEquals(1, commands.size)
        assertEquals("\"A:\\setup.exe\" /dir=A:\\", commands[0])
    }

    @Test
    fun buildRunProcessCommands_returnsEmptyListWhenRunProcessBlockMissing() {
        val kv = loadInstallScript(
            """
            "InstallScript"
            {
            }
            """,
        )

        val commands = InstallScriptInterpreter.buildRunProcessCommands(kv)

        assertTrue(commands.isEmpty())
    }

    // ── buildAllCommands ────────────────────────────────────────────────

    @Test
    fun buildAllCommands_registryCommandsAppearBeforeRunProcessCommands() {
        val kv = loadInstallScript(
            """
            "InstallScript"
            {
                "Registry"
                {
                    "HKLM\\Software\\TestApp"
                    {
                        "string"
                        {
                            "InstallPath"        "C:\\Games"
                        }
                    }
                }
                "Run Process"
                {
                    "0"
                    {
                        "process 1"        "C:\\update.exe"
                        "command 1"        ""
                    }
                }
            }
            """,
        )

        val commands = InstallScriptInterpreter.buildAllCommands(kv)

        assertEquals(2, commands.size)
        assertTrue(commands[0].contains("/t REG_SZ"))
        assertEquals("\"C:\\update.exe\"", commands[1])
    }

    // ── buildFirewallCommands ───────────────────────────────────────────

    @Test
    fun buildFirewallCommands_alwaysReturnsEmptyList() {
        val kv = loadInstallScript(
            """
            "InstallScript"
            {
                "Firewall"
                {
                    "MyApp"
                    {
                        "path"        "C:\\app.exe"
                    }
                }
            }
            """,
        )

        val commands = InstallScriptInterpreter.buildFirewallCommands(kv)

        assertTrue(commands.isEmpty())
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun loadInstallScript(vdf: String): KeyValue =
        KeyValue.loadFromString(vdf.trimIndent())!!
}
