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

package de.greluc.krt.profit.basetool.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.ingest.service.BackendImportClient;
import de.greluc.krt.profit.basetool.ingest.service.HandoffStagingService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies the ADR-0090 management-port isolation: with {@code management.server.port} set to a
 * separate port, Actuator is served ONLY there and is absent from the public application connector,
 * and the management-port endpoints are unauthenticated (Boot's management-context security
 * auto-config backs off given the custom {@link SecurityConfig} chains).
 *
 * <p>The prod {@code application-prod.yml} additionally serves this port over HTTPS with the shared
 * keystore; that is declarative SSL config not exercised here (the {@code test} profile disables
 * the server connector's TLS), so the management port runs plain HTTP for the probe. What this test
 * pins is the load-bearing behaviour: the public port no longer exposes {@code /actuator/**}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0"})
class ManagementPortIsolationTest {

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private BackendImportClient backendImportClient;
  @MockitoBean private HandoffStagingService handoffStagingService;

  /** The public application connector's port (where NPM would re-encrypt in prod). */
  @Value("${local.server.port}")
  private int appPort;

  /** The dedicated management connector's port (internal-only in prod). */
  @Value("${local.management.port}")
  private int managementPort;

  // HTTP/1.1 explicitly: the JDK client defaults to HTTP/2, whose stream-capacity handling can
  // RST_STREAM ("Processing capacity exceeded") against the freshly-started Tomcat under full-suite
  // load. These probes are trivial one-shot GETs, so HTTP/1.1 is both sufficient and robust.
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
    // The public connector NPM fronts must not expose Actuator at all — 404, not 200.
    assertThat(get(appPort, "/actuator/health").statusCode())
        .as("/actuator/health must be absent from the public application connector")
        .isEqualTo(404);
  }

  @Test
  void actuatorPrometheusIsNotServedOnThePublicApplicationPort() throws Exception {
    // On the app port the scrape path is owned by MonitoringScrapeSecurityConfig's fail-closed
    // chain (deny-all when no scrape creds are set), so it is 401/403 — never a reachable payload.
    assertThat(get(appPort, "/actuator/prometheus").statusCode())
        .as("/actuator/prometheus must never return a payload on the public application connector")
        .isNotEqualTo(200);
  }

  @Test
  void actuatorHealthIsServedOnTheManagementPort() throws Exception {
    // The Docker HEALTHCHECK reaches health on the management port unauthenticated. In this test
    // context Redis/backend health indicators are DOWN (no live dependencies), so the aggregate is
    // 503 — but "served, not 401/404" is the point: the endpoint IS present and needs no auth. In
    // prod the readiness group is UP and returns 200.
    int status = get(managementPort, "/actuator/health").statusCode();
    assertThat(status)
        .as(
            "/actuator/health must be served (200 when UP, 503 when a dependency is DOWN) — not "
                + "401/404 — on the management port")
        .isIn(200, 503);
  }

  @Test
  void actuatorPrometheusIsServedUnauthenticatedOnTheManagementPort() throws Exception {
    // ADR-0090: the management context's security auto-config backs off, so the internal-only
    // scrape endpoint is reachable without credentials — the Keycloak port-9000 posture.
    HttpResponse<String> response = get(managementPort, "/actuator/prometheus");
    assertThat(response.statusCode())
        .as("Prometheus scrapes /actuator/prometheus on the internal management port without auth")
        .isEqualTo(200);
    assertThat(response.body())
        .as("the management-port scrape returns the Micrometer exposition payload")
        .contains("# HELP");
  }
}
