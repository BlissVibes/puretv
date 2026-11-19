package com.puretv.twitch.desktop.platform

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.Component

/**
 * Whether Windows HDR ("Use HDR", advanced colour) is on for the monitor a window is on.
 * RTX Video HDR only makes sense there: on an SDR display the HDR it generates is
 * tone-mapped straight back down, which blows out highlights and over-saturates colour.
 *
 * Asks Windows itself through the DisplayConfig API (QueryDisplayConfig, then
 * DisplayConfigGetDeviceInfo for each display's GDI name and advanced-colour state),
 * and MonitorFromWindow + GetMonitorInfo for the window's display. Plain user32 calls
 * through JNA's core `Function`, no extra library. Cheap enough to poll every few
 * seconds. Off Windows, or on any failure, the answer is "no".
 */
object DisplayHdr {
    private val user32: NativeLibrary? by lazy {
        if (HostOs.current != HostOs.WINDOWS) null else runCatching { NativeLibrary.getInstance("user32") }.getOrNull()
    }

    private fun fn(name: String): Function? = runCatching { user32?.getFunction(name) }.getOrNull()

    /** GDI display name ("\\.\DISPLAY1", upper case) to whether HDR is on for it. Empty on failure. */
    fun displays(): Map<String, Boolean> = runCatching { query() }.getOrDefault(emptyMap())

    /** True when any display has Windows HDR on. */
    fun anyOn(): Boolean = displays().values.any { it }

    /** Whether HDR is on for the display [component]'s window is on; false when unknown. */
    fun isOn(component: Component): Boolean {
        val name = displayOf(component) ?: return false
        return displays()[name] == true
    }

    /** The GDI name of the display a heavyweight [component] is on, or null. */
    fun displayOf(component: Component): String? = runCatching {
        if (user32 == null || !component.isDisplayable) return null
        val hwnd = Pointer(Native.getComponentID(component))
        val monitor = fn("MonitorFromWindow")?.invokePointer(arrayOf(hwnd, MONITOR_DEFAULTTONEAREST)) ?: return null
        val info = Memory(MONITORINFOEX_SIZE.toLong()).apply { clear(); setInt(0, MONITORINFOEX_SIZE) }
        if (fn("GetMonitorInfoW")?.invokeInt(arrayOf(monitor, info)) == 0) return null
        info.getWideString(MONITORINFOEX_DEVICE_OFFSET.toLong()).uppercase()
    }.getOrNull()

    private fun query(): Map<String, Boolean> {
        val sizes = fn("GetDisplayConfigBufferSizes") ?: return emptyMap()
        val queryConfig = fn("QueryDisplayConfig") ?: return emptyMap()
        val deviceInfo = fn("DisplayConfigGetDeviceInfo") ?: return emptyMap()
        val counts = Memory(8)
        if (sizes.invokeInt(arrayOf(QDC_ONLY_ACTIVE_PATHS, counts, counts.share(4))) != 0) return emptyMap()
        val paths = Memory(counts.getInt(0).coerceAtLeast(1) * PATH_SIZE.toLong()).apply { clear() }
        val modes = Memory(counts.getInt(4).coerceAtLeast(1) * MODE_SIZE.toLong()).apply { clear() }
        if (queryConfig.invokeInt(arrayOf(QDC_ONLY_ACTIVE_PATHS, counts, paths, counts.share(4), modes, Pointer.NULL)) != 0) {
            return emptyMap()
        }
        val result = LinkedHashMap<String, Boolean>()
        for (i in 0 until counts.getInt(0)) {
            val path = paths.share(i * PATH_SIZE.toLong())
            // Source side: the GDI name ("\\.\DISPLAY1") windows report through GetMonitorInfo.
            val name = Memory(SOURCE_NAME_SIZE.toLong()).apply {
                clear(); header(this, TYPE_GET_SOURCE_NAME, SOURCE_NAME_SIZE, path, 0)
            }
            if (deviceInfo.invokeInt(arrayOf(name)) != 0) continue
            // Target side: bit 1 of the value is "advanced colour enabled", which is Windows HDR on.
            val color = Memory(ADVANCED_COLOR_SIZE.toLong()).apply {
                clear(); header(this, TYPE_GET_ADVANCED_COLOR_INFO, ADVANCED_COLOR_SIZE, path, PATH_TARGET_OFFSET)
            }
            val hdr = deviceInfo.invokeInt(arrayOf(color)) == 0 && (color.getInt(HEADER_SIZE.toLong()) and 2) != 0
            result[name.getWideString(HEADER_SIZE.toLong()).uppercase()] = hdr
        }
        return result
    }

    // DISPLAYCONFIG_DEVICE_INFO_HEADER: type, size, adapterId (LUID), id, copied from
    // the source (offset 0) or target (offset 20) half of a DISPLAYCONFIG_PATH_INFO.
    private fun header(mem: Memory, type: Int, size: Int, path: Pointer, sideOffset: Int) {
        mem.setInt(0, type)
        mem.setInt(4, size)
        mem.setInt(8, path.getInt(sideOffset.toLong()))
        mem.setInt(12, path.getInt(sideOffset + 4L))
        mem.setInt(16, path.getInt(sideOffset + 8L))
    }

    private const val QDC_ONLY_ACTIVE_PATHS = 2
    private const val MONITOR_DEFAULTTONEAREST = 2
    private const val PATH_SIZE = 72          // DISPLAYCONFIG_PATH_INFO
    private const val PATH_TARGET_OFFSET = 20 // its targetInfo
    private const val MODE_SIZE = 64          // DISPLAYCONFIG_MODE_INFO
    private const val HEADER_SIZE = 20        // DISPLAYCONFIG_DEVICE_INFO_HEADER
    private const val SOURCE_NAME_SIZE = HEADER_SIZE + 64 // + WCHAR[32]
    private const val ADVANCED_COLOR_SIZE = HEADER_SIZE + 12 // + value, colorEncoding, bitsPerColorChannel
    private const val TYPE_GET_SOURCE_NAME = 1
    private const val TYPE_GET_ADVANCED_COLOR_INFO = 9
    private const val MONITORINFOEX_SIZE = 104 // cbSize, rcMonitor, rcWork, dwFlags, szDevice[32]
    private const val MONITORINFOEX_DEVICE_OFFSET = 40
}
