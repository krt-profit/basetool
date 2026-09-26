buildscript { dependencies { constraints { classpath(libs.plexus.utils) } } }

plugins {
  id("idea")
  id("base")
  alias(libs.plugins.owasp.dependencycheck)
  alias(libs.plugins.pitest) apply false
  alias(libs.plugins.spotless)
  alias(libs.plugins.licensee) apply false
  alias(libs.plugins.spotbugs.base) apply false
  alias(libs.plugins.cyclonedx.bom) apply false
}

allprojects {
  group = "de.greluc.krt.profit.basetool"
  version = "0.0.1-SNAPSHOT"
}

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
  isEnforceCheck = true
  lineEndings = com.diffplug.spotless.LineEnding.UNIX
  val vendored =
    arrayOf(
      "**/build/**",
      "**/.gradle/**",
      "**/node_modules/**",
      "**/.claude/**",
      "**/.git/**",
      "ansible/collections/**",
      "ansible/.ansible/**",
    )
  fun sources(vararg includes: String) =
    fileTree(rootDir) {
      include(*includes)
      exclude(*vendored)
    }

  kotlinGradle {
    target(sources("**/*.gradle.kts"))
    ktfmt().googleStyle()
  }

  json {
    target(sources("**/*.json"))
    targetExclude(
      "**/src/test/**",
      "**/src/e2e/**",
      "backend/src/main/resources/api/openapi.json",
      "ingest/src/main/resources/api/openapi.json",
      "**/docs/*-bom.json",
      "**/package.json",
      "**/package-lock.json",
      "**/*.local.json",
    )
    gson().indentWithSpaces(2)
  }

  format("yaml") {
    target(sources("**/*.yml", "**/*.yaml"))
    trimTrailingWhitespace()
    endWithNewline()
  }

  format("markdown") {
    target(sources("**/*.md"))
    targetExclude("CHANGELOG.md", "CHANGELOG-ARCHIVE.md", "LICENSE.md", "CLAUDE.md")
    trimTrailingWhitespace()
    endWithNewline()
  }

  format("properties") {
    target(sources("**/*.properties"))
    targetExclude("versions.properties", "**/gradle-wrapper.properties")
    endWithNewline()
  }
}

val ossAllowedLicenses =
  listOf(
    "Apache-2.0",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "CC0-1.0",
    "GPL-2.0-with-classpath-exception",
    "LGPL-2.1-only",
    "MIT",
    "MIT-0",
    "MPL-2.0",
  )

val ossLicenseUrlAliases =
  mapOf(
    "http://www.eclipse.org/org/documents/edl-v10.php" to "BSD-3-Clause",
    "http://www.eclipse.org/legal/epl-2.0" to "EPL-2.0",
    "https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.txt" to "EPL-2.0",
    "https://github.com/resilience4j/resilience4j/blob/master/LICENSE.txt" to "Apache-2.0",
    "https://github.com/flyway/flyway/blob/main/README.txt" to "Apache-2.0",
    "https://repository.jboss.org/licenses/apache-2.0.txt" to "Apache-2.0",
    "http://www.apache.org/licenses/" to "Apache-2.0",
    "https://opensource.org/license/mit" to "MIT",
    "http://www.slf4j.org/license.html" to "MIT",
    "https://github.com/redis/lettuce/blob/main/LICENSE" to "MIT",
    "https://github.com/redis/redis-authx-core/blob/master/LICENSE" to "MIT",
    "https://www.antlr.org/license.html" to "BSD-3-Clause",
    "https://asm.ow2.io/license.html" to "BSD-3-Clause",
    "https://jdbc.postgresql.org/about/license.html" to "BSD-2-Clause",
    "https://www.mozilla.org/en-US/MPL/2.0/" to "MPL-2.0",
  )

val ossLicenseCoordinateOverrides =
  mapOf(
    "org.springframework.session:spring-session-core" to ("4.1.1" to "Apache-2.0"),
    "org.springframework.session:spring-session-data-redis" to ("4.1.1" to "Apache-2.0"),
  )

extra["ossLicenseUrlAliases"] = ossLicenseUrlAliases

