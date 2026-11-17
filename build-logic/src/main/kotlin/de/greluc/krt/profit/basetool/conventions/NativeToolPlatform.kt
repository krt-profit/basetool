package de.greluc.krt.profit.basetool.conventions

/**
 * A host platform for which `gradle/verification-metadata.xml` holds the checksums of the native
 * formatters' release binaries.
 */
enum class NativeToolPlatform {
  /** 64-bit x86 Linux: the CI runners. */
  LINUX_X64,

  /** 64-bit x86 Windows: the workstations. */
  WINDOWS_X64;

  /** Factory methods reading the JVM's `os.name` and `os.arch` values. */
  companion object {
    private val X64_ARCHITECTURES = setOf("amd64", "x86_64")

    /**
     * The platform of a JVM reporting [osName] and [osArch].
     *
     * @throws IllegalStateException when no verified binary exists for that platform
     */
    fun of(osName: String, osArch: String): NativeToolPlatform =
      when {
        osArch !in X64_ARCHITECTURES -> unsupported(osName, osArch)
        osName.startsWith("Windows") -> WINDOWS_X64
        osName == "Linux" -> LINUX_X64
        else -> unsupported(osName, osArch)
      }

    /** The file-name suffix of an executable on a JVM reporting [osName]: `.exe` on Windows. */
    fun executableSuffix(osName: String): String = if (osName.startsWith("Windows")) ".exe" else ""

    private fun unsupported(osName: String, osArch: String): Nothing =
      error(
        "The native formatters have verified binaries for Linux and Windows on x86-64 only, " +
          "not for $osName on $osArch.",
      )
  }
}
