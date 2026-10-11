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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Parity tests for the security-relevant platform classes the backend, frontend and ingest each
 * keep a copy of (PSA-03): {@code KeycloakTrustSupport} and {@code ManagementPortSecurityConfig}.
 * The copies differ in places on purpose, so each test pins what must not drift rather than
 * demanding byte equality.
 */
class PlatformMirrorParityTest {

  private static final String BACKEND_MAIN =
      "backend/src/main/java/de/greluc/krt/profit/basetool/backend/";
  private static final String FRONTEND_MAIN =
      "frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/";
  private static final String INGEST_MAIN =
      "ingest/src/main/java/de/greluc/krt/profit/basetool/ingest/";

  /**
   * The backend and ingest {@code KeycloakTrustSupport} are the same code: comments, the package
   * line, the imports and the name of the read-timeout constant are the only differences, so the
   * pinned trust store, HTTP/1.1 and hostname verification cannot be changed in one and forgotten
   * in the other.
   *
   * @throws IOException if a source file cannot be read
   */
  @Test
  void keycloakTrustSupportIsTheSameCodeInBackendAndIngest() throws IOException {
    Path root = repoRoot();
    String backend = code(root.resolve(BACKEND_MAIN + "config/KeycloakTrustSupport.java"));
    String ingest = code(root.resolve(INGEST_MAIN + "relay/KeycloakTrustSupport.java"));

    assertThat(ingest.replace("KEYCLOAK_READ_TIMEOUT_CEILING", "READ_TIMEOUT"))
        .as("the ingest KeycloakTrustSupport must keep the backend's code (modulo comments)")
        .isEqualTo(backend);
  }

  /**
   * The code of {@code KeycloakTrustSupport} keeps the three properties of the pin: the bundle's
   * own trust managers, HTTP/1.1 and no relaxed endpoint identification.
   *
   * @throws IOException if a source file cannot be read
   */
  @Test
  void keycloakTrustSupportKeepsThePinProperties() throws IOException {
    Path root = repoRoot();
    for (String file :
        List.of(
            root.resolve(BACKEND_MAIN + "config/KeycloakTrustSupport.java").toString(),
            root.resolve(INGEST_MAIN + "relay/KeycloakTrustSupport.java").toString())) {
      String code = code(Path.of(file));
      assertThat(code).as(file).contains("getTrustManagers()");
      assertThat(code).as(file).contains("HttpClient.Version.HTTP_1_1");
      assertThat(code).as(file).doesNotContain("endpointIdentificationAlgorithm");
      assertThat(code).as(file).doesNotContain("X509TrustManager");
    }
  }

  /**
   * The three applications expose the same Actuator endpoints over the web, so one cannot start to
   * publish an endpoint the other two do not.
   *
   * @throws IOException if a configuration file cannot be read
   */
  @Test
  void theThreeAppsExposeTheSameActuatorEndpoints() throws IOException {
    Path root = repoRoot();
    String backend = exposure(root.resolve("backend/src/main/resources/application.yml"));
    String frontend = exposure(root.resolve("frontend/src/main/resources/application.yml"));
    String ingest = exposure(root.resolve("ingest/src/main/resources/application.yml"));

    assertThat(backend).isNotBlank();
    assertThat(frontend).as("frontend exposure").isEqualTo(backend);
    assertThat(ingest).as("ingest exposure").isEqualTo(backend);
  }

  /**
   * Each {@code ManagementPortSecurityConfig} is active only when a management port is set, opens
   * nothing but the management chain, is stateless, keeps no request cache and leaves CSRF
   * protection on. The backend enumerates the read endpoints it opens; the frontend and ingest
   * match {@code /actuator/**}, which is safe only because CSRF stays armed there.
   *
   * @throws IOException if a source file cannot be read
   */
  @Test
  void theManagementPortChainsShareTheirFloor() throws IOException {
    Path root = repoRoot();
    for (Path file :
        List.of(
            root.resolve(BACKEND_MAIN + "config/ManagementPortSecurityConfig.java"),
            root.resolve(FRONTEND_MAIN + "kernel/security/ManagementPortSecurityConfig.java"),
            root.resolve(INGEST_MAIN + "assembly/ManagementPortSecurityConfig.java"))) {
      String code = code(file);
      assertThat(code)
          .as(file.toString())
          .contains("@ConditionalOnProperty(name = \"management.server.port\")");
      assertThat(code).as(file.toString()).contains("anyRequest().permitAll()");
      assertThat(code).as(file.toString()).contains("SessionCreationPolicy.STATELESS");
      assertThat(code).as(file.toString()).contains("RequestCacheConfigurer::disable");
      assertThat(code).as(file.toString()).contains("securityMatcher(");
      assertThat(code).as(file.toString()).doesNotContain("csrf");
      assertThat(code).as(file.toString()).doesNotContain("CsrfConfigurer");
      assertThat(code).as(file.toString()).doesNotContain("httpBasic");
    }
    assertThat(code(root.resolve(BACKEND_MAIN + "config/ManagementPortSecurityConfig.java")))
        .contains("\"/actuator/health\"", "\"/actuator/prometheus\"")
        .doesNotContain("\"/actuator/**\"");
    assertThat(
            code(root.resolve(FRONTEND_MAIN + "kernel/security/ManagementPortSecurityConfig.java")))
        .contains("securityMatcher(\"/actuator/**\")");
    assertThat(code(root.resolve(INGEST_MAIN + "assembly/ManagementPortSecurityConfig.java")))
        .contains("securityMatcher(\"/actuator/**\")");
  }

  /**
   * Reads the web exposure list of a Spring configuration file.
   *
   * @param yaml the {@code application.yml} to read
   * @return the comma-separated {@code include} value with its whitespace removed
   * @throws IOException if the file cannot be read
   */
  private static String exposure(Path yaml) throws IOException {
    Matcher matcher =
        Pattern.compile("exposure:\\s*\\R\\s*include:\\s*(.+)")
            .matcher(Files.readString(yaml, StandardCharsets.UTF_8));
    assertThat(matcher.find()).as(yaml.toString()).isTrue();
    return matcher.group(1).replaceAll("\\s", "");
  }

  /**
   * Returns a Java source file without its comments, package line and imports, so only the code is
   * compared.
   *
   * @param source the source file
   * @return the code with all whitespace runs collapsed to one space
   * @throws IOException if the file cannot be read
   */
  private static String code(Path source) throws IOException {
    String text = Files.readString(source, StandardCharsets.UTF_8);
    text = text.replaceAll("(?ms)^[ \\t]*/\\*.*?\\*/", "");
    text = text.replaceAll("(?m)^\\s*//.*$", "");
    text = text.replaceAll("(?m)^(package|import) .*$", "");
    return text.replaceAll("\\s+", " ").trim();
  }

  /**
   * Walks up from the working directory to the directory holding {@code settings.gradle.kts}.
   *
   * @return the repository root
   * @throws IllegalStateException if no such directory is found
   */
  private static Path repoRoot() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException("settings.gradle.kts not found above the working directory");
    }
    return dir;
  }
}
