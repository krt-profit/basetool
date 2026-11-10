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

package de.greluc.krt.profit.basetool.frontend.kernel.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Exercises fast headers, delayed bodies and bounded binary response consumption. */
class BackendOperationLifetimeTest {

  private static BackendCallPolicy policy(
      Duration timeout, BulkheadRegistry bulkheads, CircuitBreakerRegistry circuits) {
    AppHttpProperties properties =
        new AppHttpProperties(
            Duration.ofSeconds(1),
            timeout,
            Duration.ofSeconds(30),
            timeout,
            timeout,
            AppHttpProperties.BackendProtocol.HTTP11,
            20,
            AppHttpProperties.BackendCodec.JSON,
            true);
    return new BackendCallPolicy(
        circuits,
        RetryRegistry.of(
            RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(1)).build()),
        TimeLimiterRegistry.of(TimeLimiterConfig.custom().timeoutDuration(timeout).build()),
        bulkheads,
        properties);
  }

  private static BackendApiClient client(WebClient webClient, BackendCallPolicy policy) {
    return new BackendApiClient(
        webClient, webClient, new SimpleMeterRegistry(), new NoOpCacheManager(), policy);
  }

  @Test
  void immediateHeadersDoNotReleaseThePermitWhileTheBodyIsPending() throws Exception {
    CountDownLatch bodySubscribed = new CountDownLatch(1);
    Sinks.One<String> body = Sinks.one();
    BulkheadRegistry bulkheads =
        BulkheadRegistry.of(
            BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
    CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
    BackendCallPolicy policy = policy(Duration.ofSeconds(10), bulkheads, circuits);
    WebClient transport =
        WebClient.builder()
            .exchangeFunction(_ ->
                Mono.just(
                    ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(
                            body.asMono()
                                .doOnSubscribe(_ -> bodySubscribed.countDown())
                                .<DataBuffer>map(text ->
                                    DefaultDataBufferFactory.sharedInstance.wrap(
                                        text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                                .flux())
                        .build()))
            .build();
    BackendApiClient client = client(transport, policy);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> client.get("/first", String.class));
      assertThat(bodySubscribed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(bulkheads.bulkhead("backendApi").getMetrics().getAvailableConcurrentCalls())
          .isZero();

      assertThatThrownBy(() -> client.post("/second", null, String.class))
          .isInstanceOfSatisfying(
              BackendServiceException.class,
              failure -> assertThat(failure.getStatusCode()).isEqualTo(503));

      body.tryEmitValue("{\"saved\":true}");
      assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("{\"saved\":true}");
    }
    assertThat(bulkheads.bulkhead("backendApi").getMetrics().getAvailableConcurrentCalls())
        .isEqualTo(1);
  }

  @Test
  void aSlowBodyTimesOutAndIsRecordedAsAFailure() {
    BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
    CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
    BackendCallPolicy policy = policy(Duration.ofMillis(30), bulkheads, circuits);
    WebClient transport =
        WebClient.builder()
            .exchangeFunction(_ ->
                Mono.just(
                    ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(Flux.never())
                        .build()))
            .build();

    assertThatThrownBy(() -> client(transport, policy).post("/slow", null, String.class))
        .isInstanceOfSatisfying(
            BackendServiceException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(504));
    assertThat(circuits.circuitBreaker("backendApi").getMetrics().getNumberOfFailedCalls())
        .isEqualTo(1);
    assertThat(bulkheads.bulkhead("backendApi").getMetrics().getAvailableConcurrentCalls())
        .isEqualTo(bulkheads.bulkhead("backendApi").getBulkheadConfig().getMaxConcurrentCalls());
  }

  @Test
  void aKnownClientRefusalWithAStalledBodyKeepsItsStatusWithoutRetryOrCircuitFailure() {
    AtomicInteger attempts = new AtomicInteger();
    CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
    BackendCallPolicy policy =
        policy(Duration.ofMillis(30), BulkheadRegistry.ofDefaults(), circuits);
    WebClient transport =
        WebClient.builder()
            .filter(BackendResponseStatus.observer())
            .exchangeFunction(_ -> {
              attempts.incrementAndGet();
              return Mono.just(
                  ClientResponse.create(HttpStatus.FORBIDDEN)
                      .header("Content-Type", "application/problem+json")
                      .body(Flux.never())
                      .build());
            })
            .build();

    assertThatThrownBy(() -> client(transport, policy).get("/forbidden", String.class))
        .isInstanceOfSatisfying(
            BackendServiceException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(403));
    assertThat(attempts.get()).isEqualTo(1);
    assertThat(circuits.circuitBreaker("backendApi").getMetrics().getNumberOfFailedCalls())
        .isZero();
  }

  @Test
  void anOversizedClientRefusalBodyDoesNotChangeItsStatusOrRetry() {
    AtomicInteger attempts = new AtomicInteger();
    CircuitBreakerRegistry circuits = CircuitBreakerRegistry.ofDefaults();
    WebClient transport =
        WebClient.builder()
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(64))
            .filter(BackendResponseStatus.observer())
            .exchangeFunction(_ -> {
              attempts.incrementAndGet();
              return Mono.just(
                  ClientResponse.create(
                          HttpStatus.TOO_MANY_REQUESTS,
                          org.springframework.web.reactive.function.client.ExchangeStrategies
                              .builder()
                              .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(64))
                              .build())
                      .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(new byte[4096])))
                      .build());
            })
            .build();

    assertThatThrownBy(() ->
            client(
                    transport,
                    policy(Duration.ofSeconds(1), BulkheadRegistry.ofDefaults(), circuits))
                .get("/rate-limited", String.class))
        .isInstanceOfSatisfying(
            BackendServiceException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(429));
    assertThat(attempts.get()).isEqualTo(1);
    assertThat(circuits.circuitBreaker("backendApi").getMetrics().getNumberOfFailedCalls())
        .isZero();
  }

  @Test
  void binaryTransfersCanExceedTheCodecCeilingWithoutCollectingTheFile() {
    int chunkSize = 16 * 1024;
    int chunks = 4608;
    byte[] chunk = new byte[chunkSize];
    AtomicLong written = new AtomicLong();
    WebClient transport =
        WebClient.builder()
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(1024))
            .exchangeFunction(_ ->
                Mono.just(
                    ClientResponse.create(
                            HttpStatus.OK,
                            org.springframework.web.reactive.function.client.ExchangeStrategies
                                .builder()
                                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(1024))
                                .build())
                        .body(
                            Flux.range(0, chunks)
                                .map(_ -> DefaultDataBufferFactory.sharedInstance.wrap(chunk)))
                        .build()))
            .build();
    BackendApiClient client = client(transport, BackendCallPolicyFixture.create());

    client
        .download("/large", web -> web.get().uri("/large"))
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

    assertThat(written.get()).isEqualTo((long) chunkSize * chunks);
  }

  @Test
  void aDisconnectedOutputIsNeverReplayedAndReleasesItsPermit() {
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger cancellations = new AtomicInteger();
    BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
    BackendCallPolicy policy =
        policy(Duration.ofSeconds(10), bulkheads, CircuitBreakerRegistry.ofDefaults());
    WebClient transport =
        WebClient.builder()
            .exchangeFunction(_ -> {
              attempts.incrementAndGet();
              return Mono.just(
                  ClientResponse.create(HttpStatus.OK)
                      .body(
                          Flux.range(0, 100)
                              .<DataBuffer>map(_ ->
                                  DefaultDataBufferFactory.sharedInstance.wrap(new byte[16 * 1024]))
                              .doOnCancel(cancellations::incrementAndGet))
                      .build());
            })
            .build();

    assertThatThrownBy(() ->
            client(transport, policy)
                .download("/export", web -> web.get().uri("/export"))
                .writeTo(
                    new OutputStream() {
                      @Override
                      public void write(int value) throws IOException {
                        throw new IOException("synthetic disconnected output");
                      }
                    },
                    _ -> {}))
        .isInstanceOf(BackendServiceException.class);

    assertThat(attempts.get()).isEqualTo(1);
    assertThat(cancellations.get()).isPositive();
    assertThat(bulkheads.bulkhead("backendApi").getMetrics().getAvailableConcurrentCalls())
        .isEqualTo(bulkheads.bulkhead("backendApi").getBulkheadConfig().getMaxConcurrentCalls());
  }
}
