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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the management-port isolation for the backend (ADR-0134, extending ADR-0090).
 *
 * <p>Three properties have to hold together, and the third is what makes the backend different from
 * frontend and ingest:
 *
 * <ol>
 *   <li>Actuator is absent from the application connector, so the public vhost of the exposure plan
 *       cannot reach it even if the edge deny were removed;
 *   <li>the read endpoints answer on the management port without credentials, because Prometheus
 *       and the Docker health probe send none;
 *   <li>the log-level mutator is still refused without {@code ROLE_ADMIN}. Frontend and ingest give
 *       that up and delete the write instead; the backend keeps it, and this test is the thing that
 *       stops a later widening of the permit-all matcher from silently un-gating it.
 * </ol>
 *
 * <p>The prod profile additionally serves the management port over HTTPS with the shared keystore.
 * That is declarative SSL configuration, not exercised here — the {@code test} profile runs plain
 * HTTP on both connectors.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0"})
@ActiveProfiles("test")
class ManagementPortIsolationTest {

  /** The application connector — the one a public vhost would proxy. */
  @Value("${local.server.port}")
  private int appPort;

  /** The dedicated management connector, internal-only in prod. */
  @Value("${local.management.port}")
  private int managementPort;

  // HTTP/1.1 explicitly: the JDK client defaults to HTTP/2, whose stream-capacity handling can
  // RST_STREAM against a freshly started Tomcat under full-suite load. These are one-shot probes.
  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  /**
   * Issues a plain GET against a local port and path.
   *
   * @param port the local port to target.
   * @param path the request path, leading slash included.
   * @return the response with a string body.
   * @throws IOException if the request fails to send.
   * @throws InterruptedException if the send is interrupted.
   */
  private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Issues an unauthenticated POST against a local port and path.
   *
   * @param port the local port to target.
   * @param path the request path, leading slash included.
   * @param body the JSON request body.
   * @return the response with a string body.
   * @throws IOException if the request fails to send.
   * @throws InterruptedException if the send is interrupted.
   */
  private HttpResponse<String> post(int port, String path, String body)
      throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void managementPortIsSeparateFromTheApplicationPort() {
    assertThat(managementPort)
        .as("the management port must be a distinct connector from the application port")
        .isNotEqualTo(appPort);
  }

  @Test
  void actuatorHealthIsNotServedOnTheApplicationPort() throws Exception {
    assertThat(get(appPort, "/actuator/health").statusCode())
        .as("the connector a public vhost would proxy must serve no Actuator at all")
        .isEqualTo(404);
  }

  @Test
  void actuatorPrometheusIsNotServedOnTheApplicationPort() throws Exception {
    assertThat(get(appPort, "/actuator/prometheus").statusCode())
        .as("the metrics payload must never be obtainable from the application connector")
        .isNotEqualTo(200);
  }

  @Test
  void actuatorHealthIsServedOnTheManagementPort() throws Exception {
    // 200 when UP, 503 when a dependency is DOWN in this context — the point is "served, not 401".
    assertThat(get(managementPort, "/actuator/health").statusCode())
        .as("the Docker HEALTHCHECK probes this over localhost and sends no credentials")
        .isIn(200, 503);
  }

  @Test
  void actuatorPrometheusIsServedUnauthenticatedOnTheManagementPort() throws Exception {
    HttpResponse<String> response = get(managementPort, "/actuator/prometheus");

    assertThat(response.statusCode())
        .as("Prometheus scrapes this on the internal management port without credentials")
        .isEqualTo(200);
    assertThat(response.body())
        .as("a 200 with an empty body would satisfy the status assertion but break the scrape")
        .contains("jvm_");
  }

  @Test
  void theLogLevelMutatorStaysGatedOnTheManagementPort() throws Exception {
    // The whole reason the permit-all matcher enumerates read endpoints instead of /actuator/**.
    // An unauthenticated caller inside net-monitoring-scrape must not be able to flip ROOT to TRACE
    // and turn a 744 h log stream into a bearer-token dump.
    HttpResponse<String> response =
        post(managementPort, "/actuator/loggers/ROOT", "{\"configuredLevel\":\"TRACE\"}");

    assertThat(response.statusCode())
        .as("POST /actuator/loggers/** must still require ROLE_ADMIN on the management port")
        .isIn(401, 403);
  }
}
