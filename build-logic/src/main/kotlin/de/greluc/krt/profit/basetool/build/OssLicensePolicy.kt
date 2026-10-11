package de.greluc.krt.profit.basetool.build

/**
 * The licence policy of the shipped dependencies (ADR-0197): what Licensee allows and how the
 * public licence page names what a POM leaves unclear.
 */
object OssLicensePolicy {
  /** The SPDX identifiers Licensee accepts. */
  val allowedLicenses =
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

  /** POM licence URLs mapped to the SPDX identifier the linked text is. */
  val urlAliases =
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

  /** Coordinates whose POM names no usable licence, as `group:artifact` to `(version, SPDX id)`. */
  val coordinateOverrides =
    mapOf(
      "org.springframework.session:spring-session-core" to ("4.1.1" to "Apache-2.0"),
      "org.springframework.session:spring-session-data-redis" to ("4.1.1" to "Apache-2.0"),
    )

  /** [coordinateOverrides] reduced to `group:artifact` to SPDX identifier. */
  val overrideLicenses: Map<String, String> =
    coordinateOverrides.mapValues { (_, versionAndId) -> versionAndId.second }
}
