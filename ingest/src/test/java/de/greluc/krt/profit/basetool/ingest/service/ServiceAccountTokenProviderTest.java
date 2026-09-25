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

package de.greluc.krt.profit.basetool.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.ingest.config.ServiceAccountProperties;
import de.greluc.krt.profit.basetool.ingest.support.TestProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Behaviour of the gateway's own identity for the backend hop (ADR-0129).
 *
 * <p>This grant sits on the critical path of <em>every</em> ingest upload since the gateway stopped
 * relaying the caller's token, and it fails in a hop no client can see. The cases that matter are
 * therefore the unhappy ones: an unconfigured gateway, a refusing Keycloak, and an answer that
 * parses but carries nothing usable.
 */
class ServiceAccountTokenProviderTest {

  private MockWebServer keycloak;
  private MeterRegistry meterRegistry;

  @BeforeEach
  void startServer() throws IOException {
    keycloak = new MockWebServer();
    keycloak.start(0);
    meterRegistry = new SimpleMeterRegistry();
  }

  @AfterEach
  void stopServer() throws IOException {
    keycloak.shutdown();
  }

  private ServiceAccountTokenProvider provider(boolean configured) {
    return new ServiceAccountTokenProvider(
        properties(configured), RestClient.create(), meterRegistry);
  }

  /**
   * A provider on a clock the test moves by hand, so expiry and backoff can be crossed without
   * sleeping.
   *
   * @param clock the mutable test clock
   * @return a configured provider reading that clock
   */
  private ServiceAccountTokenProvider provider(MutableClock clock) {
    return new ServiceAccountTokenProvider(
        properties(true), RestClient.create(), meterRegistry, clock);
  }

  private ServiceAccountProperties properties(boolean configured) {
    return configured
        ? TestProperties.serviceAccount(
            "token-uri",
            keycloak.url("/token").toString(),
            "client-id",
            "basetool-ingest-gateway",
            "client-secret",
            "s3cret")
        : TestProperties.serviceAccount();
  }

