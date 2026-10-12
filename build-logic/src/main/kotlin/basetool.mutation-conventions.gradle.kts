import de.greluc.krt.profit.basetool.conventions.catalog
import de.greluc.krt.profit.basetool.conventions.moduleBuildSettings
import de.greluc.krt.profit.basetool.conventions.requiredVersion

plugins {
  id("basetool.java-conventions")
  id("info.solidsoft.pitest")
}

val mutationSettings = moduleBuildSettings()
val pitestJunit5Version = project.catalog().requiredVersion("pitestJunit5")

pitest {
  junit5PluginVersion.set(pitestJunit5Version)
  addJUnitPlatformLauncher.set(false)
  targetClasses.set(mutationSettings.mutationTargetClasses)
  targetTests.set(mutationSettings.mutationTargetTests)
  threads.set(4)
  outputFormats.set(listOf("HTML", "XML"))
  timestampedReports.set(false)

  val mockitoAgent = configurations.named("mockitoAgent")
  jvmArgs.set(
    provider {
      val args =
        mutableListOf("--enable-native-access=ALL-UNNAMED", "-Dspring.profiles.active=test")
      mockitoAgent.get().files.singleOrNull()?.let {
        args += "-Xshare:off"
        args += "-javaagent:${it.absolutePath}"
      }
      args
    }
  )
}
