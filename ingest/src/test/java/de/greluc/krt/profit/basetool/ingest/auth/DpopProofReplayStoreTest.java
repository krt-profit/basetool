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

package de.greluc.krt.profit.basetool.ingest.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.ECKey;
import de.greluc.krt.profit.basetool.ingest.auth.DpopProofReplayStore.Outcome;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.DPoPProofContext;
import org.springframework.security.oauth2.jwt.DPoPProofJwtDecoderFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * Tests the partitioned DPoP replay cache: one member at its cap cannot lock another out, the
 * exchange cache and the one of every other path are apart, and the verifier fails closed on an
 * unreadable target (REQ-XCH-006, REQ-XCH-023).
 */
class DpopProofReplayStoreTest {

  private static final String OTHER = "/unrouted";

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  /** A clock the tests move by hand. */
  private static final class MovableClock extends Clock {

    private Instant now = Instant.parse("2026-09-27T12:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private DpopProofReplayStore store(int perMember, int total, Clock clock) {
    return new DpopProofReplayStore(
        MetricNames.PATH_SCOPE_EXCHANGE, perMember, total, meterRegistry, clock);
  }

  private double refused(String scope, String reason) {
    return meterRegistry
        .get(MetricNames.DPOP_REPLAY_REFUSED)
        .tag(MetricNames.TAG_PATH_SCOPE, scope)
        .tag(MetricNames.TAG_REASON, reason)
        .counter()
        .count();
  }

  @Test
  void aMemberAtItsCapIsRefusedWhileAnotherMemberStillPasses() {
    MovableClock clock = new MovableClock();
    DpopProofReplayStore store = store(3, 1000, clock);
    Instant expiry = clock.instant().plusSeconds(30);

    for (int i = 0; i < 3; i++) {
      assertThat(store.claim("a-" + i, expiry, "member-a").outcome()).isEqualTo(Outcome.STORED);
    }

    assertThat(store.claim("a-3", expiry, "member-a").outcome()).isEqualTo(Outcome.MEMBER_CAP);
    assertThat(store.claim("b-0", expiry, "member-b").outcome()).isEqualTo(Outcome.STORED);
    assertThat(refused(MetricNames.PATH_SCOPE_EXCHANGE, MetricNames.DPOP_REPLAY_MEMBER_CAP))
        .isEqualTo(1.0d);
  }

  @Test
  void aReplayedJtiIsRefusedAndDoesNotCountAgainstTheMember() {
    MovableClock clock = new MovableClock();
    DpopProofReplayStore store = store(2, 1000, clock);
    Instant expiry = clock.instant().plusSeconds(30);

    assertThat(store.claim("jti", expiry, "member-a").outcome()).isEqualTo(Outcome.STORED);
    assertThat(store.claim("jti", expiry, "member-a").outcome()).isEqualTo(Outcome.REPLAYED);
    assertThat(store.claim("jti", expiry, "member-b").outcome()).isEqualTo(Outcome.REPLAYED);

    assertThat(store.claim("other", expiry, "member-a").outcome()).isEqualTo(Outcome.STORED);
    assertThat(refused(MetricNames.PATH_SCOPE_EXCHANGE, MetricNames.DPOP_REPLAY_REPLAYED))
        .isEqualTo(2.0d);
  }

  @Test
  void expiredProofsGiveTheMemberItsRoomBack() {
    MovableClock clock = new MovableClock();
    DpopProofReplayStore store = store(2, 1000, clock);
    Instant expiry = clock.instant().plusSeconds(30);
    store.claim("a-0", expiry, "member-a");
    store.claim("a-1", expiry, "member-a");
    assertThat(store.claim("a-2", expiry, "member-a").outcome()).isEqualTo(Outcome.MEMBER_CAP);

    clock.advance(Duration.ofSeconds(45));

    assertThat(store.claim("a-3", clock.instant().plusSeconds(30), "member-a").outcome())
        .isEqualTo(Outcome.STORED);
    assertThat(store.size()).isEqualTo(1);
  }

  @Test
  void aMemberAtItsCapLearnsWhenItsEarliestProofExpiresAndTakesNoRoom() {
    MovableClock clock = new MovableClock();
    DpopProofReplayStore store = store(2, 1000, clock);
    store.claim("a-late", clock.instant().plusSeconds(30), "member-a");
    store.claim("a-early", clock.instant().plusSeconds(20), "member-a");

    DpopProofReplayStore.Claim capped =
        store.claim("a-2", clock.instant().plusSeconds(30), "member-a");

    assertThat(capped.outcome()).isEqualTo(Outcome.MEMBER_CAP);
    assertThat(capped.retryAfterSeconds()).isEqualTo(21L);
    assertThat(store.size()).isEqualTo(2);

    clock.advance(Duration.ofMillis(9_500));
    assertThat(store.claim("a-3", clock.instant().plusSeconds(30), "member-a").retryAfterSeconds())
        .isEqualTo(11L);
  }

