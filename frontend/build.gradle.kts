import com.github.gradle.node.npm.task.NpmTask
import com.github.gradle.node.npm.task.NpxTask
import com.github.gradle.node.task.NodeTask
import de.greluc.krt.profit.basetool.conventions.OssLicensePolicy
import de.greluc.krt.profit.basetool.conventions.backendModuleDirectories

buildscript {
  dependencies {
    constraints {
      classpath(libs.handlebars)
      classpath(libs.jackson2.plugins.databind)
      classpath(libs.jackson3.databind)
    }
  }
}

plugins {
  id("basetool.spring-app-conventions")
  id("basetool.quality-conventions")
  id("basetool.mutation-conventions")
  id("basetool.supply-chain-conventions")
  alias(libs.plugins.node.gradle)
  alias(libs.plugins.openapi.generator)
}

description = "frontend"

val generatedContract = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
  generatorName.set("java")
  inputSpec.set(
    layout.settingsDirectory.file("backend/src/main/resources/api/openapi.json"),
  )
  cleanupOutput.set(true)
  outputDir.set(generatedContract)
  modelPackage.set("de.greluc.krt.profit.basetool.frontend.contract.model")
  apiPackage.set("de.greluc.krt.profit.basetool.frontend.contract.api")
  packageName.set("de.greluc.krt.profit.basetool.frontend.contract")
  globalProperties.set(mapOf("models" to "", "modelDocs" to "false", "modelTests" to "false"))
  configOptions.set(
    mapOf(
      "library" to "native",
      "serializationLibrary" to "jackson",
      "useJakartaEe" to "true",
      "openApiNullable" to "false",
      "hideGenerationTimestamp" to "true",
      "supportUrlQuery" to "false",
      "sourceFolder" to "src/main/java",
    ),
  )
}

tasks.openApiGenerate.configure {
  inputs
    .file(layout.settingsDirectory.file("backend/src/main/resources/api/openapi.json"))
    .withPropertyName("openapiSpecFile")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}

sourceSets.named("test") { java.srcDir(generatedContract.map { it.dir("src/main/java") }) }

spotless { java { target("src/**/*.java") } }

tasks.named<JavaCompile>("compileTestJava") { dependsOn(tasks.openApiGenerate) }

val projectVersion = project.version.toString()
val gitDescribe = providers.exec {
  commandLine("git", "describe", "--tags", "--always", "--dirty")
  workingDir = rootDir
  isIgnoreExitValue = true
}
val resolvedAppVersion: Provider<String> =
  providers
    .gradleProperty("appVersion")
    .filter { it.isNotBlank() }
    .orElse(
      provider {
        runCatching {
          gitDescribe.result
            .get()
            .exitValue
            .takeIf { it == 0 }
            ?.let {
              gitDescribe.standardOutput.asText.get().trim().takeIf { out -> out.isNotBlank() }
            }
        }
          .getOrNull() ?: projectVersion
      },
    )
    .map { it.removePrefix("v") }

springBoot {
  buildInfo {
    properties {
      version.set(resolvedAppVersion)
    }
  }
}

normalization { runtimeClasspath { ignore("META-INF/build-info.properties") } }

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("tools.jackson.dataformat:jackson-dataformat-cbor")
  implementation("com.fasterxml.jackson.core:jackson-databind")
  implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
  implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
  implementation("org.springframework.boot:spring-boot-starter-websocket")
  implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-cache")
  implementation("com.github.ben-manes.caffeine:caffeine")
  implementation(libs.commonmark.core)
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("io.micrometer:micrometer-registry-prometheus")
  implementation("org.springframework.boot:spring-boot-starter-opentelemetry") {
    exclude(group = "io.micrometer", module = "micrometer-registry-otlp")
  }
  implementation("org.springframework.session:spring-session-data-redis")
  implementation("org.springframework.boot:spring-boot-starter-data-redis")
  implementation(libs.resilience4j.spring.boot3)
  implementation(libs.resilience4j.reactor)
  implementation(libs.micrometer.context.propagation)
  implementation(libs.logstash.logback.encoder)
  implementation(project(":logging-support"))

  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

  "mockitoAgent"("org.mockito:mockito-core")

  testImplementation(project(":test-support"))
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
  testImplementation("org.springframework.security:spring-security-test")
  testImplementation("io.projectreactor:reactor-test")
  testImplementation(libs.okhttp3.mockwebserver)
  testImplementation(libs.okhttp3.tls)
  testImplementation(libs.archunit.core)
  testImplementation(libs.testcontainers.junit)
}

tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
  skipConfigs.set(listOf("^e2e.*"))
}

val ossLicenseReports = configurations.dependencyScope("ossLicenseReports")
val ossLicenseReportFiles =
  configurations.resolvable("ossLicenseReportFiles") {
    extendsFrom(ossLicenseReports.get())
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("oss-license-report")) }
  }

dependencies {
  "ossLicenseReports"(project(":backend"))
  "ossLicenseReports"(project(":ingest"))
  "ossLicenseReports"(project(":keycloak-spi"))
}

val generatedOssLicenses = layout.buildDirectory.dir("generated/oss-licenses")

val generateOssLicenses =
  tasks.register("generateOssLicenses") {
    group = "build"
    description = "Merges the shipped modules' Licensee reports into oss/oss-licenses.json."

    val moduleReports =
      files(
        ossLicenseReportFiles,
        tasks.named("exportLicenseeReport").map { it.outputs.files.asFileTree },
      )
    val bundledComponents = file("oss-bundled-components.json")
    val urlAliases = OssLicensePolicy.urlAliases
    val coordinateOverrides = OssLicensePolicy.overrideLicenses
    val generator = "licensee ${libs.versions.licensee.get()}"
    val outputFile = generatedOssLicenses.map { it.file("oss/oss-licenses.json") }

    inputs
      .files(moduleReports)
      .withPropertyName("moduleReports")
      .withPathSensitivity(PathSensitivity.NAME_ONLY)
    inputs
      .file(bundledComponents)
      .withPropertyName("bundledComponents")
      .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("urlAliases", urlAliases)
    inputs.property("coordinateOverrides", coordinateOverrides)
    inputs.property("generator", generator)
    outputs.dir(generatedOssLicenses)

    doLast {
      val slurper = groovy.json.JsonSlurper()

      val spdxList =
        app.cash.licensee.LicenseeTask::class
          .java
          .classLoader
          .getResource("app/cash/licensee/licenses.json")
          ?.let { slurper.parse(it) as Map<*, *> }
          ?: throw GradleException("Licensee's bundled SPDX list is missing; cannot name licences")
      val spdx =
        (spdxList["licenses"] as List<*>).filterIsInstance<Map<*, *>>().associate {
          val link = (it["seeAlso"] as? List<*>)?.firstOrNull() ?: it["reference"]
          it["licenseId"] as String to (it["name"] as String to link as String?)
        }
      fun spdxLicense(id: String): Map<String, String?> {
        val (name, link) =
          spdx[id] ?: throw GradleException("'$id' is not an SPDX identifier Licensee knows")
        return mapOf("spdxId" to id, "name" to name, "url" to link)
      }

      val components = sortedMapOf<String, MutableMap<String, Any?>>()
      moduleReports.files
        .sortedBy { it.name }
        .forEach { report ->
          val module = report.nameWithoutExtension
          (slurper.parse(report) as List<*>).filterIsInstance<Map<*, *>>().forEach { artifact ->
            val name = "${artifact["groupId"]}:${artifact["artifactId"]}"
            val version = artifact["version"] as String
            val entry =
              components.getOrPut("$name:$version") {
                val override = coordinateOverrides[name]
                val licenses =
                  if (override != null) {
                    listOf(spdxLicense(override))
                  } else {
                    val known =
                      (artifact["spdxLicenses"] as? List<*>)
                        .orEmpty()
                        .filterIsInstance<Map<*, *>>()
                        .map {
                          mapOf(
                            "spdxId" to it["identifier"],
                            "name" to it["name"],
                            "url" to it["url"],
                          )
                        }
                    val unknown =
                      (artifact["unknownLicenses"] as? List<*>)
                        .orEmpty()
                        .filterIsInstance<Map<*, *>>()
                        .map {
                          val alias = urlAliases[it["url"]]
                          if (alias != null) {
                            spdxLicense(alias)
                          } else {
                            mapOf("spdxId" to null, "name" to it["name"], "url" to it["url"])
                          }
                        }
                    (known + unknown).distinctBy { it["spdxId"] ?: it["name"] }
                  }
                mutableMapOf(
                  "name" to name,
                  "version" to version,
                  "title" to artifact["name"],
                  "url" to (artifact["scm"] as? Map<*, *>)?.get("url"),
                  "modules" to sortedSetOf<String>(),
                  "licenses" to licenses,
                )
              }
            @Suppress("UNCHECKED_CAST") (entry["modules"] as MutableSet<String>).add(module)
          }
        }

      val bundled =
        ((slurper.parse(bundledComponents) as Map<*, *>)["components"] as List<*>).filterIsInstance<
          Map<*, *>,
        >()

      val out = outputFile.get().asFile
      out.parentFile.mkdirs()
      out.writeText(
        groovy.json.JsonOutput.prettyPrint(
          groovy.json.JsonOutput.toJson(
            mapOf(
              "generator" to generator,
              "components" to components.values + bundled,
            ),
          ),
        ),
        Charsets.UTF_8,
      )
      logger.lifecycle(
        "generateOssLicenses: ${components.size} libraries + ${bundled.size} bundled components",
      )
    }
  }

