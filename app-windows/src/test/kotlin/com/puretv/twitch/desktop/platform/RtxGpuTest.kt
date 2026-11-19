package com.puretv.twitch.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RtxGpuTest {
    private val key = "HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}"

    // The two queries as `reg query /s /v ...` prints them on a PC with an AMD iGPU and an RTX 4070.
    private val driverDesc = """
        |
        |$key\0000
        |    DriverDesc    REG_SZ    AMD Radeon(TM) Graphics
        |
        |$key\0001
        |    DriverDesc    REG_SZ    NVIDIA GeForce RTX 4070
        |
        |End of search: 2 match(es) found.
        |""".trimMargin().replace("\n", "\r\n")

    private val matchingIds = """
        |
        |$key\0000
        |    MatchingDeviceId    REG_SZ    PCI\VEN_1002&DEV_164E&SUBSYS_7D751462&REV_CB
        |
        |$key\0001
        |    MatchingDeviceId    REG_SZ    pci\ven_10de&dev_2786
        |
        |End of search: 2 match(es) found.
        |""".trimMargin().replace("\n", "\r\n")

    @Test
    fun `AMD plus RTX 4070 finds the RTX card`() {
        val adapters = RtxGpu.parse(driverDesc + "\n" + matchingIds)
        assertEquals(
            listOf(
                GpuAdapter("AMD Radeon(TM) Graphics", nvidia = false, rtx = false),
                GpuAdapter("NVIDIA GeForce RTX 4070", nvidia = true, rtx = true),
            ),
            adapters,
        )
        assertEquals("NVIDIA GeForce RTX 4070", adapters.first { it.rtx }.name)
    }

    @Test
    fun `GTX and MX cards are not RTX`() {
        assertFalse(RtxGpu.isRtx("NVIDIA GeForce GTX 1080", "PCI\\VEN_10DE&DEV_1B80"))
        assertFalse(RtxGpu.isRtx("NVIDIA GeForce MX450", "PCI\\VEN_10DE&DEV_1F97"))
        assertFalse(RtxGpu.isRtx("NVIDIA GeForce GTX 1660 SUPER", null))
    }

    @Test
    fun `workstation RTX cards count`() {
        assertTrue(RtxGpu.isRtx("Quadro RTX 5000", "PCI\\VEN_10DE&DEV_1EB0"))
        assertTrue(RtxGpu.isRtx("NVIDIA RTX A4000", "PCI\\VEN_10DE&DEV_24B0"))
        assertTrue(RtxGpu.isRtx("NVIDIA RTX 2000 Ada Generation", "PCI\\VEN_10DE&DEV_28B0"))
        assertTrue(RtxGpu.isRtx("NVIDIA GeForce RTX 5090", "PCI\\VEN_10DE&DEV_2B85"))
    }

    @Test
    fun `vendor id is matched in any case`() {
        assertTrue(RtxGpu.isRtx("NVIDIA GeForce RTX 3060", "PCI\\VEN_10DE&DEV_2503"))
        assertTrue(RtxGpu.isRtx("NVIDIA GeForce RTX 3060", "pci\\ven_10de&dev_2503"))
        assertTrue(RtxGpu.isRtx("NVIDIA GeForce RTX 3060", "Pci\\Ven_10De&Dev_2503"))
    }

    @Test
    fun `an RTX name on another vendor's id is not NVIDIA`() {
        assertFalse(RtxGpu.isRtx("Some RTX lookalike", "PCI\\VEN_1002&DEV_164E"))
        assertFalse(RtxGpu.isRtx("Generic RTX adapter", null))
    }

    @Test
    fun `empty or garbage output gives no adapters`() {
        assertEquals(emptyList(), RtxGpu.parse(""))
        assertEquals(emptyList(), RtxGpu.parse("ERROR: The system was unable to find the specified registry key or value.\r\n"))
        assertEquals(emptyList(), RtxGpu.parse("\u0000\u0001 nonsense\n    DriverDesc    REG_SZ    orphan value\n"))
    }

    @Test
    fun `a key without a MatchingDeviceId falls back to the name`() {
        val out = "$key\\0000\r\n    DriverDesc    REG_SZ    NVIDIA GeForce RTX 2060\r\n\r\n" +
            "$key\\0001\r\n    DriverDesc    REG_SZ    Microsoft Basic Display Adapter\r\n"
        assertEquals(
            listOf(
                GpuAdapter("NVIDIA GeForce RTX 2060", nvidia = true, rtx = true),
                GpuAdapter("Microsoft Basic Display Adapter", nvidia = false, rtx = false),
            ),
            RtxGpu.parse(out),
        )
    }

    @Test
    fun `Configuration and Properties subkeys without values are ignored`() {
        val out = "\r\n$key\\0000\r\n    DriverDesc    REG_SZ    NVIDIA GeForce RTX 4080\r\n" +
            "    MatchingDeviceId    REG_SZ    PCI\\VEN_10DE&DEV_2704\r\n\r\n" +
            "$key\\Configuration\r\n\r\n" +
            "$key\\Properties\r\n\r\n" +
            "$key\\0000\\Settings\r\n\r\n" +
            "End of search: 2 match(es) found.\r\n"
        assertEquals(listOf(GpuAdapter("NVIDIA GeForce RTX 4080", nvidia = true, rtx = true)), RtxGpu.parse(out))
    }

    @Test
    fun `machine lookup never throws`() {
        // Whatever this machine has, reading it must not fail, and off Windows it is empty.
        val adapters = RtxGpu.adapters
        if (HostOs.current != HostOs.WINDOWS) assertEquals(emptyList(), adapters)
        assertEquals(adapters.any { it.rtx }, RtxGpu.present)
        println("RtxGpu on this machine: present=${RtxGpu.present} name=${RtxGpu.name} adapters=$adapters")
    }
}