extra["ossLicenseCoordinateOverrides"] =
  ossLicenseCoordinateOverrides.mapValues { (_, versionAndId) ->
    versionAndId.second
  }

subprojects {
  plugins.withId("java") {
    extensions.configure<JavaPluginExtension> {
      toolchain { languageVersion = JavaLanguageVersion.of(25) }
    }

    val lombok = provider {
      if (pluginManager.hasPlugin("io.spring.dependency-management")) "org.projectlombok:lombok"
      else "org.projectlombok:lombok:${libs.versions.lombok.get()}"
    }
    listOf("compileOnly", "annotationProcessor", "testCompileOnly", "testAnnotationProcessor")
      .forEach { dependencies.addProvider(it, lombok) }
    listOf("compileOnly", "testCompileOnly").forEach {
      dependencies.add(it, libs.jetbrains.annotations)
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
            java.util.zip.ZipFile(archive.get().asFile).use { zip ->
              zip
                .entries()
                .asSequence()
                .map { it.name }
                .filter {
                  it.substringAfterLast('/') in forbidden
                }
                .toList()
            }
          check(leaked.isEmpty()) {
            "${archive.get().asFile.name} contains the test profile ($leaked). Test-only " +
              "configuration belongs in src/test/resources, never in a shipped jar (SEC-17)."
          }
        }
      }

    tasks.withType<Test>().configureEach {
      useJUnitPlatform()
      jvmArgs("--enable-native-access=ALL-UNNAMED")
      maxHeapSize =
        mapOf("backend" to "3072m", "frontend" to "2048m", "ingest" to "1024m")[project.name]
          ?: "1024m"
      systemProperty("spring.profiles.active", "test")
    }

    tasks.withType<JavaCompile>().configureEach {
      options.encoding = "UTF-8"
      options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked", "-Xlint:deprecation"))
    }
  }

  plugins.withId("org.springframework.boot") {
    tasks.named<JavaExec>("bootRun") {
      jvmArgs("--enable-native-access=ALL-UNNAMED")
      systemProperty("spring.profiles.active", "dev")
    }

    extra["tomcat.version"] = "11.0.25"

    extra["netty.version"] = "4.2.18.Final"
  }

  plugins.withId("jacoco") {
    tasks.withType<Test>().configureEach { finalizedBy(tasks.named("jacocoTestReport")) }

    val generatedClassExcludes =
      listOf("**/*MapperImpl.class", "**/*MapperImpl\$*.class", "**/*Application.class")
    fun filterGenerated(classes: FileCollection): FileCollection =
      files(classes.files.map { dir -> fileTree(dir) { exclude(generatedClassExcludes) } })

    tasks.named<JacocoReport>("jacocoTestReport") {
      onlyIf { System.getenv("CI") != null }
      reports {
        xml.required.set(true)
        csv.required.set(true)
        html.required.set(true)
      }
      classDirectories.setFrom(filterGenerated(classDirectories))
    }

    val instructionFloor =
      mapOf(
        "backend" to "0.82",
        "frontend" to "0.60",
        "ingest" to "0.93",
        "keycloak-spi" to "0.66",
      )[project.name] ?: "0.50"
    val branchFloor =
      mapOf(
        "backend" to "0.65",
        "frontend" to "0.46",
        "ingest" to "0.85",
        "keycloak-spi" to "0.60",
      )[project.name] ?: "0.40"
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
      dependsOn(tasks.named("test"))
      classDirectories.setFrom(filterGenerated(classDirectories))
      violationRules {
        rule {
          element = "BUNDLE"
          limit {
            counter = "INSTRUCTION"
            value = "COVEREDRATIO"
            minimum = instructionFloor.toBigDecimal()
          }
          limit {
            counter = "BRANCH"
            value = "COVEREDRATIO"
            minimum = branchFloor.toBigDecimal()
          }
        }
      }
    }
    tasks.named("check").configure { dependsOn(tasks.named("jacocoTestCoverageVerification")) }
  }

  plugins.withId("info.solidsoft.pitest") {
    extensions.configure<info.solidsoft.gradle.pitest.PitestPluginExtension>("pitest") {
      junit5PluginVersion.set(libs.versions.pitestJunit5.get())
      targetClasses.set(listOf("de.greluc.krt.profit.basetool.${project.name}.service.*"))
      targetTests.set(listOf("de.greluc.krt.profit.basetool.${project.name}.service.*Test"))
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
  }

  plugins.withId("checkstyle") {
    extensions.configure<CheckstyleExtension>("checkstyle") {
      toolVersion = libs.versions.checkstyle.get()
      configFile = rootProject.file("config/checkstyle/google_checks.xml")
      isIgnoreFailures = false
      maxWarnings = 0
    }
    tasks
      .withType<Checkstyle>()
      .matching { it.name != "checkstyleMain" }
      .configureEach { configFile = rootProject.file("config/checkstyle/javadoc_position.xml") }
  }

  plugins.withId("com.diffplug.spotless") {
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension>("spotless") {
      isEnforceCheck = true
      lineEndings = com.diffplug.spotless.LineEnding.UNIX
      java {
        targetExclude("**/build/generated/**")
        googleJavaFormat(libs.versions.googleJavaFormat.get()).reflowLongStrings()
        removeUnusedImports()
        licenseHeader(
          """
          /*
           * Profit Basetool - squadron-management web app.
           * Copyright (C) 2026 Lucas Greuloch
           *
           * SPDX-License-Identifier: GPL-3.0-only
           *
           * This program is free software: you can redistribute it and/or modify
           * it under the terms of the GNU General Public License as published by
           * the Free Software Foundation, version 3.
           *
           * This program is distributed in the hope that it will be useful,
           * but WITHOUT ANY WARRANTY; without even the implied warranty of
           * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
           * GNU General Public License for more details.
           *
           * You should have received a copy of the GNU General Public License
           * along with this program.  If not, see <https://www.gnu.org/licenses/>.
           */

          """
            .trimIndent() + "\n"
        )
      }
    }
  }

  plugins.withId("org.cyclonedx.bom") {
    val sbomExplicitlyRequested =
      gradle.startParameter.taskNames.any { it.substringAfterLast(':').startsWith("cyclonedx") }
    val untrackedReason =
      "a release SBOM is always rebuilt from the live dependency graph: the plugin's inputs do " +
        "not see project dependencies, so UP-TO-DATE / FROM-CACHE could ship a stale one"
    tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
      enabled = sbomExplicitlyRequested
      includeConfigs.set(listOf("^runtimeClasspath$"))
      doNotTrackState(untrackedReason)
    }
    val cyclonedxBom =
      tasks.named<org.cyclonedx.gradle.BaseCyclonedxTask>("cyclonedxBom") {
        enabled = sbomExplicitlyRequested
        schemaVersion.set(org.cyclonedx.Version.VERSION_16)
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
      description =
        "Fails when the CycloneDX BOM does not list exactly the resolved runtimeClasspath."
      enabled = sbomExplicitlyRequested
      val bomFile = cyclonedxBom.flatMap { it.jsonOutput }
      val projectPath = project.path
      inputs.file(bomFile).withPropertyName("bom").withPathSensitivity(PathSensitivity.NONE)
      inputs.property("expectedComponents", expectedComponents)
      doLast {
        val bom = groovy.json.JsonSlurper().parse(bomFile.get().asFile) as Map<*, *>
        val listed =
          (bom["components"] as? List<*>)
            .orEmpty()
            .filterIsInstance<Map<*, *>>()
            .map { "${it["group"]}:${it["name"]}:${it["version"]}" }
            .toSet()
        val expected = expectedComponents.get().toSet()
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
            }
          )
        }
      }
    }
  }

  plugins.withId("com.github.spotbugs-base") {
    dependencies.add("spotbugsPlugins", libs.findsecbugs.plugin)
    val main = project.extensions.getByType<SourceSetContainer>().named("main")
    val spotbugsMain =
      tasks.register<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain") {
        group = "verification"
        description = "Runs SpotBugs analysis on the main source set."
        sourceDirs.from(main.map { it.allSource.sourceDirectories })
        classDirs.from(main.map { it.output.classesDirs })
        auxClassPaths.from(main.map { it.compileClasspath })
        pluginJarFiles.from(configurations.named("spotbugsPlugins"))
        effort.set(com.github.spotbugs.snom.Effort.DEFAULT)
        reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
        ignoreFailures = false
        reports.create("xml") {
          required.set(true)
          outputLocation.set(layout.buildDirectory.file("reports/spotbugs/main.xml"))
        }
        dependsOn("classes")
      }
    tasks.named("check").configure { dependsOn(spotbugsMain) }
  }

  plugins.withId("app.cash.licensee") {
    configure<app.cash.licensee.LicenseeExtension> {
      ossAllowedLicenses.forEach { allow(it) }
      ossLicenseUrlAliases
        .filterValues { it in ossAllowedLicenses }
        .forEach { (url, spdxId) -> allowUrl(url) { because("the linked text is $spdxId") } }
      ossLicenseCoordinateOverrides.forEach { (coordinate, versionAndId) ->
        val (group, artifact) = coordinate.split(':')
        allowDependency(group, artifact, versionAndId.first) {
          because("POM names no usable licence; the jar's LICENSE.txt is ${versionAndId.second}")
        }
      }
      unusedAction(app.cash.licensee.UnusedAction.IGNORE)
    }

    val licenseeReport = tasks.named<app.cash.licensee.LicenseeTask>("licensee")
    val moduleName = project.name
    val exportLicenseeReport =
      tasks.register<Copy>("exportLicenseeReport") {
        description = "Copies this module's Licensee report under the module's own name."
        from(licenseeReport.flatMap { it.jsonOutput })
        into(layout.buildDirectory.dir("licensee-export"))
        rename { "$moduleName.json" }
      }
    configurations.consumable("ossLicenseReportElements") {
      attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("oss-license-report")) }
      outgoing.artifact(
        exportLicenseeReport.map { it.destinationDir.resolve("$moduleName.json") }
      ) {
        builtBy(exportLicenseeReport)
      }
    }
  }
}