sourceSets.named("main") { resources.srcDir(generateOssLicenses) }

val backendDtoMirrorTrees = backendModuleDirectories().map { it.dir("src/main/java") }

val updateSnapshots = providers.gradleProperty("updateSnapshots").map { "true" }.orElse("false")

tasks.named<Test>("test") {
  systemProperty("basetool.updateSnapshots", updateSnapshots.get())

  inputs
    .files(backendDtoMirrorTrees.map { fileTree(it) { include("**/*.java") } })
    .withPropertyName("backendDtoMirrorSources")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .file(layout.settingsDirectory.file("backend/src/main/resources/api/openapi.json"))
    .withPropertyName("backendOpenApiDocument")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .file(layout.settingsDirectory.file("backend/src/main/resources/application-prod.yml"))
    .withPropertyName("backendProdConfig")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .file(layout.settingsDirectory.file("docker/test-tls/basetool-test-backend.p12"))
    .withPropertyName("testTlsKeystore")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .files(
      layout.settingsDirectory.file(
        "frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/E2eStackExtension.java",
      ),
      layout.settingsDirectory.file("frontend/src/e2e/resources/realm-export.e2e.json"),
    )
    .withPropertyName("e2eAudienceParitySources")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .files(fileTree("src/e2e/java") { include("**/*.java") })
    .withPropertyName("e2eSourcesForTestProfileScan")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  inputs
    .files(
      fileTree("src/main/resources/static/fonts"),
      fileTree(layout.settingsDirectory.dir("backend/src/main/resources/fonts")),
      fileTree(layout.settingsDirectory.dir("keycloak-theme")),
      file("oss-bundled-components.json"),
    )
    .withPropertyName("ossBundledComponentSources")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}

val cssSourceDir = layout.projectDirectory.dir("src/main/resources/static/css")
val minifiedCssDir = layout.buildDirectory.dir("generated/minified-css")

val minifyStaticCss =
  tasks.register("minifyStaticCss") {
    group = "build"
    description = "Writes minified copies of the CSS sources for processResources to ship (L-2)."

    val sourceRoot = cssSourceDir.asFile
    val outputDir = minifiedCssDir
    inputs
      .files(fileTree(cssSourceDir) { include("**/*.css") })
      .withPropertyName("cssSources")
      .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(outputDir)

    doLast {
      val outputRoot = outputDir.get().asFile
      outputRoot.deleteRecursively()
      val blockComment = Regex("""/\*[\s\S]*?\*/""")
      var totalBefore = 0L
      var totalAfter = 0L
      sourceRoot
        .walkTopDown()
        .filter { it.isFile && it.extension == "css" }
        .forEach { source ->
          val original = source.readText(Charsets.UTF_8)
          val withoutComments = blockComment.replace(original, "")
          val minified =
            withoutComments
              .lineSequence()
              .map { it.trim() }
              .filter { it.isNotEmpty() }
              .joinToString(separator = "\n")
              .plus("\n")
          val target = outputRoot.resolve(source.relativeTo(sourceRoot).path)
          target.parentFile.mkdirs()
          target.writeText(minified, Charsets.UTF_8)
          totalBefore += original.toByteArray(Charsets.UTF_8).size.toLong()
          totalAfter += minified.toByteArray(Charsets.UTF_8).size.toLong()
        }
      if (totalBefore > 0) {
        val pct = 100.0 * (totalBefore - totalAfter) / totalBefore
        logger.lifecycle(
          "minifyStaticCss: ${totalBefore} -> ${totalAfter} bytes (-${"%.1f".format(pct)}%)",
        )
      }
    }
  }

tasks.named<ProcessResources>("processResources") {
  val originalCss = cssSourceDir.asFile
  exclude { it.file.extension == "css" && it.file.startsWith(originalCss) }
  from(minifyStaticCss) { into("static/css") }
}

