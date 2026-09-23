plugins {
  `java-library`
  checkstyle
  id("jacoco")
  alias(libs.plugins.spring.dependency.management)
  alias(libs.plugins.spotbugs.base)
  id("com.diffplug.spotless")
}

description = "logging-support"

// A PLAIN library JAR holding the log hygiene all three applications share, and deliberately not a
// Spring Boot module (ADR-0205).
//
// Why it exists: `LogSafe`, `PiiMasker`, `PiiMaskingPatternLayout` and `PiiMaskingLogstashEncoder`
// were three hand-maintained copies each (backend, frontend, ingest) — twelve files — held
// together by a byte-comparison test that read the other two modules' sources off the filesystem
// and by a `logSafeMirrorSources` input declaration in each of the three build files, without
// which that test silently reported UP-TO-DATE. A masking or sanitising fix had to be made three
// times, and the guard only caught a forgotten copy of `LogSafe`; the maskers' copies were not
// compared at all. This module is the one copy.
//
// UNLIKE `test-support`, THIS SHIPS. Each application depends on it as `implementation`, so the
// JAR lands in `BOOT-INF/lib` of all three boot JARs and images and is listed as a component of
// their SBOMs. That is exactly why it holds nothing with domain meaning: no DTO, no validation
// rule, no Spring bean — only the framework-level scrubbing every log line passes through.
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

repositories { mavenCentral() }

// The Spring Boot BOM WITHOUT the Boot plugin, so logback is resolved at the version the three
// applications run — a second pin here is how the library and its consumers would drift apart.
dependencyManagement {
  imports {
    mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
  }
}

dependencies {
  // `api`: the two appender classes extend `PatternLayout` and `LogstashEncoder`, so both types are
  // part of this module's public surface. Every consumer already declares both (Boot's logging
  // starter and the catalog's encoder), so this adds nothing to any runtime classpath.
  api("ch.qos.logback:logback-classic")
  api(libs.logstash.logback.encoder)

  // Lombok + the JetBrains annotations, compile-only on both source sets exactly as in every other
  // module (ADR-0192) — neither reaches a runtime classpath, an image or an SBOM.
  compileOnly("org.projectlombok:lombok")
  annotationProcessor("org.projectlombok:lombok")
  compileOnly(libs.jetbrains.annotations)
  testCompileOnly("org.projectlombok:lombok")
  testAnnotationProcessor("org.projectlombok:lombok")
  testCompileOnly(libs.jetbrains.annotations)

  // FindSecBugs, as in the three applications: this code runs on every log event of all of them.
  spotbugsPlugins(libs.findsecbugs.plugin)

  // JUnit from the Boot BOM imported above (the `junit-jupiter` aggregate carries the params
  // engine the expectation tables need), as in `test-support`.
  testImplementation(libs.junit.jupiter)
  testImplementation("org.assertj:assertj-core")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// SpotBugs for the main source set, registered exactly like the applications' task: the `-base`
// plugin creates no task of its own. BLOCKING — a HIGH-confidence finding fails the build.
tasks.register<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain") {
  group = "verification"
  description = "Runs SpotBugs analysis on the main source set."
  sourceDirs.from(sourceSets.main.get().allSource.sourceDirectories)
  classDirs.from(sourceSets.main.get().output.classesDirs)
  auxClassPaths.from(sourceSets.main.get().compileClasspath)
  pluginJarFiles.from(configurations.named("spotbugsPlugins"))
  effort.set(com.github.spotbugs.snom.Effort.DEFAULT)
  reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
  ignoreFailures = false
  // XML reporter ONLY — the SpotBugs multi-output ordering bug writes a zero-class report when html
  // precedes xml.
  reports.create("xml") {
    required.set(true)
    outputLocation.set(layout.buildDirectory.file("reports/spotbugs/main.xml"))
  }
  dependsOn("classes")
}

tasks.named("check").configure { dependsOn("spotbugsMain") }
