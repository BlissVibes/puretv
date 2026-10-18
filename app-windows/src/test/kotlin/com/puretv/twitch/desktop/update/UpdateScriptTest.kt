package com.puretv.twitch.desktop.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the self-update script generation. Pins two behaviours:
 *  - no visible console (ping-based wait, no `timeout`; hidden .vbs launcher), and
 *  - the install runs ONLY after the app process has exited: the script polls the
 *    app PID and force-closes it if it lingers, so the silent in-place upgrade
 *    never runs against locked files (which corrupted the launcher .cfg).
 * Plus the recovery path: a failed install is retried once, then reported and
 * the old version reopened, never a silent dead end.
 */
class UpdateScriptTest {

    @Test fun batWaitsWithPingNotTimeout() {
        val s = buildUpdateScripts(
            installerPath = "C:\\tmp\\PureTV-Setup-1.4.0.exe",
            isMsi = false,
            appExePath = "C:\\app\\PureTV for Twitch.exe",
            batPath = "C:\\tmp\\PureTV-update\\apply-update.bat",
            appPid = 4242L,
        )
        assertTrue(s.bat.contains("ping 127.0.0.1 -n 3 >nul"), s.bat)
        assertFalse(s.bat.contains("timeout"), s.bat)
        assertTrue(s.bat.contains("\"C:\\tmp\\PureTV-Setup-1.4.0.exe\" /SILENT /SUPPRESSMSGBOXES /NORESTART"), s.bat)
        assertTrue(s.bat.contains("/LOG=\"C:\\tmp\\PureTV-Setup-1.4.0.exe.install-%ATTEMPT%.log\""), s.bat)
        assertTrue(s.bat.contains("\"C:\\app\\PureTV for Twitch.exe\""), s.bat)
        assertTrue(s.bat.trimEnd().endsWith("exit /b 0"), s.bat)
    }

    @Test fun batWaitsForAppPidThenForceClosesIt() {
        val s = buildUpdateScripts(
            installerPath = "C:\\tmp\\x.exe",
            isMsi = false,
            appExePath = "C:\\app\\PureTV for Twitch.exe",
            batPath = "C:\\tmp\\b.bat",
            appPid = 4242L,
        )
        // The PID is captured, polled, and force-closed as a fallback.
        assertTrue(s.bat.contains("set \"APPPID=4242\""), s.bat)
        assertTrue(s.bat.contains("tasklist /fi \"PID eq %APPPID%\""), s.bat)
        assertTrue(s.bat.contains("taskkill /f /pid %APPPID%"), s.bat)
        assertTrue(s.bat.contains("taskkill /f /im \"PureTV for Twitch.exe\""), s.bat)
        assertTrue(s.bat.contains("IMAGENAME eq PureTV for Twitch.exe"), s.bat)
        // No tree-kill: /T would kill this very script (a descendant of the app).
        assertFalse(s.bat.contains("/t "), s.bat.lowercase())
        // The wait/kill block precedes the installer invocation.
        assertTrue(s.bat.indexOf("APPPID") < s.bat.indexOf("/SILENT"), s.bat)
    }

    @Test fun relaunchRequiresSuccessfulInstallAndLauncherConfig() {
        val s = buildUpdateScripts(
            installerPath = "C:\\tmp\\x.exe",
            isMsi = false,
            appExePath = "C:\\app\\PureTV for Twitch.exe",
            batPath = "C:\\tmp\\b.bat",
            appPid = 4242L,
        )
        val installer = s.bat.indexOf("/SILENT")
        val exitCheck = s.bat.indexOf("INSTALL_EXIT")
        val configCheck = s.bat.indexOf("if not exist \"C:\\app\\app\\PureTV for Twitch.cfg\"")
        // lastIndexOf: the failure path earlier in the script also reopens the app.
        val relaunch = s.bat.lastIndexOf("start \"\" \"C:\\app\\PureTV for Twitch.exe\"")
        assertTrue(installer in 0..<exitCheck, s.bat)
        assertTrue(exitCheck < configCheck, s.bat)
        assertTrue(configCheck < relaunch, s.bat)
    }