sourceSets { create("e2e") }

configurations["e2eImplementation"].extendsFrom(configurations["testImplementation"])

configurations["e2eRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
  "e2eImplementation"(libs.playwright)
  "e2eImplementation"(libs.axe.core.playwright)
  "e2eImplementation"("org.postgresql:postgresql")
  "e2eRuntimeOnly"("org.junit.platform:junit-platform-launcher")
  "e2eCompileOnly"("org.projectlombok:lombok")
  "e2eAnnotationProcessor"("org.projectlombok:lombok")
  "e2eCompileOnly"(libs.jetbrains.annotations)
}

val playwrightInstall =
  tasks.register<JavaExec>("playwrightInstall") {
    group = "verification"
    description =
      "Installs the Playwright browsers for e2eTest/smokeTest (all three, or -Pe2e.browser only)."
    classpath = sourceSets["e2e"].runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    val withDeps =
      System.getenv("CI") == "true" &&
        System.getProperty("os.name").orEmpty().lowercase().contains("linux")
    val allBrowsers = listOf("chromium", "firefox", "webkit")
    val requested = (findProperty("e2e.browser") as String?)?.trim()?.lowercase()
    val browsers =
      when {
        requested.isNullOrEmpty() -> allBrowsers
        requested in allBrowsers -> listOf(requested)
        else ->
          throw GradleException(
            "-Pe2e.browser=$requested is not one of $allBrowsers; nothing would be installed for it",
          )
      }
    setArgs(
      if (withDeps) listOf("install", "--with-deps") + browsers else listOf("install") + browsers,
    )
  }

val playwrightSuiteConfig: Test.() -> Unit = {
  group = "verification"
  testClassesDirs = sourceSets["e2e"].output.classesDirs
  classpath = sourceSets["e2e"].runtimeClasspath
  dependsOn(playwrightInstall)
  dependsOn("minifyStaticCss")
  outputs.upToDateWhen { false }
  outputs.cacheIf { false }
  environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
  mapOf("E2E_USERNAME" to "e2e.username", "E2E_PASSWORD" to "e2e.password").forEach { (env, prop) ->
    System.getenv(env)?.takeIf { it.isNotBlank() }?.let { systemProperty(prop, it) }
  }
  listOf(
      "e2e.baseUrl",
      "e2e.browser",
      "e2e.device",
      "e2e.username",
      "e2e.password",
      "e2e.hostResolverRules",
      "e2e.prebuilt",
    )
    .forEach { key -> (findProperty(key) as String?)?.let { systemProperty(key, it) } }
}

tasks.register<Test>("e2eTest") {
  description =
    "Runs the destructive Playwright e2e flows against an isolated stack (JUnit tag: e2e)."
  playwrightSuiteConfig()
  useJUnitPlatform { includeTags("e2e") }
}

tasks.register<Test>("smokeTest") {
  description =
    "Runs the non-destructive Playwright smoke checks (JUnit tag: smoke); set E2E_BASE_URL for staging."
  playwrightSuiteConfig()
  useJUnitPlatform { includeTags("smoke") }
}

node {
  version.set(libs.versions.node.get())
  download.set(true)
  distBaseUrl.set(null as String?)
  npmInstallCommand.set("ci")
}

val lintCss =
  tasks.register<NpxTask>("lintCss") {
    group = "verification"
    description = "Lints CSS sources with Stylelint (strict; fails the build on findings)."
    dependsOn(tasks.named("npmInstall"))
    command.set("stylelint")
    args.set(listOf("src/main/resources/static/css/**/*.css"))
    ignoreExitValue.set(false)
    inputs.files(fileTree("src/main/resources/static/css") { include("**/*.css") })
    inputs.file("package.json")
    inputs.file(".stylelintrc.json")
  }

val lintCssInline =
  tasks.register<NpxTask>("lintCssInline") {
    group = "verification"
    description = "Lints any Thymeleaf <style> block with Stylelint's template rule set."
    dependsOn(tasks.named("npmInstall"))
    command.set("stylelint")
    args.set(
      listOf(
        "--config",
        ".stylelintrc.templates.json",
        "src/main/resources/templates/**/*.html",
      ),
    )
    ignoreExitValue.set(false)
    inputs.files(fileTree("src/main/resources/templates") { include("**/*.html") })
    inputs.file("package.json")
    inputs.file(".stylelintrc.templates.json")
  }

