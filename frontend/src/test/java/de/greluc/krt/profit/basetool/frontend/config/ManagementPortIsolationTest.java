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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executor;
import org.apache.tomcat.util.threads.VirtualThreadExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies the management-port isolation (ADR-0090): Actuator is served only on the management
 * port, and the scrape there needs no credentials.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0"})
@ActiveProfiles("test")
class ManagementPortIsolationTest {

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  /** The public application connector's port (where NPM would re-encrypt in prod). */
  @Value("${local.server.port}")
  private int appPort;

  /** The dedicated management connector's port (internal-only in prod). */
  @Value("${local.management.port}")
  private int managementPort;

  /** The running application context, whose embedded Tomcat the virtual-thread probe inspects. */
  @Autowired private ServletWebServerApplicationContext webServerContext;

  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  /**
   * Issues a plain GET against a local port and path.
   *
   * @param port the local port
   * @param path the request path, with leading slash
   * @return the HTTP response with a string body
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
    assertThat(get(managementPort, "/actuator/health").statusCode())
        .as("/actuator/health must be served — not 401/404 — on the management port")
        .isIn(200, 503);
  }

  @Test
  void actuatorPrometheusIsServedUnauthenticatedOnTheManagementPort() throws Exception {
    HttpResponse<String> response = get(managementPort, "/actuator/prometheus");
    assertThat(response.statusCode())
        .as("Prometheus scrapes /actuator/prometheus on the internal management port without auth")
        .isEqualTo(200);
    assertThat(response.body())
        .as("the management-port scrape returns the Micrometer exposition payload")
        .contains("# HELP");
  }

  /**
   * Verifies that the connector runs on a {@link VirtualThreadExecutor}, so the Tomcat thread-pool
   * settings are inert and concurrency is measured by the active-request gauge (FE-PERF-07).
   *
   * @throws Exception if a probe request fails to send
   */
  @Test
  void theConnectorRunsOnVirtualThreadsSoOnlyTheActiveRequestGaugeMeasuresConcurrency()
      throws Exception {
    get(appPort, "/actuator/health");

    String scrape = get(managementPort, "/actuator/prometheus").body();

    Executor executor =
        ((TomcatWebServer) webServerContext.getWebServer())
            .getTomcat()
            .getConnector()
            .getProtocolHandler()
            .getExecutor();
    assertThat(executor)
        .as(
            "virtual threads hand the connector an external executor; Tomcat's pool settings are"
                + " inert")
        .isInstanceOf(VirtualThreadExecutor.class);
    assertThat(scrape)
        .as("the in-flight request gauge the dashboard's concurrency panel reads")
        .contains("http_server_requests_active_seconds_count{");
  }

  /**
   * Verifies that the in-flight timer carries no histogram while the latency timer keeps its
   * buckets.
   *
   * @throws Exception if a probe request fails to send
   */
  @Test
  void theInFlightTimerCarriesNoHistogramWhileTheLatencyTimerKeepsIt() throws Exception {
    get(appPort, "/actuator/health");

    String scrape = get(managementPort, "/actuator/prometheus").body();

    assertThat(scrape)
        .as("no bucket series for the in-flight long-task timer")
        .doesNotContain("http_server_requests_active_seconds_bucket");
    assertThat(scrape)
        .as("the latency timer keeps the buckets the p95 panels compute from")
        .contains("http_server_requests_seconds_bucket{");
  }
}