    @Test fun vbsRunsBatHiddenAndDetached() {
        val s = buildUpdateScripts(
            installerPath = "C:\\tmp\\x.exe",
            isMsi = false,
            appExePath = null,
            batPath = "C:\\tmp\\apply-update.bat",
            appPid = 1L,
        )
        // Run(cmd, 0, False): window style 0 = hidden, False = don't wait.
        assertTrue(s.vbs.contains("WScript.Shell"), s.vbs)
        assertTrue(s.vbs.contains("apply-update.bat"), s.vbs)
        assertTrue(s.vbs.contains(", 0, False"), s.vbs)
    }

    @Test fun msiUsesMsiexec() {
        val s = buildUpdateScripts(
            installerPath = "C:\\tmp\\x.msi",
            isMsi = true,
            appExePath = null,
            batPath = "C:\\tmp\\b.bat",
            appPid = 1L,
        )
        assertTrue(s.bat.contains("msiexec /i \"C:\\tmp\\x.msi\" /passive /norestart /l*v \"C:\\tmp\\x.msi.install-%ATTEMPT%.log\""), s.bat)
    }

    @Test fun noRelaunchLineWhenExeNull() {
        val s = buildUpdateScripts(
            installerPath = "C:\\tmp\\x.exe",
            isMsi = false,
            appExePath = null,
            batPath = "C:\\tmp\\b.bat",
            appPid = 1L,
        )
        assertFalse(s.bat.contains("start \"\""), s.bat)
    }

    @Test fun installerShowsProgressInsteadOfRunningInvisibly() {
        val s = buildUpdateScripts(
            installerPath = "C:\\tmp\\x.exe",
            isMsi = false,
            appExePath = "C:\\app\\PureTV for Twitch.exe",
            batPath = "C:\\tmp\\b.bat",
            appPid = 1L,
        )
        // Fully silent, the app vanished for the whole install, people reopened it
        // mid-copy, and the locked jars rolled the update back.
        assertFalse(s.bat.contains("VERYSILENT"), s.bat)
    }

    @Test fun failedInstallIsRetriedOnceThenReportedAndTheOldAppReopened() {
        val s = buildUpdateScripts(
            installerPath = "C:\\tmp\\x.exe",
            isMsi = false,
            appExePath = "C:\\app\\PureTV for Twitch.exe",
            batPath = "C:\\tmp\\b.bat",
            appPid = 1L,
            targetVersion = "v1.12.1",
            updateLogPath = "C:\\data\\update.log",
            failureMarkerPath = "C:\\data\\update-failed.txt",
        )
        val bat = s.bat
        assertTrue(bat.contains("if %ATTEMPT% GEQ 2 goto puretv_install_failed"), bat)
        val retryKill = bat.indexOf("taskkill", bat.indexOf("GEQ 2"))
        assertTrue(retryKill in 0..<bat.indexOf("goto puretv_install\r\n"), bat)

        val failed = bat.substring(bat.indexOf(":puretv_install_failed"), bat.indexOf(":puretv_installed"))
        assertTrue(failed.contains(">>\"C:\\data\\update.log\" echo"), failed)
        assertTrue(failed.contains("install of v1.12.1 failed with exit %INSTALL_EXIT%"), failed)
        assertTrue(failed.contains(">\"C:\\data\\update-failed.txt\" echo v1.12.1"), failed)
        // Reopens the old version, but not on top of one the user already reopened.
        assertTrue(failed.contains("|| start \"\" \"C:\\app\\PureTV for Twitch.exe\""), failed)
        assertTrue(failed.trimEnd().endsWith("exit /b %INSTALL_EXIT%"), failed)
    }
}
