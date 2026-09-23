plugins {
  `java-library`
  checkstyle
  id("com.diffplug.spotless")
  alias(libs.plugins.spring.dependency.management)
}

description = "test-support"

// A PLAIN library JAR holding TEST INFRASTRUCTURE ONLY, and deliberately not a Spring Boot module.
//
// Why it exists (#1804): `AnonymousSurfaceSweepTest` (backend) and `AnonymousSurfaceSweepMvcTest`
// (frontend) each carried their own copy of the same endpoint-enumeration engine. Both guards are
// worth having for exactly one reason - they ask the dispatcher for EVERY mapping rather than
// asserting a list somebody remembered to write - so a defect in that engine blinds both at once,
// and the 2026-09-06 review of #1803 found two such defects that had to be fixed in each copy
// separately. This module is the one copy.
//
// Why a module rather than test fixtures on an existing one: the frontend must not depend on the
// backend, and there is no neutral module to hang fixtures off - `ingest` and `keycloak-spi` are no
// more neutral than `backend` is. Owner decision on 2026-09-07 put the code in `src/main/java`
// rather than in a `src/testFixtures` source set, so the build needs no plugin this repo does not
// already use.
//
// NOTHING SHIPS FROM HERE. Every consumer (backend, frontend, and since 2026-09-22 ingest's
// IngestEndpointSurfaceTest) depends on it as `testImplementation`, so it never reaches a runtime
// classpath, an image or an SBOM. Keep it that way: production code does not belong in
// this module, and its name is the only thing saying so.
//
// The toolchain, the repositories and Lombok + the JetBrains annotations on both source sets
// (compile-only on each, so nothing leaks onto a consumer's runtime classpath; Lombok at
// the Boot-managed version through the BOM imported below) come from the root
// build.gradle.kts and settings.gradle.kts.

// The Spring Boot BOM WITHOUT the Boot plugin: this module compiles against the same Spring the two
// applications run, and pinning a second set of versions here is how the two drift apart.
dependencyManagement {
  imports {
    mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
  }
}

dependencies {
  // `api`, not `implementation`: a consumer's sweep writes `Call`, `WebApplicationContext` and
  // `HttpMethod` in its own source, so these types belong on its compile classpath too. Both
  // consumers already carry Spring, but saying so here keeps the module honest on its own.
  api("org.springframework:spring-web")
  api("org.springframework:spring-webmvc")
  // `ProfiledLogbackConfig` loads an application's real logback configuration into a private
  // context, so the three apps can pin that their prod JSON sink masks what reaches it
  // (ADR-0205). `api`: the consumer's test holds the returned `LoggerContext`. Every consumer
  // already has logback through Boot's logging starter, at this same BOM-managed version.
  api("ch.qos.logback:logback-classic")

  // Only the TESTS need the servlet API: the engine itself names no servlet type, but
  // StaticWebApplicationContext and RequestMappingHandlerMapping load one when the fixture builds a
  // registry. Compile-scoping it would claim a dependency this module does not have.
  testImplementation("jakarta.servlet:jakarta.servlet-api")
  // JUnit comes from the Boot BOM imported above, like every other Boot-managed dependency here.
  // A `platform(libs.junit.bom)` on top of it used to pin the catalog's keycloak-spi JUnit line
  // into this module as well, i.e. two JUnit versions competing in one build.
  testImplementation(libs.junit.jupiter)
  testImplementation("org.assertj:assertj-core")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// `TestImagesTest` compares the `TestImages.REDIS` constant against the image production runs, read
// off the filesystem from the repository root. Neither file is on this task's classpath, so without
// the declaration a digest bump in the compose file alone would leave the task UP-TO-DATE and the
// guard silently skipped -- the cross-module input defect the other modules' builds document.
tasks.named<Test>("test") {
  inputs
    .files(
      rootProject.file("docker-compose.yml"),
      rootProject.file("quadlet/systemd/redis.container"),
    )
    .withPropertyName("productionRedisImageSources")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
