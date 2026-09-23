package dev.aura.adosaura.config

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelsTest {

    private fun entry(pkg: String, label: String) = AppEntry(pkg, label, listOf("all"), 1)

    @Test fun uniqueNamesAreUntouched() {
        val m = Labels.display(listOf(entry("com.android.chrome", "Chrome")))
        assertEquals("Chrome", m["com.android.chrome"])
    }

    @Test fun duplicateNamesGetTheirVendor() {
        val m = Labels.display(
            listOf(
                entry("com.google.android.apps.authenticator2", "Authenticator"),
                entry("com.azure.authenticator", "Authenticator"),
            ),
        )
        assertEquals("Authenticator · google", m["com.google.android.apps.authenticator2"])
        assertEquals("Authenticator · azure", m["com.azure.authenticator"])
    }

    @Test fun sameVendorFallsBackToPackage() {
        val m = Labels.display(listOf(entry("com.x.a", "Notes"), entry("com.x.b", "Notes")))
        assertEquals("Notes · com.x.a", m["com.x.a"])
        assertEquals("Notes · com.x.b", m["com.x.b"])
    }

    @Test fun shortNameStaysOnOneLine() = assertEquals(listOf("Gmail"), Labels.split("Gmail", 5))

    @Test fun longWordIsCutInHalf() = assertEquals(listOf("Cam", "era"), Labels.split("Camera", 5))

    @Test fun twoWordsBreakAtTheSpace() = assertEquals(listOf("Aura", "Pulse"), Labels.split("Aura Pulse", 6))

    @Test fun veryLongWordIsChunked() =
        assertEquals(listOf("Auth", "enti", "cato", "r"), Labels.split("Authenticator", 4))
}