  @Test
  void aMembersExpiredProofsMakeRoomBeforeTheNextSweep() {
    MovableClock clock = new MovableClock();
    DpopProofReplayStore store = store(2, 1000, clock);
    store.claim("a-0", clock.instant().plusSeconds(5), "member-a");
    store.claim("a-1", clock.instant().plusSeconds(30), "member-a");

    clock.advance(Duration.ofSeconds(6));

    assertThat(store.claim("a-2", clock.instant().plusSeconds(30), "member-a").outcome())
        .isEqualTo(Outcome.STORED);
    assertThat(store.size()).isEqualTo(2);
  }

  @Test
  void theRetryAfterRoundsUpAndIsAtLeastOneSecond() {
    Instant now = Instant.parse("2026-09-27T12:00:00Z");

    assertThat(DpopProofReplayStore.retryAfterSeconds(now, now)).isEqualTo(1L);
    assertThat(DpopProofReplayStore.retryAfterSeconds(now, now.plusMillis(1))).isEqualTo(1L);
    assertThat(DpopProofReplayStore.retryAfterSeconds(now, now.plusMillis(1_001))).isEqualTo(2L);
    assertThat(DpopProofReplayStore.retryAfterSeconds(now, now.plusSeconds(30))).isEqualTo(31L);
  }

  @Test
  void aFullStoreRefusesAndCountsIt() {
    MovableClock clock = new MovableClock();
    DpopProofReplayStore store = store(1, 2, clock);
    Instant expiry = clock.instant().plusSeconds(30);
    store.claim("a", expiry, "member-a");
    store.claim("b", expiry, "member-b");

    assertThat(store.claim("c", expiry, "member-c").outcome()).isEqualTo(Outcome.FULL);
    assertThat(refused(MetricNames.PATH_SCOPE_EXCHANGE, MetricNames.DPOP_REPLAY_FULL))
        .isEqualTo(1.0d);
  }

  @Test
  void aFullStoreTellsWhenItsEarliestProofExpiresAndTakesNoRoom() {
    MovableClock clock = new MovableClock();
    DpopProofReplayStore store = store(1, 2, clock);
    store.claim("a", clock.instant().plusSeconds(30), "member-a");
    store.claim("b", clock.instant().plusSeconds(20), "member-b");

    DpopProofReplayStore.Claim full = store.claim("c", clock.instant().plusSeconds(30), "member-c");

    assertThat(full.outcome()).isEqualTo(Outcome.FULL);
    assertThat(full.retryAfterSeconds()).isEqualTo(21L);
    assertThat(store.size()).isEqualTo(2);

    clock.advance(Duration.ofMillis(12_500));
    assertThat(store.claim("d", clock.instant().plusSeconds(30), "member-c").retryAfterSeconds())
        .isEqualTo(8L);

    clock.advance(Duration.ofSeconds(8));
    assertThat(store.claim("e", clock.instant().plusSeconds(30), "member-c").outcome())
        .isEqualTo(Outcome.STORED);
  }

  @Test
  void anExchangeProofRefusedByAFullStoreIsReportedAsStoreFull() throws Exception {
    DpopProofReplayStore exchange =
        new DpopProofReplayStore(
            MetricNames.PATH_SCOPE_EXCHANGE, 1, 1, meterRegistry, Clock.systemUTC());
    ExchangeDpopNonces nonces = new ExchangeDpopNonces();
    DPoPProofJwtDecoderFactory factory =
        ExchangeDpopProofValidation.factory(
            nonces,
            new DpopProofReplayStores(
                exchange,
                new DpopProofReplayStore(
                    MetricNames.PATH_SCOPE_OTHER, 1, 1, meterRegistry, Clock.systemUTC())));
    ECKey keyA = ExchangeTestSupport.newKey();
    ECKey keyB = ExchangeTestSupport.newKey();
    Jwt tokenA = token("token-a", keyA);
    Jwt tokenB = token("token-b", keyB);
    String first =
        ExchangeTestSupport.proof(
            keyA, tokenA.getTokenValue(), "GET", ExchangeTestSupport.STOCK, nonces.current());
    assertThat(refusal(factory, tokenA, first)).isNull();

    String other =
        ExchangeTestSupport.proof(
            keyB, tokenB.getTokenValue(), "GET", ExchangeTestSupport.STOCK, nonces.current());
    assertThat(refusal(factory, tokenB, other))
        .isInstanceOfSatisfying(
            DpopProofStoreFullError.class,
            full -> assertThat(full.getRetryAfterSeconds()).isBetween(1L, 31L));
    assertThat(exchange.size()).isEqualTo(1);

    assertThat(refusal(factory, tokenA, first))
        .isNotNull()
        .isNotInstanceOf(DpopProofStoreFullError.class)
        .extracting(OAuth2Error::getErrorCode)
        .isEqualTo("invalid_dpop_proof");
  }

