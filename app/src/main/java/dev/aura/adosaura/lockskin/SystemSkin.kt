package dev.aura.adosaura.lockskin

/**
 * The seam Phase 3 plugs into.
 *
 * Recents, the notification shade and the system-wide status bar are system
 * components: a normal APK cannot replace them on HyperOS, so none of that is
 * attempted here. On a rooted phone a Magisk + LSPosed module would implement
 * this interface and swap itself in, without the launcher changing.
 */
interface SystemSkin {

    /** Draw over the recents screen. */
    val ownsRecents: Boolean

    /** Draw over the notification shade. */
    val ownsShade: Boolean

    /** Replace the system status bar rather than hiding it at home. */
    val ownsStatusBar: Boolean

    companion object {
        /** What a non-rooted install gets. Honest about the limitation. */
        val NONE: SystemSkin = object : SystemSkin {
            override val ownsRecents get() = false
            override val ownsShade get() = false
            override val ownsStatusBar get() = false

            override fun toString() =
                "SystemSkin.NONE — recents, shade and the system status bar stay MIUI's"
        }
    }
}