val probeSource =
  layout.projectDirectory.file(
    "src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/TouchClassLayoutE2eTest.java",
  )
val extractProbeScript = layout.projectDirectory.file("scripts/extract-probe-js.mjs")
val extractedProbe = layout.buildDirectory.file("probe/probe.js")

val extractProbeJs =
  tasks.register<NodeTask>("extractProbeJs") {
    group = "verification"
    description = "Extracts the e2e probe script out of its Java text block so eslint can read it."
    dependsOn(tasks.named("npmSetup"))
    script.set(extractProbeScript.asFile)
    args.set(listOf(probeSource.asFile.absolutePath, extractedProbe.get().asFile.absolutePath))
    ignoreExitValue.set(false)
    inputs.file(probeSource)
    inputs.file(extractProbeScript)
    outputs.file(extractedProbe)
  }

val lintProbeJs =
  tasks.register<NpxTask>("lintProbeJs") {
    group = "verification"
    description = "Lints the extracted e2e probe script (Java text blocks hide JavaScript defects)."
    dependsOn(tasks.named("npmInstall"), extractProbeJs)
    command.set("eslint")
    args.set(
      listOf(
        "--no-config-lookup",
        "--config",
        "eslint.probe.config.mjs",
        extractedProbe.get().asFile.absolutePath,
      ),
    )
    ignoreExitValue.set(false)
    inputs.file(extractedProbe)
    inputs.file("eslint.probe.config.mjs")
  }

val lintHtml =
  tasks.register<NpxTask>("lintHtml") {
    group = "verification"
    description =
      "Lints Thymeleaf HTML templates with HTMLHint (strict; fails the build on findings)."
    dependsOn(tasks.named("npmInstall"))
    command.set("htmlhint")
    args.set(listOf("src/main/resources/templates/**/*.html"))
    ignoreExitValue.set(false)
    inputs.files(fileTree("src/main/resources/templates") { include("**/*.html") })
    inputs.file("package.json")
    inputs.file(".htmlhintrc")
  }

val lintJs =
  tasks.register<NpxTask>("lintJs") {
    group = "verification"
    description =
      "Lints the browser scripts and the Node scripts with ESLint (strict; fails on findings)."
    dependsOn(tasks.named("npmInstall"))
    command.set("eslint")
    args.set(listOf("src/main/resources/static/js/**/*.js", "scripts/**/*.mjs"))
    ignoreExitValue.set(false)
    inputs.files(fileTree("src/main/resources/static/js") { include("**/*.js") })
    inputs.files(fileTree("scripts") { include("**/*.mjs") })
    inputs.file("package.json")
    inputs.file("eslint.config.mjs")
    inputs.file("module-ratchet.json")
  }

val prettierCheck =
  tasks.register<NpxTask>("prettierCheck") {
    group = "verification"
    description = "Checks CSS/JS formatting with Prettier (strict; fails the build on findings)."
    dependsOn(tasks.named("npmInstall"))
    command.set("prettier")
    args.set(
      listOf(
        "--check",
        "src/main/resources/static/css/**/*.css",
        "src/main/resources/static/js/**/*.js",
        "types/**/*.d.ts",
        "scripts/**/*.mjs",
      ),
    )
    ignoreExitValue.set(false)
    inputs.files(fileTree("src/main/resources/static/css") { include("**/*.css") })
    inputs.files(
      fileTree("src/main/resources/static/js") {
        include("**/*.js")
        exclude("vendor/**")
      },
    )
    inputs.files(fileTree("types") { include("**/*.d.ts") })
    inputs.files(fileTree("scripts") { include("**/*.mjs") })
    inputs.file("package.json")
    inputs.file(".prettierrc.json")
    inputs.file(".prettierignore")
  }

tasks.register<NpxTask>("prettierApply") {
  group = "formatting"
  description = "Reformats the hand-written CSS/JS in place with Prettier."
  dependsOn(tasks.named("npmInstall"))
  command.set("prettier")
  args.set(
    listOf(
      "--write",
      "src/main/resources/static/css/**/*.css",
      "src/main/resources/static/js/**/*.js",
      "types/**/*.d.ts",
      "scripts/**/*.mjs",
    ),
  )
  ignoreExitValue.set(false)
}

val openApiSpec = layout.settingsDirectory.file("backend/src/main/resources/api/openapi.json")
val generatedApiTypes = layout.buildDirectory.file("generated/ts/api.d.ts")

val generateApiTypesScript = layout.projectDirectory.file("scripts/gen-api-types.mjs")