  @Test
  void aPerMemberCapAboveTheTotalIsRefused() {
    assertThatThrownBy(() -> store(10, 5, Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theVerifierCapsTheMemberOfTheTokenAndKeepsTheOtherMembersAndTheExchangeApart()
      throws Exception {
    DpopProofReplayStores stores =
        new DpopProofReplayStores(
            new DpopProofReplayStore(
                MetricNames.PATH_SCOPE_EXCHANGE, 2, 1000, meterRegistry, Clock.systemUTC()),
            new DpopProofReplayStore(
                MetricNames.PATH_SCOPE_OTHER, 2, 1000, meterRegistry, Clock.systemUTC()));
    ExchangeDpopNonces nonces = new ExchangeDpopNonces();
    DPoPProofJwtDecoderFactory factory = ExchangeDpopProofValidation.factory(nonces, stores);
    ECKey keyA = ExchangeTestSupport.newKey();
    ECKey keyB = ExchangeTestSupport.newKey();
    Jwt tokenA = token("token-a", keyA);
    Jwt tokenB = token("token-b", keyB);

    assertThat(verifies(factory, keyA, tokenA, OTHER, null)).isTrue();
    assertThat(verifies(factory, keyA, tokenA, OTHER, null)).isTrue();
    assertThat(verifies(factory, keyA, tokenA, OTHER, null)).isFalse();

    assertThat(verifies(factory, keyB, tokenB, OTHER, null)).isTrue();
    assertThat(verifies(factory, keyA, tokenA, ExchangeTestSupport.STOCK, nonces.current()))
        .isTrue();
    assertThat(refused(MetricNames.PATH_SCOPE_OTHER, MetricNames.DPOP_REPLAY_MEMBER_CAP))
        .isEqualTo(1.0d);
    assertThat(refused(MetricNames.PATH_SCOPE_EXCHANGE, MetricNames.DPOP_REPLAY_MEMBER_CAP))
        .isZero();
  }

  @Test
  void anExchangeProofWithoutTheNonceTakesNoRoomInTheCache() throws Exception {
    DpopProofReplayStore exchange =
        new DpopProofReplayStore(
            MetricNames.PATH_SCOPE_EXCHANGE, 1, 1000, meterRegistry, Clock.systemUTC());
    ExchangeDpopNonces nonces = new ExchangeDpopNonces();
    DPoPProofJwtDecoderFactory factory =
        ExchangeDpopProofValidation.factory(
            nonces,
            new DpopProofReplayStores(
                exchange,
                new DpopProofReplayStore(
                    MetricNames.PATH_SCOPE_OTHER, 1, 1000, meterRegistry, Clock.systemUTC())));
    ECKey key = ExchangeTestSupport.newKey();
    Jwt token = token("token", key);

    assertThat(verifies(factory, key, token, ExchangeTestSupport.STOCK, null)).isFalse();
    assertThat(verifies(factory, key, token, ExchangeTestSupport.STOCK, null)).isFalse();

    assertThat(exchange.size()).isZero();
    assertThat(verifies(factory, key, token, ExchangeTestSupport.STOCK, nonces.current())).isTrue();
  }

  @Test
  void anExchangeProofOverTheMemberCapIsReportedAsTheProofLimitAndAReplayIsNot() throws Exception {
    DpopProofReplayStore exchange =
        new DpopProofReplayStore(
            MetricNames.PATH_SCOPE_EXCHANGE, 1, 1000, meterRegistry, Clock.systemUTC());
    ExchangeDpopNonces nonces = new ExchangeDpopNonces();
    DPoPProofJwtDecoderFactory factory =
        ExchangeDpopProofValidation.factory(
            nonces,
            new DpopProofReplayStores(
                exchange,
                new DpopProofReplayStore(
                    MetricNames.PATH_SCOPE_OTHER, 1, 1000, meterRegistry, Clock.systemUTC())));
    ECKey key = ExchangeTestSupport.newKey();
    Jwt token = token("token", key);
    String first =
        ExchangeTestSupport.proof(
            key, token.getTokenValue(), "GET", ExchangeTestSupport.STOCK, nonces.current());
    assertThat(refusal(factory, token, first)).isNull();

    String second =
        ExchangeTestSupport.proof(
            key, token.getTokenValue(), "GET", ExchangeTestSupport.STOCK, nonces.current());
    assertThat(refusal(factory, token, second))
        .isInstanceOfSatisfying(
            DpopProofLimitError.class,
            limit -> assertThat(limit.getRetryAfterSeconds()).isBetween(1L, 31L));
    assertThat(exchange.size()).isEqualTo(1);

    assertThat(refusal(factory, token, first))
        .isNotNull()
        .isNotInstanceOf(DpopProofLimitError.class)
        .extracting(OAuth2Error::getErrorCode)
        .isEqualTo("invalid_dpop_proof");
  }

  @Test
  void anUnreadableOrPathlessTargetCountsAsAnExchangeRoute() {
    assertThat(ExchangeDpopProofValidation.isExchangeTarget("https://ingest.example/v1/a b"))
        .isTrue();
    assertThat(ExchangeDpopProofValidation.isExchangeTarget("urn:example:opaque")).isTrue();
    assertThat(
            ExchangeDpopProofValidation.isExchange(
                DPoPProofContext.withDPoPProof("x")
                    .method("GET")
                    .targetUri("https://ingest.example")
                    .build()))
        .isTrue();
    assertThat(ExchangeDpopProofValidation.isExchangeTarget("https://ingest.example/exchange"))
        .isTrue();
  }

  @Test
  void onlyAReadablePathOutsideTheExchangeSkipsTheNonce() {
    assertThat(ExchangeDpopProofValidation.isExchangeTarget("https://ingest.example" + OTHER))
        .isFalse();
    assertThat(ExchangeDpopProofValidation.isExchangeTarget("https://ingest.example/exchanged"))
        .isFalse();
  }

  /**
   * Builds a bound access token for a fresh member.
   *
   * @param value the token value
   * @param key the bound key
   * @return the token
   * @throws Exception if hashing the key fails
   */
  private static @NotNull Jwt token(@NotNull String value, @NotNull ECKey key) throws Exception {
    return ExchangeTestSupport.token(
        value,
        "basetool-ingest",
        ExchangeTestSupport.thumbprint(key),
        UUID.randomUUID().toString(),
        "exchange.connect",
        Instant.now().minusSeconds(5));
  }

  /**
   * Decodes an exchange proof for the stock route as the gateway would.
   *
   * @param factory the verifier factory
   * @param token the access token
   * @param proof the signed proof
   * @return the first validation error, or {@code null} when the proof passed
   */
  private static @Nullable OAuth2Error refusal(
      @NotNull DPoPProofJwtDecoderFactory factory, @NotNull Jwt token, @NotNull String proof) {
    DPoPProofContext context =
        DPoPProofContext.withDPoPProof(proof)
            .accessToken(token)
            .method("GET")
            .targetUri(ExchangeTestSupport.ORIGIN + ExchangeTestSupport.STOCK)
            .build();
    try {
      factory.createDecoder(context).decode(proof);
      return null;
    } catch (JwtValidationException refused) {
      return refused.getErrors().iterator().next();
    }
  }

  /**
   * Verifies one freshly signed proof as the gateway would.
   *
   * @param factory the verifier factory
   * @param key the proof key
   * @param token the access token
   * @param path the request path
   * @param nonce the server nonce, or {@code null}
   * @return whether the proof passed
   * @throws Exception if signing fails
   */
  private static boolean verifies(
      @NotNull DPoPProofJwtDecoderFactory factory,
      @NotNull ECKey key,
      @NotNull Jwt token,
      @NotNull String path,
      String nonce)
      throws Exception {
    String proof = ExchangeTestSupport.proof(key, token.getTokenValue(), "GET", path, nonce);
    DPoPProofContext context =
        DPoPProofContext.withDPoPProof(proof)
            .accessToken(token)
            .method("GET")
            .targetUri(ExchangeTestSupport.ORIGIN + path)
            .build();
    try {
      factory.createDecoder(context).decode(proof);
      return true;
    } catch (org.springframework.security.oauth2.jwt.JwtException refused) {
      return false;
    }
  }
}
