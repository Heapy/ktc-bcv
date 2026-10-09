package io.heapy.ktc.plugins.bcv

import org.jetbrains.amper.plugins.Configurable

/** Configuration for JVM and optional KLib API snapshots. Package and class names use dotted notation. */
@Configurable
public interface BcvSettings {
    /** Baselines are stored under this directory, relative to the consumer module. */
    public val apiDirectory: String get() = "api"

    /** Empty disables KLib checks, preserving the existing JVM-only configuration. */
    public val klibTargets: List<String> get() = emptyList()

    /** Compile all selected targets instead of selecting the current host's family. */
    public val klibIncludeCrossTargets: Boolean get() = false
    public val klibTimeoutSeconds: Int get() = 1800

    /** The following filters apply only to the JVM snapshot. */
    public val ignoredPackages: List<String> get() = emptyList()
    public val ignoredClasses: List<String> get() = emptyList()
    public val nonPublicMarkers: List<String> get() = emptyList()

    /** When an inclusion list is set, only explicitly included API is retained. */
    public val publicPackages: List<String> get() = emptyList()
    public val publicClasses: List<String> get() = emptyList()
    public val publicMarkers: List<String> get() = emptyList()
}
