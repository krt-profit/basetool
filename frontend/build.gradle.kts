import com.github.gradle.node.npm.task.NpxTask
import com.github.gradle.node.task.NodeTask

buildscript { dependencies { constraints { classpath(libs.handlebars) } } }

plugins {
  java
  checkstyle
  id("jacoco")
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependency.management)
  alias(libs.plugins.cyclonedx.bom)
  alias(libs.plugins.licensee)
  alias(libs.plugins.spotbugs.base)
  alias(libs.plugins.pitest)
  id("com.diffplug.spotless")
  alias(libs.plugins.node.gradle)
  alias(libs.plugins.openapi.generator)
}

description = "frontend"

val generatedContract = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
  generatorName.set("java")
  inputSpec.set(
    rootProject.layout.projectDirectory.file("backend/src/main/resources/api/openapi.json")
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
    )
  )
}

tasks.openApiGenerate.configure {
  inputs
    .file(rootProject.layout.projectDirectory.file("backend/src/main/resources/api/openapi.json"))
    .withPropertyName("openapiSpecFile")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}

sourceSets.named("test") { java.srcDir(generatedContract.map { it.dir("src/main/java") }) }

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
      }
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
    @Suppress("UNCHECKED_CAST")
    val urlAliases = rootProject.extra["ossLicenseUrlAliases"] as Map<String, String>
    @Suppress("UNCHECKED_CAST")
    val coordinateOverrides =
      rootProject.extra["ossLicenseCoordinateOverrides"] as Map<String, String>
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
          Map<*, *>
        >()

      val out = outputFile.get().asFile
      out.parentFile.mkdirs()
      out.writeText(
        groovy.json.JsonOutput.prettyPrint(
          groovy.json.JsonOutput.toJson(
            mapOf(
              "generator" to generator,
              "components" to components.values + bundled,
            )
          )
        ),
        Charsets.UTF_8,
      )
      logger.lifecycle(
        "generateOssLicenses: ${components.size} libraries + ${bundled.size} bundled components"
      )
    }
  }

sourceSets.named("main") { resources.srcDir(generateOssLicenses) }

val backendDtoMirrorDir = "backend/src/main/java/de/greluc/krt/profit/basetool/backend/model/dto"

tasks.named<Test>("test") {
  inputs
    .files(rootProject.fileTree(backendDtoMirrorDir) { include("*.java") })
    .withPropertyName("backendDtoMirrorSources")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .file(rootProject.file("backend/src/main/resources/api/openapi.json"))
    .withPropertyName("backendOpenApiDocument")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .file(rootProject.file("backend/src/main/resources/application-prod.yml"))
    .withPropertyName("backendProdConfig")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .file(rootProject.file("docker/test-tls/basetool-test-backend.p12"))
    .withPropertyName("testTlsKeystore")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .files(
      rootProject.file(
        "frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/E2eStackExtension.java"
      ),
      rootProject.file("frontend/src/e2e/resources/realm-export.e2e.json"),
    )
    .withPropertyName("e2eAudienceParitySources")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  inputs
    .files(
      fileTree("src/main/resources/static/fonts"),
      rootProject.fileTree("backend/src/main/resources/fonts"),
      rootProject.fileTree("keycloak-theme"),
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
          "minifyStaticCss: ${totalBefore} -> ${totalAfter} bytes (-${"%.1f".format(pct)}%)"
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
            "-Pe2e.browser=$requested is not one of $allBrowsers; nothing would be installed for it"
          )
      }
    setArgs(
      if (withDeps) listOf("install", "--with-deps") + browsers else listOf("install") + browsers
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
    description =
      "Lints the page stylesheets (static/css/pages) and any Thymeleaf <style> block with Stylelint."
    dependsOn(tasks.named("npmInstall"))
    command.set("stylelint")
    args.set(
      listOf(
        "--config",
        ".stylelintrc.templates.json",
        "src/main/resources/templates/**/*.html",
        "src/main/resources/static/css/pages/**/*.css",
      )
    )
    ignoreExitValue.set(false)
    inputs.files(fileTree("src/main/resources/templates") { include("**/*.html") })
    inputs.files(fileTree("src/main/resources/static/css/pages") { include("**/*.css") })
    inputs.file("package.json")
    inputs.file(".stylelintrc.templates.json")
  }

val probeSource =
  layout.projectDirectory.file(
    "src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/TouchClassLayoutE2eTest.java"
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
    dependsOn(extractProbeJs)
    command.set("eslint")
    args.set(
      listOf(
        "--no-config-lookup",
        "--config",
        "eslint.probe.config.mjs",
        extractedProbe.get().asFile.absolutePath,
      )
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
      "Lints hand-written browser scripts with ESLint (strict; fails the build on findings)."
    dependsOn(tasks.named("npmInstall"))
    command.set("eslint")
    args.set(listOf("src/main/resources/static/js/**/*.js"))
    ignoreExitValue.set(false)
    inputs.files(fileTree("src/main/resources/static/js") { include("**/*.js") })
    inputs.file("package.json")
    inputs.file("eslint.config.mjs")
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
      )
    )
    ignoreExitValue.set(false)
    inputs.files(fileTree("src/main/resources/static/css") { include("**/*.css") })
    inputs.files(
      fileTree("src/main/resources/static/js") {
        include("**/*.js")
        exclude("vendor/**")
      }
    )
    inputs.files(fileTree("types") { include("**/*.d.ts") })
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
    )
  )
  ignoreExitValue.set(false)
}

val openApiSpec = rootProject.file("backend/src/main/resources/api/openapi.json")
val generatedApiTypes = layout.buildDirectory.file("generated/ts/api.d.ts")

val generateApiTypesScript = layout.projectDirectory.file("scripts/gen-api-types.mjs")

val generateApiTypes =
  tasks.register<NodeTask>("generateApiTypes") {
    group = "build"
    description =
      "Generates TypeScript types for the backend DTOs from the OpenAPI spec (build output)."
    dependsOn(tasks.named("npmSetup"))
    script.set(generateApiTypesScript.asFile)
    args.set(listOf(openApiSpec.absolutePath, generatedApiTypes.get().asFile.absolutePath))
    ignoreExitValue.set(false)
    inputs.file(openApiSpec)
    inputs.file(generateApiTypesScript)
    outputs.file(generatedApiTypes)
  }

val typecheckJs =
  tasks.register<NpxTask>("typecheckJs") {
    group = "verification"
    description =
      "Type-checks the opted-in browser scripts with tsc --noEmit (strict; fails on findings)."
    dependsOn(tasks.named("npmInstall"), generateApiTypes)
    command.set("tsc")
    args.set(listOf("-p", "tsconfig.json"))
    ignoreExitValue.set(false)
    inputs.files(
      fileTree("src/main/resources/static/js") {
        include("**/*.js")
        exclude("vendor/**")
      }
    )
    inputs.files(fileTree("types") { include("**/*.d.ts") })
    inputs.file(generatedApiTypes)
    inputs.file("package.json")
    inputs.file("tsconfig.json")
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

tasks.named("check").configure {
  dependsOn(
    lintCss,
    lintCssInline,
    lintProbeJs,
    lintHtml,
    lintJs,
    prettierCheck,
    typecheckJs,
    testGenApiTypes,
  )
}
