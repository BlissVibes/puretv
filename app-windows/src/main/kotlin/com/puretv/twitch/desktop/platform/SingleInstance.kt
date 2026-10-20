package com.puretv.twitch.desktop.platform

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService

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
 *
 * A copy hidden in the tray has a window, but an invisible one that focusing
 * cannot bring back. So the second launch also drops a `show.request` file into
 * [activationDir]. The running copy watches that folder ([listenForActivation]),
 * deletes the request and shows its window; the deleted file is the answer that
 * lets the second launch exit. A copy that is still closing never answers, which
 * is how the two cases stay apart. The watch is a blocked thread on a folder
 * nothing else writes to, so it costs no CPU and no wakeups.
 */
class SingleInstance(
    private val lockFile: File,
    private val focusExistingWindow: () -> Boolean,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val activationDir: File = File(lockFile.parentFile ?: File("."), "activate"),
) {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    @Volatile private var watcher: WatchService? = null
    @Volatile private var listening = false

    private val requestFile get() = File(activationDir, REQUEST_NAME)

    /** True if this process should keep running; false if another copy took over. */
    fun acquire(waitForClosingCopyMs: Long = 10_000): Boolean {
        val deadline = nowMs() + waitForClosingCopyMs
        var requested = false
        while (true) {
            when (tryLock()) {
                LockResult.ACQUIRED -> {
                    // Anything already here is a request nobody answered (its copy was
                    // closing); it must not pop the window open on its own later.
                    runCatching { requestFile.delete() }
                    return true
                }
                LockResult.UNAVAILABLE -> return true
                LockResult.HELD -> {
                    if (!requested) requested = requestActivation()
                    if (requested && !requestFile.exists()) return false
                    if (runCatching(focusExistingWindow).getOrDefault(false)) return false
                    if (nowMs() >= deadline) return true
                    sleep(POLL_MS)
                }
            }
        }
    }

    /**
     * Calls [onActivate] (on a background thread) each time another launch asks this
     * copy to show itself. Only the copy that won [acquire] should listen.
     */
    fun listenForActivation(onActivate: () -> Unit) {
        val service = runCatching {
            activationDir.mkdirs()
            FileSystems.getDefault().newWatchService().also {
                activationDir.toPath().register(
                    it,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                )
            }
        }.getOrNull() ?: return
        watcher = service
        listening = true
        Thread({
            // A request written between acquire() and the watch starting is answered too.
            consumeRequest(onActivate)
            try {
                while (true) {
                    val key = service.take()
                    val ours = key.pollEvents().any { (it.context() as? java.nio.file.Path)?.toString() == REQUEST_NAME }
                    if (ours) consumeRequest(onActivate)
                    if (!key.reset()) break
                }
            } catch (_: ClosedWatchServiceException) {
            } catch (_: InterruptedException) {
            }
        }, "puretv-activation").apply { isDaemon = true }.start()
    }

    /**
     * Stops [listenForActivation]. Called as soon as the app is quitting: a launch
     * that arrives while this copy tears down must not have its request answered
     * (deleted) by a copy that is about to exit, or it would leave and nothing
     * would be running. Unanswered, it waits for the lock and starts normally.
     */
    fun stopListening() {
        listening = false
        runCatching { watcher?.close() }
        watcher = null
    }

    private fun consumeRequest(onActivate: () -> Unit) {
        if (listening && requestFile.exists() && runCatching { requestFile.delete() }.getOrDefault(false)) {
            runCatching(onActivate)
        }
    }

    /** Writes the request in one rename, so the running copy never reads half a file. */
    private fun requestActivation(): Boolean = runCatching {
        activationDir.mkdirs()
        val temp = File.createTempFile("show", ".tmp", activationDir)
        temp.writeText("show")
        Files.move(temp.toPath(), requestFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    }.getOrDefault(false)

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
        private const val REQUEST_NAME = "show.request"

        /**
         * Restores and focuses the top-level window titled [title], if one is showing.
         * A window hidden in the tray does not count: focusing cannot show it, and
         * that copy answers the request file instead.
         */
        fun focusWindowTitled(title: String): Boolean {
            val user32 = User32.INSTANCE
            val hwnd = user32.FindWindow(null, title) ?: return false
            if (!user32.IsWindowVisible(hwnd)) return false
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
