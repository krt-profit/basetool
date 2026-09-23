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
// The toolchain, the repositories, Lombok + the JetBrains annotations on both source sets
// (compile-only, ADR-0192) and `spotbugsMain` with FindSecBugs come from the root
// build.gradle.kts and settings.gradle.kts (BLD-SIMP-06).

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

  // JUnit from the Boot BOM imported above (the `junit-jupiter` aggregate carries the params
  // engine the expectation tables need), as in `test-support`.
  testImplementation(libs.junit.jupiter)
  testImplementation("org.assertj:assertj-core")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
