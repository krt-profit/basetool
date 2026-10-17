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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executor;
import org.apache.tomcat.util.threads.VirtualThreadExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

/**
 * Verifies the backend's management-port isolation (ADR-0134, REQ-OBS-005).
 *
 * <ol>
 *   <li>Actuator is absent from the application connector.
 *   <li>Health answers on the management port without credentials.
 *   <li>Metrics and info answer there only to the scrape identity.
 *   <li>The log-level mutator still requires {@code ROLE_ADMIN}.
 * </ol>
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

  /** The application connector — the one a public vhost would proxy. */
  @Value("${local.server.port}")
  private int appPort;

  /** The dedicated management connector, internal-only in prod. */
  @Value("${local.management.port}")
  private int managementPort;

  /** The running application context, whose embedded Tomcat the virtual-thread probe inspects. */
  @Autowired private ServletWebServerApplicationContext webServerContext;

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
   * Issues a GET against the management port with HTTP basic credentials.
   *
   * @param path the request path, leading slash included.
   * @param user the basic-auth username.
   * @param password the basic-auth password.
   * @return the response with a string body.
   * @throws IOException if the request fails to send.
   * @throws InterruptedException if the send is interrupted.
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

  /**
   * Scrapes the management port as the configured scrape identity.
   *
   * @return the scrape response.
   * @throws IOException if the request fails to send.
   * @throws InterruptedException if the send is interrupted.
   */
  private HttpResponse<String> scrape() throws IOException, InterruptedException {
    return getAs("/actuator/prometheus", SCRAPE_USER, SCRAPE_PASSWORD);
  }

  /**
   * Scrapes the management port until the latency timer has recorded a request, for at most five
   * seconds, and returns the last scrape.
   *
   * <p>Tomcat stops the {@code http.server.requests} timer after the response has been sent, so a
   * scrape issued right after the client received it can precede the recording.
   *
   * @return the last scrape body.
   * @throws IOException if a scrape fails to send.
   * @throws InterruptedException if a scrape or the wait between scrapes is interrupted.
   */
  private String scrapeOnceTheLatencyTimerRecorded() throws IOException, InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    String scrape = scrape().body();
    while (!scrape.contains("http_server_requests_seconds_bucket{")
        && System.nanoTime() < deadline) {
      Thread.sleep(50);
      scrape = scrape().body();
    }
    return scrape;
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
    assertThat(get(managementPort, "/actuator/health").statusCode())
        .as("the Docker HEALTHCHECK probes this over localhost and sends no credentials")
        .isIn(200, 503);
  }

  @Test
  void actuatorPrometheusIsServedToTheScrapeIdentityOnTheManagementPort() throws Exception {
    HttpResponse<String> response = scrape();

    assertThat(response.statusCode())
        .as("Prometheus scrapes this on the management port with the scrape credential")
        .isEqualTo(200);
    assertThat(response.body())
        .as("a 200 with an empty body would satisfy the status assertion but break the scrape")
        .contains("jvm_");
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
  void actuatorInfoRefusesAnUnauthenticatedCallerOnTheManagementPort() throws Exception {
    assertThat(get(managementPort, "/actuator/info").statusCode())
        .as("info carries build details and is not part of the open health surface")
        .isEqualTo(401);
  }

  @Test
  void theLogLevelMutatorStaysGatedOnTheManagementPort() throws Exception {
    HttpResponse<String> response =
        post(managementPort, "/actuator/loggers/ROOT", "{\"configuredLevel\":\"TRACE\"}");

    assertThat(response.statusCode())
        .as("POST /actuator/loggers/** must still require ROLE_ADMIN on the management port")
        .isIn(401, 403);
  }

  /**
   * The connector runs on a {@link VirtualThreadExecutor}, so Tomcat thread-pool settings and
   * gauges are inert and concurrency is measured by the active-request gauge instead.
   *
   * @throws Exception if a probe request fails to send
   */
  @Test
  void theConnectorRunsOnVirtualThreadsSoOnlyTheActiveRequestGaugeMeasuresConcurrency()
      throws Exception {
    get(appPort, "/actuator/health");

    String scrape = scrape().body();

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
   * The in-flight {@code http.server.requests.active} timer exports no histogram, while the latency
   * timer {@code http.server.requests} keeps its buckets.
   *
   * @throws Exception if a probe request fails to send
   */
  @Test
  void theInFlightTimerCarriesNoHistogramWhileTheLatencyTimerKeepsIt() throws Exception {
    get(appPort, "/actuator/health");

    String scrape = scrapeOnceTheLatencyTimerRecorded();

    assertThat(scrape)
        .as("no bucket series for the in-flight long-task timer")
        .doesNotContain("http_server_requests_active_seconds_bucket");
    assertThat(scrape)
        .as("the latency timer keeps the buckets the p95 panels compute from")
        .contains("http_server_requests_seconds_bucket{");
  }
}
