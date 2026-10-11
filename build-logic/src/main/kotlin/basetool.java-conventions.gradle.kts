import de.greluc.krt.profit.basetool.build.BasetoolExtension
import de.greluc.krt.profit.basetool.build.catalog
import de.greluc.krt.profit.basetool.build.moduleBuildSettings
import de.greluc.krt.profit.basetool.build.requiredLibrary
import de.greluc.krt.profit.basetool.build.requiredVersion
import java.util.zip.ZipFile

plugins { java }

val basetool = extensions.create<BasetoolExtension>("basetool")
val catalog = project.catalog()

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

val lombok = provider {
  if (pluginManager.hasPlugin("io.spring.dependency-management")) "org.projectlombok:lombok"
  else "org.projectlombok:lombok:${catalog.requiredVersion("lombok")}"
}

listOf("compileOnly", "annotationProcessor", "testCompileOnly", "testAnnotationProcessor").forEach {
  dependencies.addProvider(it, lombok)
}

listOf("compileOnly", "testCompileOnly").forEach {
  dependencies.add(it, catalog.requiredLibrary("jetbrains-annotations"))
}

val mockitoAgent =
  configurations.register("mockitoAgent") {
    isCanBeConsumed = false
    isTransitive = false
    description = "The mockito-core jar, attached to every Test JVM as a Java agent."
  }

tasks.withType<Test>().configureEach {
  val agent: FileCollection = mockitoAgent.get()
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      agent.files.singleOrNull()?.let { listOf("-Xshare:off", "-javaagent:${it.absolutePath}") }
        ?: emptyList()
    }
  )
}

tasks
  .withType<Jar>()
  .matching { it.name == "jar" || it.name == "bootJar" }
  .configureEach {
    val archive = archiveFile
    val forbidden =
      setOf("application-test.yml", "application-test.yaml", "application-test.properties")
    doLast {
      val leaked =
        ZipFile(archive.get().asFile).use { zip ->
          zip
            .entries()
            .asSequence()
            .map { it.name }
            .filter { it.substringAfterLast('/') in forbidden }
            .toList()
        }
      check(leaked.isEmpty()) {
        "${archive.get().asFile.name} contains the test profile ($leaked). Test-only " +
          "configuration belongs in src/test/resources, never in a shipped jar (SEC-17)."
      }
    }
  }

val testMaxHeap = moduleBuildSettings().testMaxHeap

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  maxHeapSize = testMaxHeap
  systemProperty("spring.profiles.active", "test")
}

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
  options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked", "-Xlint:deprecation"))
  options.release.convention(basetool.javaRelease)
}
