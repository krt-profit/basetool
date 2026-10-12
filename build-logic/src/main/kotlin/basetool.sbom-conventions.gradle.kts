import org.cyclonedx.Version
import org.cyclonedx.gradle.BaseCyclonedxTask
import org.cyclonedx.gradle.CyclonedxDirectTask

plugins {
  id("basetool.java-conventions")
  id("org.cyclonedx.bom")
}

val sbomExplicitlyRequested =
  gradle.startParameter.taskNames.any { it.substringAfterLast(':').startsWith("cyclonedx") }

val untrackedReason =
  "a release SBOM is always rebuilt from the live dependency graph: the plugin's inputs do " +
    "not see project dependencies, so UP-TO-DATE / FROM-CACHE could ship a stale one"

tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
  enabled = sbomExplicitlyRequested
  includeConfigs.set(listOf("^runtimeClasspath$"))
  doNotTrackState(untrackedReason)
}

val cyclonedxBom =
  tasks.named<BaseCyclonedxTask>("cyclonedxBom") {
    enabled = sbomExplicitlyRequested
    schemaVersion.set(Version.VERSION_16)
    jsonOutput.set(file("docs/${project.name}-bom.json"))
    xmlOutput.set(file("docs/${project.name}-bom.xml"))
    includeBomSerialNumber.set(true)
    includeLicenseText.set(true)
    includeBuildSystem.set(true)
    doNotTrackState(untrackedReason)
    finalizedBy("verifyCyclonedxBom")
  }

val expectedComponents =
  configurations
    .named("runtimeClasspath")
    .flatMap { it.incoming.resolutionResult.rootComponent }
    .map { root ->
      val seen = mutableSetOf<ResolvedComponentResult>()
      val queue = ArrayDeque(listOf(root))
      while (queue.isNotEmpty()) {
        queue.removeFirst().dependencies.filterIsInstance<ResolvedDependencyResult>().forEach {
          if (seen.add(it.selected)) queue.addLast(it.selected)
        }
      }
      seen
        .filter { it != root }
        .mapNotNull { it.moduleVersion }
        .map { "${it.group}:${it.name}:${it.version}" }
        .sorted()
    }

tasks.register("verifyCyclonedxBom") {
  group = "verification"
  description = "Fails when the CycloneDX BOM does not list exactly the resolved runtimeClasspath."
  enabled = sbomExplicitlyRequested
  val bomFile = cyclonedxBom.flatMap { it.jsonOutput }
  val projectPath = project.path
  val expectedProvider = expectedComponents
  inputs.file(bomFile).withPropertyName("bom").withPathSensitivity(PathSensitivity.NONE)
  inputs.property("expectedComponents", expectedProvider)
  doLast {
    val bom = groovy.json.JsonSlurper().parse(bomFile.get().asFile) as Map<*, *>
    val listed =
      (bom["components"] as? List<*>)
        .orEmpty()
        .filterIsInstance<Map<*, *>>()
        .map { "${it["group"]}:${it["name"]}:${it["version"]}" }
        .toSet()
    val expected = expectedProvider.get().toSet()
    val missing = (expected - listed).sorted()
    val extra = (listed - expected).sorted()
    if (missing.isNotEmpty() || extra.isNotEmpty()) {
      throw GradleException(
        buildString {
          append("$projectPath: the SBOM does not match the resolved runtimeClasspath.\n")
          if (missing.isNotEmpty()) {
            append("  On the classpath, missing from the BOM:\n")
            missing.forEach { append("    - $it\n") }
          }
          if (extra.isNotEmpty()) {
            append("  In the BOM, no longer on the classpath:\n")
            extra.forEach { append("    - $it\n") }
          }
          append("A stale BOM was written; regenerate it with a fresh `cyclonedxBom` run.")
        },
      )
    }
  }
}
