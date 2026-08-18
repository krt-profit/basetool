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

package de.greluc.krt.profit.basetool.frontend.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins the one cross-module invariant behind the 2026-08-18 rollback: the port the frontend probes
 * for the backend's readiness must be the port the backend actually serves Actuator on in
 * production.
 *
 * <p>The two values live in different modules' {@code application-prod.yml} and nothing connected
 * them. ADR-0134 moved the backend's Actuator to the internal-only management port {@code 11271};
 * {@link BackendHealthIndicator} kept probing the API port, got a 404, and — because it sits in the
 * readiness group that gates the Docker HEALTHCHECK — left the frontend container permanently
 * unhealthy. The deploy loop rolled v1.5.47 back after 180 s.
 *
 * <p>Every existing test stayed green through that: {@code ManagementPortSecurityConfig} is
 * conditional on {@code management.server.port}, which only the prod profile sets, and the
 * frontend's {@code test} profile redefines the readiness group without the {@code backend}
 * indicator. The combination existed nowhere but production. Reading the two files is crude, and it
 * is the only thing that would have caught this before the deploy did.
 */
class BackendHealthUrlProdParityTest {

  /** The frontend's prod config, relative to the module directory Gradle runs tests from. */
  private static final Path FRONTEND_PROD =
      Path.of("src", "main", "resources", "application-prod.yml");

  /** The backend's prod config. The relative hop across modules is deliberate. */
  private static final Path BACKEND_PROD =
      Path.of("..", "backend", "src", "main", "resources", "application-prod.yml");

  /** Pulls the port out of {@code ${BACKEND_HEALTH_URL:https://backend:11271}}. */
  private static final Pattern HEALTH_URL_PORT =
      Pattern.compile("backend-health-url:.*?backend:(\\d+)");

  /** The backend key this must track. */
  private static final String MANAGEMENT_PORT_PATH = "management.server.port";

  @Test
  @DisplayName("the frontend probes the port the backend actually serves Actuator on in prod")
  void theHealthProbePortMatchesTheBackendsManagementPort() throws IOException {
    String probedPort = healthProbePort(read(FRONTEND_PROD));
    String managementPort = managementServerPort(read(BACKEND_PROD));

    assertThat(probedPort)
        .as("app.backend-health-url must be set in the frontend's prod config")
        .isNotNull();
    assertThat(managementPort)
        .as("the backend's prod config must declare %s", MANAGEMENT_PORT_PATH)
        .isNotNull();
    assertThat(probedPort)
        .as(
            "the readiness probe is in the group that gates the Docker HEALTHCHECK; pointing it at "
                + "a port that serves no Actuator makes the container permanently unhealthy and "
                + "the deploy loop rolls the release back (2026-08-18, ADR-0134)")
        .isEqualTo(managementPort);
  }

  @Test
  void theProbeDoesNotSilentlyFallBackToTheApiPort() throws IOException {
    // The @Value default (${app.backend-health-url:${app.backend-url}}) is right for dev, test and
    // e2e, where one connector serves both. In prod that fallback is the bug, so the key has to be
    // present rather than inherited.
    String frontendProd = read(FRONTEND_PROD);

    assertThat(frontendProd).contains("backend-health-url:");
    assertThat(healthProbePort(frontendProd))
        .as("in prod the Actuator port and the API port are deliberately different")
        .isNotEqualTo("11261");
  }

  /**
   * Extracts the port the readiness probe targets.
   *
   * @param yaml the frontend's raw prod config
   * @return the port as a string, or {@code null} when the key is absent
   */
  private static String healthProbePort(String yaml) {
    Matcher matcher = HEALTH_URL_PORT.matcher(yaml);
    return matcher.find() ? matcher.group(1) : null;
  }

  /**
   * Reads {@code management.server.port} out of the backend's prod config.
   *
   * <p>Parsed rather than regex-matched: the block carries a dozen lines of ADR-0134 rationale
   * between {@code management:} and {@code server:}, and a pattern that assumed adjacency failed on
   * the comments — a guard that cannot read the file it guards is worse than no guard.
   *
   * @param yaml the backend's raw prod config
   * @return the port as a string, or {@code null} when the key is absent
   */
  private static String managementServerPort(String yaml) {
    Object node = new Yaml().load(yaml);
    for (String key : new String[] {"management", "server", "port"}) {
      if (!(node instanceof Map<?, ?> map)) {
        return null;
      }
      node = map.get(key);
    }
    return node == null ? null : String.valueOf(node);
  }

  /**
   * Reads one config file, failing with the resolved path when the layout moved.
   *
   * @param relative the path relative to the frontend module directory
   * @return the file content
   * @throws IOException if the file cannot be read
   */
  private static String read(Path relative) throws IOException {
    assertThat(Files.exists(relative))
        .as("expected to find %s (resolved: %s)", relative, relative.toAbsolutePath().normalize())
        .isTrue();
    return Files.readString(relative, StandardCharsets.UTF_8);
  }
}