  private static MockResponse token(String accessToken, int expiresIn) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"access_token\":\"" + accessToken + "\",\"expires_in\":" + expiresIn + "}");
  }

  /** The grant is a client-credentials POST carrying the configured client id and secret. */
  @Test
  void mintsATokenWithTheClientCredentialsGrant() throws InterruptedException {
    keycloak.enqueue(token("AT-1", 300));

    assertThat(provider(true).currentToken()).isEqualTo("AT-1");

    RecordedRequest request = keycloak.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    String body = request.getBody().readString(StandardCharsets.UTF_8);
    assertThat(body).contains("grant_type=client_credentials");
    assertThat(body).contains("client_id=basetool-ingest-gateway");
    assertThat(outcome("minted")).isEqualTo(1.0);
  }

  /**
   * A still-valid token is reused rather than re-minted.
   *
   * <p>Not a micro-optimisation: without the cache the gateway asks Keycloak for a token on every
   * single upload, which turns an identity into a rate limit.
   */
  @Test
  void reusesACachedTokenUntilItNearsExpiry() {
    keycloak.enqueue(token("AT-1", 300));
    ServiceAccountTokenProvider provider = provider(true);

    assertThat(provider.currentToken()).isEqualTo("AT-1");
    assertThat(provider.currentToken()).isEqualTo("AT-1");
    assertThat(provider.currentToken()).isEqualTo("AT-1");

    assertThat(keycloak.getRequestCount()).as("one grant, not three").isEqualTo(1);
    assertThat(outcome("cached")).isEqualTo(2.0);
  }

  /**
   * A token whose remaining life is inside the refresh skew is replaced.
   *
   * <p>The skew exists so a token that expires in flight never reaches the backend — the failure
   * would surface there as an opaque 401 with no hint that the clock, not the credential, was
   * wrong.
   */
  @Test
  void reMintsWhenTheCachedTokenIsInsideTheRefreshSkew() {
    keycloak.enqueue(token("AT-1", 10));
    keycloak.enqueue(token("AT-2", 300));
    ServiceAccountTokenProvider provider = provider(true);

    assertThat(provider.currentToken()).isEqualTo("AT-1");
    assertThat(provider.currentToken()).isEqualTo("AT-2");
    assertThat(keycloak.getRequestCount()).isEqualTo(2);
  }

  /** A refusing Keycloak fails loudly and is counted, rather than yielding a null token. */
  @Test
  void failsLoudlyWhenKeycloakRefuses() {
    keycloak.enqueue(
        new MockResponse().setResponseCode(401).setBody("{\"error\":\"invalid_client\"}"));

    assertThatThrownBy(() -> provider(true).currentToken())
        .isInstanceOf(ServiceAccountTokenProvider.ServiceAccountTokenException.class);
    assertThat(outcome("failed")).isEqualTo(1.0);
  }

  /**
   * A 200 that carries no access token is a failure, not an empty success.
   *
   * <p>Without this the gateway would put an empty bearer on the backend hop, and the backend would
   * answer a malformed-token 401 — the failure would be reported one layer away from its cause,
   * which is exactly the shape of the incident this whole change exists to fix.
   */
  @Test
  void failsWhenTheAnswerCarriesNoAccessToken() {
    keycloak.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"expires_in\":300}"));

    assertThatThrownBy(() -> provider(true).currentToken())
        .isInstanceOf(ServiceAccountTokenProvider.ServiceAccountTokenException.class);
    assertThat(outcome("failed")).isEqualTo(1.0);
  }

  /** An unconfigured gateway says so, instead of sending a null token nobody can diagnose. */
  @Test
  void refusesWithANamedErrorWhenUnconfigured() {
    ServiceAccountTokenProvider provider = provider(false);

    assertThat(provider.isConfigured()).isFalse();
    assertThatThrownBy(provider::currentToken)
        .isInstanceOf(ServiceAccountTokenProvider.ServiceAccountTokenException.class)
        .hasMessageContaining("app.ingest.service-account");
    assertThat(keycloak.getRequestCount()).as("nothing is sent").isZero();
  }

  /** A partially configured gateway is treated as unconfigured — all three values or none. */
  @Test
  void treatsAPartialConfigurationAsUnconfigured() {
    ServiceAccountProperties properties =
        TestProperties.serviceAccount(
            "token-uri", keycloak.url("/token").toString(), "client-id", "basetool-ingest-gateway");
    ServiceAccountTokenProvider provider =
        new ServiceAccountTokenProvider(properties, RestClient.create(), meterRegistry);

    assertThat(provider.isConfigured()).isFalse();
  }

  /** A cold cache under concurrent load produces one grant, not one per caller. */
  @Test
  void mintsOnceUnderConcurrentFirstUse() throws InterruptedException {
    keycloak.enqueue(
        token("AT-1", 300)
            .setBodyDelay(
                Duration.ofMillis(150).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
    ServiceAccountTokenProvider provider = provider(true);

    Thread[] callers = new Thread[4];
    for (int i = 0; i < callers.length; i++) {
      callers[i] = new Thread(provider::currentToken);
      callers[i].start();
    }
    for (Thread caller : callers) {
      caller.join();
    }

    assertThat(keycloak.getRequestCount()).as("the mint lock collapses the burst").isEqualTo(1);
  }

  /**
   * A heavier burst on a cold cache: every caller gets the same token, one grant is made, and the
   * others are served from the cache — the pair of token and expiry is published as one value, so
   * no reader can see a token without its own expiry.
   */
  @Test
  void everyConcurrentCallerSeesTheOneMintedToken() throws Exception {
    keycloak.enqueue(token("AT-1", 300).setBodyDelay(100, TimeUnit.MILLISECONDS));
    ServiceAccountTokenProvider provider = provider(true);
    int callers = 16;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<String>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
      for (int i = 0; i < callers; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return provider.currentToken();
                }));
      }
      start.countDown();
      for (Future<String> result : results) {
        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("AT-1");
      }
    }

    assertThat(keycloak.getRequestCount()).isEqualTo(1);
    assertThat(outcome("minted")).isEqualTo(1.0);
    assertThat(outcome("cached")).isEqualTo(callers - 1.0);
  }

  /**
   * {@code invalidate()} drops the cache: the next call mints a fresh token although the old one
   * had minutes left. This is what the backend-401/403 mapping relies on (ING-SEC-02) — a refused
   * token must not be replayed until its natural expiry.
   */
  @Test
  void invalidateForcesTheNextCallToMintAFreshToken() {
    keycloak.enqueue(token("AT-1", 300));
    keycloak.enqueue(token("AT-2", 300));
    ServiceAccountTokenProvider provider = provider(true);

    assertThat(provider.currentToken()).isEqualTo("AT-1");
    provider.invalidate();

    assertThat(provider.currentToken()).isEqualTo("AT-2");
    assertThat(keycloak.getRequestCount()).isEqualTo(2);
  }

  /** Invalidating a cold cache is harmless: the next call simply mints as it would have anyway. */
  @Test
  void invalidateOnAColdCacheIsANoOp() {
    keycloak.enqueue(token("AT-1", 300));
    ServiceAccountTokenProvider provider = provider(true);

    provider.invalidate();

    assertThat(provider.currentToken()).isEqualTo("AT-1");
  }

  /**
   * After a failed grant the provider refuses at once for the backoff window instead of calling
   * Keycloak again — and tries again once the window has passed.
   */
  @Test
  void backsOffAfterAFailedGrantAndRetriesOnceTheWindowHasPassed() {
    keycloak.enqueue(new MockResponse().setResponseCode(503));
    keycloak.enqueue(token("AT-1", 300));
    MutableClock clock = new MutableClock(Instant.parse("2026-09-22T10:00:00Z"));
    ServiceAccountTokenProvider provider = provider(clock);

    assertThatThrownBy(provider::currentToken)
        .isInstanceOf(ServiceAccountTokenProvider.ServiceAccountTokenException.class);
    assertThatThrownBy(provider::currentToken)
        .isInstanceOf(ServiceAccountTokenProvider.ServiceAccountTokenException.class)
        .hasMessageContaining("backing off");
    assertThat(keycloak.getRequestCount()).as("the backoff sends nothing").isEqualTo(1);
    assertThat(outcome("failed")).isEqualTo(1.0);
    assertThat(outcome("backoff")).isEqualTo(1.0);

    clock.advance(ServiceAccountTokenProvider.FAILURE_BACKOFF.plusMillis(1));

    assertThat(provider.currentToken()).isEqualTo("AT-1");
    assertThat(keycloak.getRequestCount()).isEqualTo(2);
  }

  /** The cache honours the clock: a token is served until expiry minus skew, then re-minted. */
  @Test
  void servesTheCachedTokenUntilExpiryMinusSkewOnTheInjectedClock() {
    keycloak.enqueue(token("AT-1", 90));
    keycloak.enqueue(token("AT-2", 90));
    MutableClock clock = new MutableClock(Instant.parse("2026-09-22T10:00:00Z"));
    ServiceAccountTokenProvider provider = provider(clock);

    assertThat(provider.currentToken()).isEqualTo("AT-1");
    clock.advance(Duration.ofSeconds(59));
    assertThat(provider.currentToken()).as("90s - 30s skew, still inside").isEqualTo("AT-1");
    clock.advance(Duration.ofSeconds(1));
    assertThat(provider.currentToken()).isEqualTo("AT-2");
  }

  /** A clock a test advances by hand. */
  private static final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /**
   * Reads one outcome counter.
   *
   * @param value the bounded outcome tag
   * @return the current count, or 0 when the series does not exist
   */
  private double outcome(String value) {
    var counter =
        meterRegistry.find("basetool.ingest.service.account.token").tag("outcome", value).counter();
    return counter == null ? 0.0 : counter.count();
  }
}
