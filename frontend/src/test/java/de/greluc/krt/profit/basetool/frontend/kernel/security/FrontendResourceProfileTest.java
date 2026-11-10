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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendCallPolicyFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

/** Characterizes near-cap parallel transfers and inactive OAuth retention in an isolated JVM. */
@Tag("resource-profile")
class FrontendResourceProfileTest {

  private static final long PAYLOAD_BYTES = 63_000_000;
  private static final int CONCURRENCY = 4;
  private static final int CHUNK_BYTES = 16 * 1024;
  private static final int OAUTH_CLIENTS = 5000;
  private static final int TOKEN_CHARACTERS = 1024;
  private static final byte[] CHUNK = new byte[CHUNK_BYTES];

  @Test
  void writesAReproducibleResourceReport() throws Exception {
    Path directory =
        Path.of(System.getProperty("basetool.resource-profile-output"))
            .toAbsolutePath()
            .normalize();
    assertThat(directory.toString().replace('\\', '/')).startsWith("D:/Coding/Agents/Temp/codex/");
    Files.createDirectories(directory);
    Path payload = directory.resolve("synthetic-near-cap.json");
    writePayload(payload);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("java", System.getProperty("java.version"));
    report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
    report.put("concurrency", CONCURRENCY);
    report.put("payloadBytesPerTransfer", PAYLOAD_BYTES);
    report.put("heapSamplingMillis", 5);
    try (var serverThreads = Executors.newVirtualThreadPerTaskExecutor()) {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(serverThreads);
      server.createContext("/", FrontendResourceProfileTest::serve);
      server.start();
      try {
        WebClient transport =
            WebClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
                .build();
        BackendApiClient backend =
            new BackendApiClient(
                transport,
                transport,
                new SimpleMeterRegistry(),
                new NoOpCacheManager(),
                BackendCallPolicyFixture.create());
        CatalogueBackendClient catalogue = new CatalogueBackendClient(backend);
        backend.get("/warmup", String.class);
        report.put("bufferedUploads", measure(() -> uploads(catalogue, payload, true)));
        report.put("streamedUploads", measure(() -> uploads(catalogue, payload, false)));
        report.put("bufferedDownloads", measure(() -> downloads(backend, true)));
        report.put("streamedDownloads", measure(() -> downloads(backend, false)));
      } finally {
        server.stop(0);
      }
    }
    report.put("oauthRetention", oauthRetention());
    Files.writeString(
        directory.resolve("frontend-resources.json"),
        JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
    Files.delete(payload);
  }

  private static Map<String, Long> measure(Supplier<Long> operation) throws Exception {
    collect();
    long before = heapUsed();
    AtomicLong peak = new AtomicLong(before);
    long started = System.nanoTime();
    long processed;
    try (var sampler = Executors.newSingleThreadScheduledExecutor()) {
      sampler.scheduleAtFixedRate(
          () -> peak.accumulateAndGet(heapUsed(), Math::max), 0, 5, TimeUnit.MILLISECONDS);
      processed = operation.get();
      peak.accumulateAndGet(heapUsed(), Math::max);
    }
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    collect();
    return Map.of(
        "heapBeforeBytes",
        before,
        "sampledPeakHeapBytes",
        peak.get(),
        "retainedHeapAfterGcBytes",
        heapUsed(),
        "elapsedMillis",
        elapsed,
        "processedBytes",
        processed);
  }

  private static long uploads(CatalogueBackendClient catalogue, Path payload, boolean buffered) {
    CyclicBarrier start = new CyclicBarrier(CONCURRENCY);
    return parallel(() -> {
      var resource =
          buffered ? new ByteArrayResource(readPayload(payload)) : new FileSystemResource(payload);
      await(start);
      catalogue.enqueueP4kImport(resource, "synthetic.json");
      return PAYLOAD_BYTES;
    });
  }

  private static long downloads(BackendApiClient backend, boolean buffered) {
    CyclicBarrier start = new CyclicBarrier(CONCURRENCY);
    CyclicBarrier retained = new CyclicBarrier(CONCURRENCY);
    return parallel(() -> {
      await(start);
      if (buffered) {
        byte[] bytes =
            backend.execute(
                HttpMethod.GET,
                "/download",
                web -> web.get().uri("/download"),
                response -> response.bodyToMono(byte[].class));
        await(retained);
        assertThat(bytes.length).isEqualTo(PAYLOAD_BYTES);
        return (long) bytes.length;
      }
      AtomicLong written = new AtomicLong();
      backend
          .download("/download", web -> web.get().uri("/download"))
          .writeTo(
              new OutputStream() {
                @Override
                public void write(int value) {
                  written.incrementAndGet();
                }

                @Override
                public void write(byte[] bytes, int offset, int length) {
                  written.addAndGet(length);
                }
              },
              _ -> {});
      assertThat(written.get()).isEqualTo(PAYLOAD_BYTES);
      return written.get();
    });
  }

  private static long parallel(Supplier<Long> operation) {
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<java.util.concurrent.Future<Long>>();
      for (int i = 0; i < CONCURRENCY; i++) {
        futures.add(workers.submit(operation::get));
      }
      long total = 0;
      for (var future : futures) {
        total += future.get(45, TimeUnit.SECONDS);
      }
      return total;
    } catch (Exception failure) {
      throw new IllegalStateException("Synthetic transfer characterization failed", failure);
    }
  }