val generateApiTypes =
  tasks.register<NodeTask>("generateApiTypes") {
    group = "build"
    description =
      "Generates TypeScript types for the backend DTOs from the OpenAPI spec (build output)."
    dependsOn(tasks.named("npmSetup"))
    script.set(generateApiTypesScript.asFile)
    args.set(listOf(openApiSpec.asFile.absolutePath, generatedApiTypes.get().asFile.absolutePath))
    ignoreExitValue.set(false)
    inputs.file(openApiSpec)
    inputs.file(generateApiTypesScript)
    outputs.file(generatedApiTypes)
  }

val typecheckJs =
  tasks.register<NpxTask>("typecheckJs") {
    group = "verification"
    description =
      "Type-checks every browser script with tsc --noEmit (strict, checkJs, noImplicitAny; fails on findings)."
    dependsOn(tasks.named("npmInstall"), generateApiTypes)
    command.set("tsc")
    args.set(listOf("-p", "tsconfig.json"))
    ignoreExitValue.set(false)
    inputs.files(
      fileTree("src/main/resources/static/js") {
        include("**/*.js")
        exclude("vendor/**")
      },
    )
    inputs.files(fileTree("types") { include("**/*.d.ts") })
    inputs.file(generatedApiTypes)
    inputs.file("package.json")
    inputs.file("tsconfig.json")
  }

val testTypecheckRatchet =
  tasks.register<NodeTask>("testTypecheckRatchet") {
    group = "verification"
    description =
      "Holds the type-check ratchet: every script has a domain, the strict options stay on, and the ratchet fires on planted trees."
    dependsOn(tasks.named("npmSetup"))
    script.set(layout.projectDirectory.file("scripts/typecheck-ratchet.test.mjs").asFile)
    ignoreExitValue.set(false)
    inputs.file(layout.projectDirectory.file("scripts/typecheck-ratchet.test.mjs"))
    inputs.file(layout.projectDirectory.file("scripts/typecheck-ratchet.mjs"))
    inputs.file(layout.projectDirectory.file("typecheck-ratchet.json"))
    inputs.file(layout.projectDirectory.file("tsconfig.json"))
    inputs.files(
      fileTree("src/main/resources/static/js") {
        include("*.js")
      },
    )
    outputs.upToDateWhen { false }
  }

val testGenApiTypes =
  tasks.register<NodeTask>("testGenApiTypes") {
    group = "verification"
    description = "Regression tests for the OpenAPI -> .d.ts emitter (scripts/gen-api-types.mjs)."
    dependsOn(tasks.named("npmSetup"))
    script.set(layout.projectDirectory.file("scripts/gen-api-types.test.mjs").asFile)
    ignoreExitValue.set(false)
    inputs.file(generateApiTypesScript)
    inputs.file(layout.projectDirectory.file("scripts/gen-api-types.test.mjs"))
    outputs.upToDateWhen { false }
  }

val testLiveSyncJs =
  tasks.register<NodeTask>("testLiveSyncJs") {
    group = "verification"
    description =
      "Regression tests for the live-sync socket script (static/js/kernel/krt-live-sync.js)."
    dependsOn(tasks.named("npmSetup"))
    script.set(layout.projectDirectory.file("scripts/krt-live-sync.test.mjs").asFile)
    ignoreExitValue.set(false)
    inputs.file(layout.projectDirectory.file("scripts/krt-live-sync.test.mjs"))
    inputs.file(
      layout.projectDirectory.file("src/main/resources/static/js/kernel/krt-live-sync.js"),
    )
    outputs.upToDateWhen { false }
  }

val testPromotionCsvEscape =
  tasks.register<NodeTask>("testPromotionCsvEscape") {
    group = "verification"
    description = "Regression tests for the promotion CSV export's formula-injection escaper."
    dependsOn(tasks.named("npmSetup"))
    script.set(layout.projectDirectory.file("scripts/promotion-csv-escape.test.mjs").asFile)
    ignoreExitValue.set(false)
    inputs.file(layout.projectDirectory.file("scripts/promotion-csv-escape.test.mjs"))
    inputs.file(
      layout.projectDirectory.file("src/main/resources/static/js/promotion/promotion-manage.js"),
    )
    outputs.upToDateWhen { false }
  }

