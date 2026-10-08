package io.heapy.ktc.plugins.bcv

import org.jetbrains.amper.plugins.Configurable

/** Configuration for the JVM API snapshot. Package and class names use dotted notation. */
@Configurable
public interface BcvSettings {
    /** Baselines are stored under this directory, relative to the consumer module. */
    public val apiDirectory: String get() = "api"

    public val ignoredPackages: List<String> get() = emptyList()
    public val ignoredClasses: List<String> get() = emptyList()
    public val nonPublicMarkers: List<String> get() = emptyList()

    /** When an inclusion list is set, only explicitly included API is retained. */
    public val publicPackages: List<String> get() = emptyList()
    public val publicClasses: List<String> get() = emptyList()
    public val publicMarkers: List<String> get() = emptyList()
}
