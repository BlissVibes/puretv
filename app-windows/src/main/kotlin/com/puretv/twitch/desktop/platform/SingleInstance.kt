package com.puretv.twitch.desktop.platform

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Keeps PureTV to one running copy. Two copies would each try to start the local
 * stream proxy on the same port, write the same files under `%APPDATA%\PureTwitch`
 * and refresh the same sign-in at the same moment, rotating each other's refresh
 * token into an invalid state.
 *
 * The first copy holds an exclusive lock on `instance.lock`; Windows drops it when
 * that process ends, however it ends. A second launch brings the existing window
 * to the front and exits. If the lock is held but no window is up, a copy is
 * still closing (the updater relaunches PureTV right after quitting it), so the
 * new launch waits for the lock instead of giving up and leaving no PureTV at all.
 * When in doubt it runs: refusing to start is worse than a rare second copy.
 */
class SingleInstance(
    private val lockFile: File,
    private val focusExistingWindow: () -> Boolean,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /** True if this process should keep running; false if another copy took over. */
    fun acquire(waitForClosingCopyMs: Long = 10_000): Boolean {
        val deadline = nowMs() + waitForClosingCopyMs
        while (true) {
            when (tryLock()) {
                LockResult.ACQUIRED, LockResult.UNAVAILABLE -> return true
                LockResult.HELD -> {
                    if (runCatching(focusExistingWindow).getOrDefault(false)) return false
                    if (nowMs() >= deadline) return true
                    sleep(POLL_MS)
                }
            }
        }
    }

    private enum class LockResult { ACQUIRED, HELD, UNAVAILABLE }

    private fun tryLock(): LockResult {
        val openChannel = channel ?: runCatching {
            lockFile.parentFile?.mkdirs()
            RandomAccessFile(lockFile, "rw").channel
        }.getOrNull()?.also { channel = it } ?: return LockResult.UNAVAILABLE
        return try {
            val acquired = openChannel.tryLock()
            if (acquired != null) {
                lock = acquired
                LockResult.ACQUIRED
            } else {
                LockResult.HELD
            }
        } catch (e: OverlappingFileLockException) {
            LockResult.HELD
        } catch (e: Exception) {
            LockResult.UNAVAILABLE
        }
    }

    companion object {
        private const val POLL_MS = 250L

        /** Restores and focuses the top-level window titled [title], if one exists. */
        fun focusWindowTitled(title: String): Boolean {
            val user32 = User32.INSTANCE
            val hwnd = user32.FindWindow(null, title) ?: return false
            // Restore only a minimized window: SW_RESTORE would also un-maximize one.
            val placement = WinUser.WINDOWPLACEMENT()
            if (user32.GetWindowPlacement(hwnd, placement).booleanValue() &&
                placement.showCmd == WinUser.SW_SHOWMINIMIZED
            ) {
                user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
            }
            user32.SetForegroundWindow(hwnd)
            return true
        }
    }
}