val testKrtFetchReadJs =
  tasks.register<NodeTask>("testKrtFetchReadJs") {
    group = "verification"
    description = "Regression tests for the one read path, krtFetch.get / getJson (REQ-FE-031)."
    dependsOn(tasks.named("npmSetup"))
    script.set(layout.projectDirectory.file("scripts/krt-fetch-read.test.mjs").asFile)
    ignoreExitValue.set(false)
    inputs.file(layout.projectDirectory.file("scripts/krt-fetch-read.test.mjs"))
    inputs.file(layout.projectDirectory.file("src/main/resources/static/js/kernel/krt-fetch.js"))
    outputs.upToDateWhen { false }
  }

val testTrustedTypesJs =
  tasks.register<NodeTask>("testTrustedTypesJs") {
    group = "verification"
    description =
      "Regression tests for the krt-html builder and the krt-fragment policy (ADR-0239)."
    dependsOn(tasks.named("npmSetup"))
    script.set(layout.projectDirectory.file("scripts/trusted-types.test.mjs").asFile)
    ignoreExitValue.set(false)
    inputs.file(layout.projectDirectory.file("scripts/trusted-types.test.mjs"))
    inputs.file(layout.projectDirectory.file("src/main/resources/static/js/kernel/krt-html.js"))
    inputs.file(layout.projectDirectory.file("src/main/resources/static/js/kernel/krt-fetch.js"))
    outputs.upToDateWhen { false }
  }

val testClientErrorCspJs =
  tasks.register<NodeTask>("testClientErrorCspJs") {
    group = "verification"
    description =
      "Regression tests for the client-error beacon's CSP violation report (REQ-SEC-064, REQ-FE-022)."
    dependsOn(tasks.named("npmSetup"))
    script.set(layout.projectDirectory.file("scripts/client-error-csp.test.mjs").asFile)
    ignoreExitValue.set(false)
    inputs.file(layout.projectDirectory.file("scripts/client-error-csp.test.mjs"))
    inputs.file(
      layout.projectDirectory.file("src/main/resources/static/js/kernel/krt-client-error.js"),
    )
    outputs.upToDateWhen { false }
  }

val testSafeUrl =
  tasks.register<NodeTask>("testSafeUrl") {
    group = "verification"
    description =
      "Regression tests for safeSameOriginUrl, the validator between a DOM-read URL and a form action."
    dependsOn(tasks.named("npmSetup"))
    script.set(layout.projectDirectory.file("scripts/safe-url.test.mjs").asFile)
    ignoreExitValue.set(false)
    inputs.file(layout.projectDirectory.file("scripts/safe-url.test.mjs"))
    inputs.file(layout.projectDirectory.file("scripts/flatten-module.mjs"))
    inputs.file(layout.projectDirectory.file("src/main/resources/static/js/kernel/safe-url.js"))
    outputs.upToDateWhen { false }
  }

val testTableSort =
  tasks.register<NodeTask>("testTableSort") {
    group = "verification"
    description =
      "Regression tests for krtTableSort, the shared sortable table headers: value order, click cycle and aria-sort."
    dependsOn(tasks.named("npmSetup"))
    script.set(layout.projectDirectory.file("scripts/krt-table-sort.test.mjs").asFile)
    ignoreExitValue.set(false)
    inputs.file(layout.projectDirectory.file("scripts/krt-table-sort.test.mjs"))
    inputs.file(layout.projectDirectory.file("scripts/flatten-module.mjs"))
    inputs.file(
      layout.projectDirectory.file("src/main/resources/static/js/kernel/krt-table-sort.js"),
    )
    outputs.upToDateWhen { false }
  }

val testEslintBans =
  tasks.register<NodeTask>("testEslintBans") {
    group = "verification"
    description =
      "Proves the ESLint transport, browser-baseline and Trusted Types sink bans fire on planted sources."
    dependsOn(tasks.named("npmInstall"))
    script.set(layout.projectDirectory.file("scripts/eslint-bans.test.mjs").asFile)
    ignoreExitValue.set(false)
    inputs.file(layout.projectDirectory.file("scripts/eslint-bans.test.mjs"))
    inputs.file(layout.projectDirectory.file("eslint.config.mjs"))
    inputs.file(layout.projectDirectory.file("module-ratchet.json"))
    inputs.file(layout.projectDirectory.file("package.json"))
    outputs.upToDateWhen { false }
  }

val repositoryRoot = layout.settingsDirectory
val exchangeDocs = repositoryRoot.dir("docs/exchange")
val markdownlintTool = repositoryRoot.dir(".github/tools/markdownlint")
val pythonExecutable =
  (findProperty("pythonExecutable") as String?)
    ?: if (System.getProperty("os.name").orEmpty().lowercase().contains("windows")) "python"
    else "python3"

