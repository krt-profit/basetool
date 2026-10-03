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

package de.greluc.krt.profit.basetool.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Drives the committed admission map and include through the edge's own nginx image (REQ-API-021,
 * guard G-08): every admitted operation reaches the upstream, every request of the refusal matrix
 * is refused by the edge with {@code 404}.
 *
 * <p>The upstream is a {@code return 200}, so a {@code 404} can only be the edge's own refusal.
 */
class EdgeAdmissionNginxTest {

  /** The edge's image line in {@code docker-compose.yml}. */
  private static final Pattern EDGE_IMAGE =
      Pattern.compile("(?m)^\\s*image:\\s*(nginxinc/nginx-unprivileged:\\S+)\\s*$");

  /** A configuration that mounts the two files the way {@code 50-api.conf.template} does. */
  private static final String NGINX_CONF =
      """
      pid /tmp/nginx.pid;
      pcre_jit on;
      events { worker_connections 256; }
      http {
        access_log off;
        error_log /dev/stderr warn;
        include /etc/nginx/edge/include/api-admission.conf;
        server {
          listen 8080;
          include /etc/nginx/edge/include/api-allowlist.conf;
          location / { return 200 "reached"; }
        }
      }
      """;

  /** The running edge. */
  private static GenericContainer<?> edge;

  /** The client every request goes through. */
  private static HttpClient client;

  /**
   * Starts nginx with the committed map and include.
   *
   * @throws IOException if a committed file cannot be read
   */
  @BeforeAll
  static void startEdge() throws IOException {
    edge =
        new GenericContainer<>(DockerImageName.parse(edgeImage()))
            .withExposedPorts(8080)
            .withCopyToContainer(Transferable.of(NGINX_CONF), "/etc/nginx/nginx.conf")
            .withCopyToContainer(
                Transferable.of(EdgeAdmissionTest.committed(EdgeAdmission.MAP_FILE)),
                "/etc/nginx/edge/include/api-admission.conf")
            .withCopyToContainer(
                Transferable.of(EdgeAdmissionTest.committed(EdgeAdmission.INCLUDE_FILE)),
                "/etc/nginx/edge/include/api-allowlist.conf")
            .waitingFor(
                Wait.forHttp("/api/v1/app/version-policy")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)));
    edge.start();
    client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
  }

  /** Stops nginx and the client. */
  @AfterAll
  static void stopEdge() {
    if (client != null) {
      client.close();
    }
    if (edge != null) {
      edge.stop();
    }
  }

  /**
   * Verifies that every sample of every admitted operation, bare and with a query string, reaches
   * the upstream.
   *
   * @throws Exception if a request cannot be sent
   */
  @Test
  @DisplayName("every frozen operation, anonymous read and retired operation reaches the upstream")
  void everyAdmittedOperationReachesTheUpstream() throws Exception {
    List<EdgeAdmission.Request> requests = EdgeAdmission.load().admittedRequests();
    assertThat(requests).hasSizeGreaterThanOrEqualTo(2 * 246);

    assertThat(mismatches(requests)).isEmpty();
  }

  /**
   * Verifies that every request of the refusal matrix is refused by the edge: other verbs on
   * admitted paths, near-miss spellings, ids that are not uuids, the paths the removed prefix rules
   * opened, the exchange and the web-only admin trees.
   *
   * @throws Exception if a request cannot be sent
   */
  @Test
  @DisplayName("every other verb, near-miss and former prefix path is refused with 404")
  void everyOtherRequestIsRefused() throws Exception {
    List<EdgeAdmission.Request> requests = EdgeAdmission.load().refusedRequests();
    assertThat(requests).hasSizeGreaterThanOrEqualTo(1_500);

    assertThat(mismatches(requests)).isEmpty();
  }

  /**
   * Verifies that the nightly probe's rows get the edge half of their answer here: a refusal row
   * the edge refuses, every other row passed to the upstream.
   *
   * @throws Exception if the workflow cannot be read or a request cannot be sent
   */
  @Test
  @DisplayName("the nightly probe's rows pass or stop at the edge as the table says")
  void theProbeRowsAgreeWithTheEdge() throws Exception {
    List<EdgeAdmission.Request> rows = new ArrayList<>();
    for (EdgeAdmission.Request row :
        EdgeAdmission.parseProbeRows(EdgeAdmissionTest.committed(EdgeAdmission.PROBE_WORKFLOW))) {
      rows.add(
          new EdgeAdmission.Request(row.method(), row.path(), row.status() == 404 ? 404 : 200));
    }
    assertThat(rows).hasSizeGreaterThanOrEqualTo(246);

    assertThat(mismatches(rows)).isEmpty();
  }

  /**
   * Proves the harness able to fail: an admitted request expected to be refused, and a refused one
   * expected to pass, are both reported.
   *
   * @throws Exception if a request cannot be sent
   */
  @Test
  @DisplayName("a wrong expectation is reported")
  void aWrongExpectationIsReported() throws Exception {
    List<EdgeAdmission.Request> planted =
        List.of(
            new EdgeAdmission.Request("GET", "/api/v1/orders", 404),
            new EdgeAdmission.Request("GET", "/api/v1/me/layout", 200));

    assertThat(mismatches(planted)).hasSize(2);
  }

  /**
   * Verifies that nginx loaded the configuration without a warning.
   *
   * @throws Exception if a request cannot be sent
   */
  @Test
  @DisplayName("nginx starts the admission without a warning")
  void nginxStartsWithoutAWarning() throws Exception {
    assertThat(mismatches(List.of(new EdgeAdmission.Request("GET", "/api/v1/orders", 200))))
        .isEmpty();
    assertThat(edge.getLogs()).doesNotContain("[warn]").doesNotContain("[emerg]");
  }

  /**
   * Sends every request and collects the ones whose status differs from the expectation.
   *
   * @param requests the requests with their expected statuses
   * @return {@code VERB path: expected X, got Y} per difference
   * @throws Exception if a request cannot be sent
   */
  private static List<String> mismatches(List<EdgeAdmission.Request> requests) throws Exception {
    String base = "http://" + edge.getHost() + ":" + edge.getMappedPort(8080);
    List<String> differences = new ArrayList<>();
    for (EdgeAdmission.Request request : requests) {
      HttpRequest http =
          HttpRequest.newBuilder(URI.create(base + request.path()))
              .method(request.method(), HttpRequest.BodyPublishers.noBody())
              .timeout(Duration.ofSeconds(10))
              .build();
      int status = client.send(http, HttpResponse.BodyHandlers.discarding()).statusCode();
      if (status != request.status()) {
        differences.add(
            request.method()
                + " "
                + request.path()
                + ": expected "
                + request.status()
                + ", got "
                + status);
      }
    }
    return differences;
  }

  /**
   * Reads the edge's pinned image from {@code docker-compose.yml}, the image production runs.
   *
   * @return the image reference
   * @throws IOException if the compose file cannot be read
   */
  private static @NotNull String edgeImage() throws IOException {
    Matcher matcher = EDGE_IMAGE.matcher(EdgeAdmissionTest.committed("docker-compose.yml"));
    assertThat(matcher.find()).as("no nginx-unprivileged image in docker-compose.yml").isTrue();
    return matcher.group(1);
  }
}
