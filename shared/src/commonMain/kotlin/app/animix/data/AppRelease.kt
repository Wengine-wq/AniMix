package app.animix.data

/** Public release identity. [AppVersion] remains the numeric package/protocol version. */
object AppRelease {
    const val version = "1.0.5 re"
    /** The build number compared with GitHub releases tagged `v<build>-re` (see UpdateSource). */
    const val build = "1.0.5"
    const val name = "New Horizons"
    const val label = "AniMix $version · $name"
    const val nextVersion = "1.1 re"
}
