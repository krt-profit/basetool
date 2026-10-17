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

package de.greluc.krt.profit.basetool.ingest.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Integration tests for the management-port isolation (ADR-0090, REQ-OBS-005): with {@code
 * management.server.port} set, Actuator is served only on that port, health there is open, and
 * every other Actuator path needs the scrape identity.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "app.monitoring.scrape.username=" + ManagementPortIsolationTest.SCRAPE_USER,
      "app.monitoring.scrape.password=" + ManagementPortIsolationTest.SCRAPE_PASSWORD
    })
class ManagementPortIsolationTest {

  /** Test-only scrape username. */
  static final String SCRAPE_USER = "scrape-test";

  /** Test-only scrape password. */
  static final String SCRAPE_PASSWORD = "scrape-test-password";

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private HandoffStagingService handoffStagingService;

  /** The public application connector's port (where NPM would re-encrypt in prod). */
  @Value("${local.server.port}")
  private int appPort;

  /** The dedicated management connector's port (internal-only in prod). */
  @Value("${local.management.port}")
  private int managementPort;

  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  /**
   * Issues a plain GET against a local port + path and returns the HTTP status code.
   *
   * @param port the local port to target
   * @param path the request path (leading slash included)
   * @return the response status code
   * @throws IOException if the request fails to send
   * @throws InterruptedException if the send is interrupted
   */
  private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void managementPortIsSeparateFromTheApplicationPort() {
    assertThat(managementPort)
        .as("the management port must be a distinct connector from the public app port")
        .isNotEqualTo(appPort);
  }

  @Test
  void actuatorHealthIsNotServedOnThePublicApplicationPort() throws Exception {
    assertThat(get(appPort, "/actuator/health").statusCode())
        .as("/actuator/health must be absent from the public application connector")
        .isEqualTo(404);
  }

  @Test
  void actuatorPrometheusIsNotServedOnThePublicApplicationPort() throws Exception {
    assertThat(get(appPort, "/actuator/prometheus").statusCode())
        .as("/actuator/prometheus must never return a payload on the public application connector")
        .isNotEqualTo(200);
  }

  @Test
  void actuatorHealthIsServedOnTheManagementPort() throws Exception {
    int status = get(managementPort, "/actuator/health").statusCode();
    assertThat(status)
        .as(
            "/actuator/health must be served (200 when UP, 503 when a dependency is DOWN) — not "
                + "401/404 — on the management port")
        .isIn(200, 503);
  }

  @Test
  void actuatorPrometheusIsServedToTheScrapeIdentityOnTheManagementPort() throws Exception {
    HttpResponse<String> response = getAs("/actuator/prometheus", SCRAPE_USER, SCRAPE_PASSWORD);
    assertThat(response.statusCode())
        .as("Prometheus scrapes /actuator/prometheus on the management port with the credential")
        .isEqualTo(200);
    assertThat(response.body())
        .as("the management-port scrape returns the Micrometer exposition payload")
        .contains("# HELP");
  }

  @Test
  void actuatorPrometheusRefusesAnUnauthenticatedCallerOnTheManagementPort() throws Exception {
    assertThat(get(managementPort, "/actuator/prometheus").statusCode())
        .as("a neighbour on a shared network must not read the metrics without the credential")
        .isEqualTo(401);
  }

  @Test
  void actuatorPrometheusRefusesAWrongPasswordOnTheManagementPort() throws Exception {
    assertThat(getAs("/actuator/prometheus", SCRAPE_USER, "wrong").statusCode())
        .as("only the configured scrape password unlocks the metrics")
        .isEqualTo(401);
  }

  @Test
  void actuatorLoggersRefusesAnUnauthenticatedCallerOnTheManagementPort() throws Exception {
    assertThat(get(managementPort, "/actuator/loggers").statusCode())
        .as("logger levels are not part of the open health surface")
        .isEqualTo(401);
  }

  /**
   * Issues a GET against the management port with HTTP basic credentials.
   *
   * @param path the request path (leading slash included)
   * @param user the basic-auth username
   * @param password the basic-auth password
   * @return the HTTP response with a string body
   * @throws IOException if the request fails to send
   * @throws InterruptedException if the send is interrupted
   */
  private HttpResponse<String> getAs(String path, String user, String password)
      throws IOException, InterruptedException {
    String token =
        Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + path))
            .header("Authorization", "Basic " + token)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