val installExchangeDocsMarkdownlint =
  tasks.register<NpmTask>("installExchangeDocsMarkdownlint") {
    group = "verification"
    description =
      "Installs the pinned markdownlint-cli of the exchange-docs workflow from its lockfile."
    dependsOn(tasks.named("npmSetup"))
    workingDir.set(markdownlintTool.asFile)
    npmCommand.set(listOf("ci"))
    args.set(listOf("--ignore-scripts", "--no-audit", "--no-fund"))
    inputs.file(markdownlintTool.file("package.json"))
    inputs.file(markdownlintTool.file("package-lock.json"))
    outputs.dir(markdownlintTool.dir("node_modules"))
  }

val lintExchangeDocs =
  tasks.register<NodeTask>("lintExchangeDocs") {
    group = "verification"
    description =
      "Lints the exchange developer pages with markdownlint, as the Exchange Docs workflow does."
    dependsOn(installExchangeDocsMarkdownlint)
    script.set(markdownlintTool.file("node_modules/markdownlint-cli/markdownlint.js").asFile)
    workingDir.set(repositoryRoot.asFile)
    args.set(listOf("-c", "docs/exchange/.markdownlint.json", "docs/exchange/**/*.md"))
    ignoreExitValue.set(false)
    inputs.files(fileTree(exchangeDocs) { include("**/*.md", ".markdownlint.json") })
    outputs.upToDateWhen { false }
  }

/**
 * Registers a Python check of the Exchange Docs workflow, run from the repository root.
 *
 * @param name the task name
 * @param text the task description
 * @param arguments the script and its arguments
 */
fun exchangeDocsPython(name: String, text: String, arguments: List<String>) =
  tasks.register<Exec>(name) {
    group = "verification"
    description = text
    workingDir = repositoryRoot.asFile
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine(listOf(pythonExecutable) + arguments)
    inputs.files(fileTree(exchangeDocs))
    inputs.files(fileTree(repositoryRoot.dir(".github/scripts")) { include("*exchange*.py") })
    inputs.files(
      fileTree(repositoryRoot.dir("ingest/src/main/resources")) {
        include("api/exchange-v1.openapi.json", "exchange/v1/schemas/*.json")
      },
    )
    outputs.upToDateWhen { false }
  }

val exchangeDocsChecks =
  listOf(
    exchangeDocsPython(
      "selftestExchangeDocsLinks",
      "Self-tests the exchange docs link checker.",
      listOf(".github/scripts/check_exchange_docs_links.py", "--selftest"),
    ),
    exchangeDocsPython(
      "checkExchangeDocsLinks",
      "Checks that every relative link of the exchange docs resolves and no German text is on them.",
      listOf(".github/scripts/check_exchange_docs_links.py"),
    ),
    exchangeDocsPython(
      "testExchangeDpopReference",
      "Runs the tests of the DPoP reference implementation in the exchange docs.",
      listOf("-m", "unittest", "discover", "-s", "docs/exchange/dpop-reference"),
    ),
    exchangeDocsPython(
      "selftestExchangeReference",
      "Self-tests the preparation of the exchange OpenAPI reference page.",
      listOf(".github/scripts/prepare_exchange_reference.py", "--selftest"),
    ),
    exchangeDocsPython(
      "prepareExchangeReference",
      "Prepares the exchange OpenAPI reference page into the build directory.",
      listOf(
        ".github/scripts/prepare_exchange_reference.py",
        "--out",
        layout.buildDirectory.dir("exchange-reference").get().asFile.absolutePath,
      ),
    ),
  )

tasks.register("checkExchangeDocs") {
  group = "verification"
  description =
    "Runs the checks of the Exchange Docs workflow's build job except the Jekyll build (links, German text, markdownlint, DPoP reference, OpenAPI reference)."
  dependsOn(exchangeDocsChecks, lintExchangeDocs)
}

tasks.named("check").configure {
  dependsOn("checkExchangeDocs")
  dependsOn(
    testKrtFetchReadJs,
    testTrustedTypesJs,
    testClientErrorCspJs,
    testSafeUrl,
    testTableSort,
    testEslintBans,
    testTypecheckRatchet,
    testPromotionCsvEscape,
    lintCss,
    lintCssInline,
    lintProbeJs,
    lintHtml,
    lintJs,
    prettierCheck,
    typecheckJs,
    testGenApiTypes,
    testLiveSyncJs,
  )
}
