import de.greluc.krt.profit.basetool.conventions.lombokNotation

plugins { id("basetool.java-conventions") }

val delombokTool =
  configurations.register("delombokTool") {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Lombok's own jar, which runs delombok."
  }

dependencies.addProvider("delombokTool", lombokNotation())

val delombokDir = layout.buildDirectory.dir("delombok/main")

val delombok =
  tasks.register<JavaExec>("delombok") {
    group = "documentation"
    description = "Writes the main sources with the code Lombok generates expanded, for Javadoc."
    val mainSources = sourceSets.main.get().java.sourceDirectories
    val compileClasspath = sourceSets.main.get().compileClasspath
    val outputDir = delombokDir
    inputs
      .files(mainSources)
      .withPropertyName("sources")
      .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
      .files(compileClasspath)
      .withPropertyName("compileClasspath")
      .withNormalizer(ClasspathNormalizer::class.java)
    outputs.dir(outputDir)
    classpath = delombokTool.get()
    mainClass = "lombok.launch.Main"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
    argumentProviders.add(
      CommandLineArgumentProvider {
        listOf(
          "delombok",
          "--encoding=UTF-8",
          "--classpath=${compileClasspath.asPath}",
          "--target=${outputDir.get().asFile.absolutePath}",
        ) + mainSources.files.filter { it.exists() }.map { it.absolutePath }
      },
    )
    doFirst { outputDir.get().asFile.deleteRecursively() }
  }

tasks.javadoc {
  dependsOn(delombok)
  setSource(delombokDir)
  options { (this as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet") }
  destinationDir = project.file("docs/javadoc")
}
