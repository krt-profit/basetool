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
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

repositories { mavenCentral() }

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

  // Lombok + the JetBrains annotations, on both source sets and compile-only on each: this module
  // is `testImplementation` for its two consumers, so anything that leaked onto its runtime
  // classpath would land on theirs. The version is Boot-managed via the BOM imported above, which
  // is what keeps it equal to the one backend/frontend/ingest compile against.
  compileOnly("org.projectlombok:lombok")
  annotationProcessor("org.projectlombok:lombok")
  compileOnly(libs.jetbrains.annotations)
  testCompileOnly("org.projectlombok:lombok")
  testAnnotationProcessor("org.projectlombok:lombok")
  testCompileOnly(libs.jetbrains.annotations)

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