tasks.register("generateTermsVersion") {
  description = "Regenerates the committed Terms-of-Use version from the German message bundle."
  val bundle = rootProject.file("backend/src/main/resources/messages_de.properties")
  val target = rootProject.file("backend/src/main/resources/terms-version.properties")
  val override = providers.gradleProperty("termsVersion").filter { it.isNotBlank() }
  val targetForLog = target.relativeTo(rootDir).path
  outputs.upToDateWhen { false }
  doLast {
    val version =
      override.orNull
        ?: run {
          val clauses =
            bundle
              .readLines(Charsets.UTF_8)
              .filter { it.startsWith("terms.") && it.contains('=') }
              .sorted()
              .joinToString(separator = "\n")
          check(clauses.isNotEmpty()) { "No terms.* entries found in ${bundle.path}" }
          check(clauses.lines().none { it.endsWith("\\") }) {
            "A terms.* entry in ${bundle.path} uses a backslash line continuation. The digest " +
              "only sees the first line, so edits to the rest would not re-prompt anyone. Put " +
              "the clause on one line."
          }
          java.security.MessageDigest.getInstance("SHA-256")
            .digest(clauses.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 16)
        }
    target.parentFile.mkdirs()
    target.writeText("basetool.terms.version=" + version + "\n", Charsets.UTF_8)
    logger.lifecycle("Terms-of-Use version: $version -> $targetForLog")
  }
}

dependencyCheck {
  failBuildOnCVSS = 7.0f
  formats = listOf("HTML", "SARIF")
  outputDirectory.set(layout.buildDirectory.dir("reports/dependency-check"))
  suppressionFile = rootProject.file("config/owasp/dependency-check-suppressions.xml").absolutePath
  setConnectionTimeout(java.time.Duration.ofSeconds(30))
  setReadTimeout(java.time.Duration.ofSeconds(120))
  nvd.validForHours = 168
  val resolvedNvdApiKey = (project.findProperty("nvdApiKey") as String?)?.takeIf { it.isNotBlank() }
  if (resolvedNvdApiKey != null) {
    nvd.apiKey = resolvedNvdApiKey
    nvd.delay = 0
  } else {
    nvd.delay = 16000
  }
}