  private static void serve(HttpExchange exchange) throws IOException {
    try (exchange) {
      if (exchange.getRequestMethod().equals("POST")) {
        long received = exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
        assertThat(received).isBetween(PAYLOAD_BYTES, PAYLOAD_BYTES + 1024);
        byte[] response = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
      } else if (exchange.getRequestURI().getPath().equals("/download")) {
        exchange.sendResponseHeaders(200, PAYLOAD_BYTES);
        writeBytes(exchange.getResponseBody(), PAYLOAD_BYTES);
      } else {
        exchange.sendResponseHeaders(200, 2);
        exchange.getResponseBody().write("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
    }
  }

  private static Map<String, Long> oauthRetention() throws Exception {
    ClientRegistration registration =
        ClientRegistration.withRegistrationId("profile")
            .clientId("synthetic-profile")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://example.test/callback")
            .authorizationUri("https://example.test/authorize")
            .tokenUri("https://example.test/token")
            .build();
    Map<String, OAuth2AuthorizedClient> baseline = new ConcurrentHashMap<>();
    List<WeakReference<OAuth2AuthorizedClient>> baselineReferences = new ArrayList<>();
    Instant expiry = Instant.now().plusSeconds(62);
    collect();
    long beforeBaseline = heapUsed();
    for (int i = 0; i < OAUTH_CLIENTS; i++) {
      OAuth2AuthorizedClient client = authorizedClient(registration, "buffered-" + i, expiry);
      baseline.put(client.getPrincipalName(), client);
      baselineReferences.add(new WeakReference<>(client));
    }
    collect();
    long baselineRetained = heapUsed() - beforeBaseline;
    List<WeakReference<OAuth2AuthorizedClient>> expiringReferences = new ArrayList<>();
    Instant tokenExpiry = Instant.now().plusSeconds(62);
    SingleFlightAuthorizedClientManager manager =
        new SingleFlightAuthorizedClientManager(request -> {
          OAuth2AuthorizedClient client =
              authorizedClient(registration, request.getPrincipal().getName(), tokenExpiry);
          expiringReferences.add(new WeakReference<>(client));
          return client;
        });
    collect();
    long beforeExpiring = heapUsed();
    for (int i = 0; i < OAUTH_CLIENTS; i++) {
      manager.authorize(
          OAuth2AuthorizeRequest.withClientRegistrationId("profile")
              .principal(new TestingAuthenticationToken("expiring-" + i, "unused"))
              .build());
    }
    collect();
    long expiringRetained = heapUsed() - beforeExpiring;
    long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
    while (System.nanoTime() < deadline && live(expiringReferences) > 0) {
      Thread.sleep(250);
      collect();
    }
    long remaining = live(expiringReferences);
    assertThat(remaining).isZero();
    assertThat(live(baselineReferences)).isEqualTo(OAUTH_CLIENTS);
    long afterExpiry = heapUsed() - beforeExpiring;
    Reference.reachabilityFence(manager);
    Reference.reachabilityFence(baseline);
    return Map.of(
        "clients",
        (long) OAUTH_CLIENTS,
        "tokenCharacters",
        (long) TOKEN_CHARACTERS,
        "bufferedRetainedHeapDeltaBytes",
        baselineRetained,
        "expiringRetainedHeapDeltaBytes",
        expiringRetained,
        "expiringHeapDeltaAfterNaturalExpiryBytes",
        afterExpiry,
        "bufferedClientsStillReachable",
        live(baselineReferences),
        "expiringClientsStillReachable",
        remaining);
  }

  private static OAuth2AuthorizedClient authorizedClient(
      ClientRegistration registration, String principal, Instant expiry) {
    return new OAuth2AuthorizedClient(
        registration,
        principal,
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            principal + "x".repeat(TOKEN_CHARACTERS - principal.length()),
            Instant.now().minusSeconds(1),
            expiry));
  }

  private static long live(List<WeakReference<OAuth2AuthorizedClient>> references) {
    return references.stream().filter(reference -> reference.get() != null).count();
  }

  private static void writePayload(Path path) throws IOException {
    try (OutputStream output = Files.newOutputStream(path)) {
      writeBytes(output, PAYLOAD_BYTES);
    }
  }

  private static void writeBytes(OutputStream output, long count) throws IOException {
    for (long remaining = count; remaining > 0; remaining -= Math.min(remaining, CHUNK.length)) {
      output.write(CHUNK, 0, (int) Math.min(remaining, CHUNK.length));
    }
  }

  private static byte[] readPayload(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    }
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(30, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new IllegalStateException("Synthetic workers failed to rendezvous", failure);
    }
  }

  private static void collect() throws InterruptedException {
    ManagementFactory.getMemoryMXBean().gc();
    Thread.sleep(50);
  }

  private static long heapUsed() {
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }
}
