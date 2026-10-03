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

package de.greluc.krt.profit.basetool.ingest.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.ingest.config.ExchangeStoreProperties;
import de.greluc.krt.profit.basetool.ingest.gate.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.idempotency.ExchangeIdempotencyFilter;
import de.greluc.krt.profit.basetool.ingest.observability.ExchangeRefusals;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistry;
import de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport;
import de.greluc.krt.profit.basetool.ingest.support.TestLoggingProperties;
import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import de.greluc.krt.profit.basetool.testsupport.redis.RedisAclTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * The byte budget and the idempotency cache against a real Redis under the ingest ACL user
 * (REQ-XCH-020, REQ-XCH-023): filling one member's budget and then one client's leaves every other
 * member and client working, parallel writes never overshoot a budget, a duplicate that raced the
 * first request replays its answer, and a lock is released only by the request holding it.
 */
@Testcontainers
class ExchangeStoreRedisIntegrationTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS))
          .withExposedPorts(6379)
          .withCopyToContainer(
              Transferable.of(RedisAclTemplate.render(values())), "/etc/redis/users.acl")
          .withCommand("redis-server", "--aclfile", "/etc/redis/users.acl");

  private static final ExchangeStoreProperties SMALL =
      new ExchangeStoreProperties(
          4096L, 8192L, 12288L, 1024, Duration.ofHours(24), Duration.ofMinutes(2), 1024L, 10);

  private static final ExchangeStoreProperties DEFAULTS =
      new ExchangeStoreProperties(
          1_048_576L,
          16_777_216L,
          67_108_864L,
          32768,
          Duration.ofHours(24),
          Duration.ofMinutes(2),
          524_288L,
          10);

  private static final int THREADS = 16;

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-09-27T12:00:00Z"));
  private LettuceConnectionFactory ingest;
  private LettuceConnectionFactory admin;
  private StringRedisTemplate template;
  private StringRedisTemplate observer;
  private Clock clock;
  private ExchangeBudget budget;
  private ExchangeIdempotency idempotency;

  @BeforeEach
  void setUp() {
    admin = connect(RedisAclTemplate.ADMIN_USER, "REDIS_PASSWORD");
    try (RedisConnection connection = admin.getConnection()) {
      connection.serverCommands().flushAll();
    }
    observer = new StringRedisTemplate(admin);
    observer.afterPropertiesSet();
    ingest = connect(RedisAclTemplate.INGEST_USER, "REDIS_INGEST_PASSWORD");
    template = new StringRedisTemplate(ingest);
    template.afterPropertiesSet();
    clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneId.of("UTC");
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    budget = new ExchangeBudget(template, SMALL, new SimpleMeterRegistry(), clock);
    idempotency = new ExchangeIdempotency(template, JsonMapper.builder().build(), SMALL);
  }

  @AfterEach
  void tearDown() {
    ingest.destroy();
    admin.destroy();
  }

  @Test
  void aFullMemberBudgetStopsOnlyThatMember() {
    budget.record("a", "m1", "ingest:xch:idem:a:m1:1", 3000L, Duration.ofHours(1));

    assertThat(budget.reserve("a", "m1", "ingest:xch:idem:a:m1:2", 100L, Duration.ofHours(1)))
        .isFalse();
    assertThat(budget.reserve("a", "m2", "ingest:xch:idem:a:m2:1", 100L, Duration.ofHours(1)))
        .isTrue();
    assertThat(budget.reserve("b", "m1", "ingest:xch:idem:b:m1:1", 100L, Duration.ofHours(1)))
        .isTrue();
    assertThat(total(ExchangeBudget.memberScope("a", "m1"))).isEqualTo(3512L);
  }

  @Test
  void aFullClientBudgetStopsOnlyThatClient() {
    budget.record("a", "m1", "ingest:xch:idem:a:m1:1", 3500L, Duration.ofHours(1));
    budget.record("a", "m2", "ingest:xch:idem:a:m2:1", 3500L, Duration.ofHours(1));

    assertThat(budget.reserve("a", "m3", "ingest:xch:idem:a:m3:1", 100L, Duration.ofHours(1)))
        .isFalse();
    assertThat(budget.reserve("b", "m1", "ingest:xch:idem:b:m1:1", 100L, Duration.ofHours(1)))
        .isTrue();
    assertThat(total(ExchangeBudget.clientScope("a"))).isEqualTo(8024L);
    assertThat(total(ExchangeBudget.totalScope())).isEqualTo(8636L);
  }

  @Test
  void expiredEntriesFreeTheirBytes() {
    budget.record("a", "m1", "ingest:xch:idem:a:m1:1", 3000L, Duration.ofMinutes(5));
    assertThat(budget.reserve("a", "m1", "ingest:xch:idem:a:m1:2", 100L, Duration.ofHours(1)))
        .isFalse();

    now.set(now.get().plus(Duration.ofMinutes(6)));

    assertThat(budget.reserve("a", "m1", "ingest:xch:idem:a:m1:2", 100L, Duration.ofHours(1)))
        .isTrue();
    assertThat(total(ExchangeBudget.memberScope("a", "m1"))).isEqualTo(612L);
    assertThat(template.opsForZSet().range(ExchangeBudget.memberScope("a", "m1"), 0, -1))
        .containsExactly(ExchangeBudget.entry("ingest:xch:idem:a:m1:2", 100L));
  }

  @Test
  void aReservationSettlesOnItsValueAndAReleaseFreesIt() {
    assertThat(
            budget.reserve("a", "m1", "ingest:xch:idem-lock:a:m1:x", 2000L, Duration.ofMinutes(2)))
        .isTrue();
    assertThat(
            budget.settle(
                "a",
                "m1",
                "ingest:xch:idem-lock:a:m1:x",
                2000L,
                "ingest:xch:idem:a:m1:x",
                1000L,
                Duration.ofHours(24)))
        .isTrue();

    assertThat(total(ExchangeBudget.memberScope("a", "m1"))).isEqualTo(1512L);
    assertThat(template.opsForZSet().range(ExchangeBudget.memberScope("a", "m1"), 0, -1))
        .containsExactly(ExchangeBudget.entry("ingest:xch:idem:a:m1:x", 1000L));

    budget.release("a", "m1", "ingest:xch:idem:a:m1:x", 1000L);

    assertThat(observer.hasKey(ExchangeBudget.memberScope("a", "m1"))).isFalse();
    assertThat(observer.hasKey(ExchangeBudget.sum(ExchangeBudget.memberScope("a", "m1"))))
        .isFalse();
  }

  @Test
  void aValueThatDoesNotFitLeavesItsReservationInPlace() {
    budget.reserve("a", "m1", "ingest:xch:pending:1", 1000L, Duration.ofMinutes(30));

    assertThat(
            budget.settle(
                "a",
                "m1",
                "ingest:xch:pending:1",
                1000L,
                "ingest:handoff:m1:h",
                4000L,
                Duration.ofMinutes(30)))
        .isFalse();
    assertThat(total(ExchangeBudget.memberScope("a", "m1"))).isEqualTo(1512L);
    assertThat(template.opsForZSet().range(ExchangeBudget.memberScope("a", "m1"), 0, -1))
        .containsExactly(ExchangeBudget.entry("ingest:xch:pending:1", 1000L));
  }

  @Test
  void aQuotaCounterIsBornWithItsExpiryAndCountedOnceInTheBudget() {
    ExchangeQuotas quotas = new ExchangeQuotas(template, budget, clock);
    String key = ExchangeQuotas.PREFIX + "a:m1:2026-09-27";

    assertThat(quotas.countWrite("a", "m1").count()).isEqualTo(1L);
    assertThat(quotas.countWrite("a", "m1").count()).isEqualTo(2L);

    assertThat(observer.getExpire(key, TimeUnit.SECONDS))
        .as("until the end of the following UTC day")
        .isBetween(Duration.ofHours(36).toSeconds() - 5L, Duration.ofHours(36).toSeconds());
    assertThat(total(ExchangeBudget.memberScope("a", "m1")))
        .isEqualTo(ExchangeBudget.charge(key.length() + (long) ExchangeQuotas.VALUE_BYTES));
    assertThat(template.opsForZSet().range(ExchangeBudget.memberScope("a", "m1"), 0, -1))
        .hasSize(1);
  }

  @Test
  void aMissingRunningTotalIsRebuiltFromItsSet() {
    budget.record("a", "m1", "ingest:xch:idem:a:m1:1", 3000L, Duration.ofHours(1));
    template.delete(ExchangeBudget.sum(ExchangeBudget.memberScope("a", "m1")));

    assertThat(budget.reserve("a", "m1", "ingest:xch:idem:a:m1:2", 100L, Duration.ofHours(1)))
        .as("the rebuilt total still holds the first entry")
        .isFalse();
    assertThat(total(ExchangeBudget.memberScope("a", "m1"))).isEqualTo(3512L);
  }

  @Test
  void theBudgetKeysExpireWithTheirLongestEntry() {
    budget.record("a", "m1", "ingest:xch:quota:a:m1:1", 20L, Duration.ofDays(5));
    budget.record("a", "m1", "ingest:xch:idem:a:m1:1", 20L, Duration.ofHours(1));

    Long expiry = observer.getExpire(ExchangeBudget.memberScope("a", "m1"), TimeUnit.SECONDS);
    Long totalExpiry =
        observer.getExpire(
            ExchangeBudget.sum(ExchangeBudget.memberScope("a", "m1")), TimeUnit.SECONDS);
    assertThat(expiry).isGreaterThan(Duration.ofDays(4).toSeconds());
    assertThat(totalExpiry).isGreaterThan(Duration.ofDays(4).toSeconds());
  }

  @Test
  void parallelReservationsNeverOvershootAMembersBudget() throws Exception {
    List<Boolean> admitted =
        parallel(
            index ->
                budget.reserve(
                    "a", "m1", "ingest:xch:pending:" + index, 500L, Duration.ofMinutes(5)));

    assertThat(admitted.stream().filter(Boolean::booleanValue).count())
        .as("4096 bytes hold four entries of 1012")
        .isEqualTo(4L);
    assertThat(total(ExchangeBudget.memberScope("a", "m1"))).isEqualTo(4048L);
    assertThat(total(ExchangeBudget.clientScope("a"))).isEqualTo(4048L);
    assertThat(total(ExchangeBudget.totalScope())).isEqualTo(4048L);
  }

  @Test
  void parallelReservationsOfManyMembersNeverOvershootTheClientsOrTheTotalBudget()
      throws Exception {
    List<Boolean> admitted =
        parallel(
            index ->
                budget.reserve(
                    index % 2 == 0 ? "a" : "b",
                    "m" + index,
                    "ingest:xch:pending:" + index,
                    1500L,
                    Duration.ofMinutes(5)));

    assertThat(admitted.stream().filter(Boolean::booleanValue).count())
        .as("12288 bytes hold six entries of 2012, at most four per 8192-byte client")
        .isEqualTo(6L);
    assertThat(total(ExchangeBudget.clientScope("a"))).isLessThanOrEqualTo(8192L);
    assertThat(total(ExchangeBudget.clientScope("b"))).isLessThanOrEqualTo(8192L);
    assertThat(total(ExchangeBudget.totalScope())).isEqualTo(12072L);
  }

  @Test
  void theCacheReplaysAndTheLockHoldsOneWriterUnderTheIngestUser() {
    String namespace = ExchangeIdempotency.namespace("a", "m1", "key-000001");

    assertThat(idempotency.find(namespace)).isEmpty();
    String token = idempotency.claim(namespace).orElseThrow();
    assertThat(idempotency.claim(namespace)).isEmpty();

    ExchangeIdempotency.Stored stored =
        new ExchangeIdempotency.Stored("fp", 200, "application/json", "{\"ok\":1}");
    idempotency.store(namespace, stored);
    assertThat(idempotency.releaseClaim(namespace, token)).isTrue();

    assertThat(idempotency.sizeOf(namespace, stored)).isPositive();
    assertThat(idempotency.find(namespace))
        .hasValueSatisfying(
            found -> {
              assertThat(found.fingerprint()).isEqualTo("fp");
              assertThat(found.status()).isEqualTo(200);
              assertThat(found.body()).isEqualTo("{\"ok\":1}");
            });
    assertThat(idempotency.claim(namespace)).isPresent();
    assertThat(observer.getExpire(ExchangeIdempotency.PREFIX + namespace)).isPositive();
  }

  @Test
  void aLockIsReleasedOnlyByTheRequestHoldingIt() {
    String namespace = ExchangeIdempotency.namespace("a", "m1", "key-000002");
    String first = idempotency.claim(namespace).orElseThrow();

    assertThat(idempotency.releaseClaim(namespace, "not-the-token")).isFalse();
    assertThat(idempotency.claim(namespace)).isEmpty();

    template.delete(ExchangeIdempotency.CLAIM_PREFIX + namespace);
    String second = idempotency.claim(namespace).orElseThrow();

    assertThat(idempotency.releaseClaim(namespace, first))
        .as("a request that outlived its lock must not free the next holder's lock")
        .isFalse();
    assertThat(idempotency.claim(namespace)).isEmpty();
    assertThat(idempotency.releaseClaim(namespace, second)).isTrue();
    assertThat(idempotency.claim(namespace)).isPresent();
  }

  @Test
  void aDuplicateThatLookedBeforeTheFirstAnswerWasStoredReplaysItInsteadOfWritingAgain()
      throws Exception {
    CountDownLatch looked = new CountDownLatch(1);
    CountDownLatch firstDone = new CountDownLatch(1);
    AtomicBoolean pauseNextLookup = new AtomicBoolean(true);
    ExchangeIdempotency cache =
        new ExchangeIdempotency(template, JsonMapper.builder().build(), DEFAULTS) {
          @Override
          public @NotNull Optional<Stored> find(@NotNull String namespace) {
            Optional<Stored> found = super.find(namespace);
            if (pauseNextLookup.compareAndSet(true, false)) {
              looked.countDown();
              try {
                assertThat(firstDone.await(30, TimeUnit.SECONDS)).isTrue();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            return found;
          }
        };
    ExchangeIdempotencyFilter filter = filter(cache);
    AtomicInteger writes = new AtomicInteger();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<MockHttpServletResponse> second =
          pool.submit(() -> send(filter, "late-key-1", writes));
      assertThat(looked.await(30, TimeUnit.SECONDS)).isTrue();

      MockHttpServletResponse first = send(filter, "late-key-1", writes);
      firstDone.countDown();
      MockHttpServletResponse replayed = second.get(30, TimeUnit.SECONDS);

      assertThat(first.getStatus()).isEqualTo(200);
      assertThat(writes.get()).as("the late duplicate must not run the write again").isEqualTo(1);
      assertThat(replayed.getStatus()).isEqualTo(200);
      assertThat(replayed.getHeader(ExchangeIdempotencyFilter.REPLAYED)).isEqualTo("true");
      assertThat(replayed.getContentAsString()).isEqualTo("{\"written\":true}");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void parallelDuplicatesOfOneKeyRunTheWriteOnceAndReplayIt() throws Exception {
    ExchangeIdempotencyFilter filter =
        filter(new ExchangeIdempotency(template, JsonMapper.builder().build(), DEFAULTS));

    for (int round = 0; round < 20; round++) {
      String key = "race-key-" + round;
      AtomicInteger writes = new AtomicInteger();
      List<MockHttpServletResponse> responses = parallel(index -> send(filter, key, writes));

      assertThat(writes.get()).as("round %d ran the write once", round).isEqualTo(1);
      for (MockHttpServletResponse response : responses) {
        if (response.getStatus() == 200) {
          assertThat(response.getContentAsString()).isEqualTo("{\"written\":true}");
        } else {
          assertThat(response.getStatus()).isEqualTo(409);
          assertThat(response.getContentAsString()).contains("IDEMPOTENCY_IN_PROGRESS");
        }
      }
      assertThat(
              observer.hasKey(
                  ExchangeIdempotency.CLAIM_PREFIX + ExchangeIdempotency.namespace("a", "m1", key)))
          .isFalse();
    }
    assertThat(total(ExchangeBudget.memberScope("a", "m1")))
        .as("only the twenty cached answers stay counted, no reservation")
        .isEqualTo(
            template.opsForZSet().range(ExchangeBudget.memberScope("a", "m1"), 0, -1).stream()
                .filter(entry -> entry.startsWith(ExchangeIdempotency.PREFIX))
                .mapToLong(entry -> Long.parseLong(entry.substring(entry.lastIndexOf('|') + 1)))
                .sum());
    assertThat(template.opsForZSet().range(ExchangeBudget.memberScope("a", "m1"), 0, -1))
        .hasSize(20);
  }

  @Test
  void aRefundTakesOneWriteBackAndKeepsTheCountersExpiry() {
    ExchangeQuotas quotas = new ExchangeQuotas(template, budget, clock);
    String key = quotas.countWrite("a", "m1").key();
    quotas.countWrite("a", "m1");
    long ttl = observer.getExpire(key, TimeUnit.SECONDS);

    quotas.refund(key);

    assertThat(template.opsForValue().get(key)).isEqualTo("1");
    assertThat(observer.getExpire(key, TimeUnit.SECONDS)).isBetween(ttl - 2L, ttl);
    quotas.refund(key);
    quotas.refund(key);
    assertThat(template.opsForValue().get(key)).as("never below zero").isEqualTo("0");
    quotas.refund(ExchangeQuotas.PREFIX + "a:m1:2026-01-01");
    assertThat(observer.hasKey(ExchangeQuotas.PREFIX + "a:m1:2026-01-01"))
        .as("a refund creates no counter")
        .isFalse();
  }

  @Test
  void aBudgetRefusalGivesTheQuotaCountBackAndWaitsUntilEnoughExpires() throws Exception {
    ExchangeBudget defaults =
        new ExchangeBudget(template, DEFAULTS, new SimpleMeterRegistry(), clock);
    ExchangeQuotas quotas = new ExchangeQuotas(template, defaults, clock);
    String counter = quotas.countWrite("a", "m1").key();
    defaults.record(
        "a", "m1", "ingest:xch:idem:a:m1:old", 1_048_576L - 20_000L, Duration.ofMinutes(10));
    AtomicInteger writes = new AtomicInteger();

    MockHttpServletResponse response =
        send(
            filter(new ExchangeIdempotency(template, JsonMapper.builder().build(), DEFAULTS)),
            "budget-key-1",
            writes,
            counter);

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).contains("EXCHANGE_BUDGET_EXHAUSTED");
    assertThat(writes).hasValue(0);
    assertThat(template.opsForValue().get(counter))
        .as("the refused write is not counted")
        .isEqualTo("0");
    assertThat(response.getHeader("Retry-After"))
        .as("the member's budget frees when the old answer expires")
        .isEqualTo("600");
  }

  @Test
  void theWaitIsWhenEnoughOfTheOverflowingScopeHasExpired() {
    budget.record("a", "m1", "ingest:xch:idem:a:m1:1", 1000L, Duration.ofMinutes(5));
    budget.record("a", "m1", "ingest:xch:idem:a:m1:2", 1000L, Duration.ofMinutes(10));
    budget.record("a", "m1", "ingest:xch:idem:a:m1:3", 1000L, Duration.ofMinutes(20));

    assertThat(budget.reserve("a", "m1", "ingest:xch:idem:a:m1:4", 100L, Duration.ofHours(1)))
        .isFalse();
    assertThat(budget.retryAfterSeconds("a", "m1", 100L)).isEqualTo(300L);
    assertThat(budget.retryAfterSeconds("a", "m1", 2000L)).isEqualTo(600L);
    assertThat(budget.retryAfterSeconds("a", "m2", 100L))
        .as("another member fits now")
        .isEqualTo(1L);
    assertThat(budget.retryAfterSeconds("a", "m1", 5000L))
        .as("larger than the member's whole budget")
        .isEqualTo(ExchangeBudget.MAX_RETRY_AFTER_SECONDS);
  }

  @Test
  void eachClientsUseIsPublishedAgainstTheClientBudget() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExchangeBudget measured = new ExchangeBudget(template, SMALL, meters, clock);

    measured.record("a", "m1", "ingest:xch:idem:a:m1:1", 1536L, Duration.ofHours(1));
    measured.record("b", "m1", "ingest:xch:idem:b:m1:1", 512L, Duration.ofHours(1));

    assertThat(
            meters
                .get("basetool.ingest.exchange.client.budget.used.ratio")
                .tag("client_id", "a")
                .gauge()
                .value())
        .isEqualTo(2048.0d / SMALL.clientBytes());
    assertThat(
            meters
                .get("basetool.ingest.exchange.client.budget.used.ratio")
                .tag("client_id", "b")
                .gauge()
                .value())
        .isEqualTo(1024.0d / SMALL.clientBytes());
  }

  /**
   * Builds the idempotency filter over a cache, with the production default budgets.
   *
   * @param cache the idempotency cache
   * @return the filter
   */
  private @NotNull ExchangeIdempotencyFilter filter(@NotNull ExchangeIdempotency cache) {
    ExchangeBudget defaults =
        new ExchangeBudget(template, DEFAULTS, new SimpleMeterRegistry(), clock);
    return new ExchangeIdempotencyFilter(
        cache,
        defaults,
        new ExchangeQuotas(template, defaults, clock),
        DEFAULTS,
        mock(ExchangeRefusals.class),
        JsonMapper.builder().build(),
        TestLoggingProperties.defaults(),
        new SimpleMeterRegistry());
  }

  /**
   * Sends one write through the filter, whose chain counts the writes it runs.
   *
   * @param filter the filter
   * @param key the idempotency key
   * @param writes counts the writes that reached the chain
   * @return the response
   * @throws Exception if the filter fails
   */
  private static @NotNull MockHttpServletResponse send(
      @NotNull ExchangeIdempotencyFilter filter, @NotNull String key, @NotNull AtomicInteger writes)
      throws Exception {
    return send(filter, key, writes, null);
  }

  /**
   * Sends one write through the filter as the limit filter left it: counted on a quota counter.
   *
   * @param filter the filter
   * @param key the idempotency key
   * @param writes counts the writes that reached the chain
   * @param counted the quota counter the write was counted on, or {@code null} for none
   * @return the response
   * @throws Exception if the filter fails
   */
  private static @NotNull MockHttpServletResponse send(
      @NotNull ExchangeIdempotencyFilter filter,
      @NotNull String key,
      @NotNull AtomicInteger writes,
      @Nullable String counted)
      throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", ExchangeTestSupport.BLUEPRINT_CHANGES);
    request.setContent("{\"ops\":[]}".getBytes(StandardCharsets.UTF_8));
    request.setContentType("application/json");
    request.addHeader(ExchangeIdempotencyFilter.IDEMPOTENCY_KEY, key);
    request.setAttribute(
        ExchangeRequestContext.ATTRIBUTE,
        new ExchangeRequestContext(
            "a",
            "m1",
            "thumbprint",
            Set.of("exchange.blueprints.write"),
            new ExchangeRegistry.Client("A", true, Set.of(), null, null, null),
            null));
    if (counted != null) {
      request.setAttribute(ExchangeQuotas.COUNTED, counted);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain =
        (req, res) -> {
          writes.incrementAndGet();
          res.setContentType("application/json");
          res.getOutputStream().write("{\"written\":true}".getBytes(StandardCharsets.UTF_8));
        };
    filter.doFilter(request, response, chain);
    return response;
  }

  /**
   * Returns a scope's running total.
   *
   * @param scope the budget set
   * @return the bytes it counts, zero when it holds nothing
   */
  private long total(@NotNull String scope) {
    String value = template.opsForValue().get(ExchangeBudget.sum(scope));
    return value == null ? 0L : Long.parseLong(value);
  }

  /**
   * Runs one task on {@link #THREADS} threads released at the same moment.
   *
   * @param task the task, given the thread's index
   * @param <T> the result type
   * @return the results in thread order
   * @throws Exception if a task fails
   */
  private static <T> @NotNull List<T> parallel(@NotNull Indexed<T> task) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < THREADS; i++) {
        int index = i;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return task.run(index);
                }));
      }
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * A task that knows which of the parallel threads runs it.
   *
   * @param <T> the result type
   */
  @FunctionalInterface
  private interface Indexed<T> {

    /**
     * Runs the task.
     *
     * @param index the thread's index
     * @return the result
     * @throws Exception if the task fails
     */
    T run(int index) throws Exception;
  }

  /**
   * Connects as one ACL user.
   *
   * @param user the user
   * @param passwordVariable the variable naming its E2E password
   * @return the started factory
   */
  private static LettuceConnectionFactory connect(String user, String passwordVariable) {
    RedisStandaloneConfiguration config =
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
    config.setUsername(user);
    config.setPassword(RedisAclTemplate.E2E_PASSWORDS.get(passwordVariable));
    LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
    factory.afterPropertiesSet();
    factory.start();
    return factory;
  }

  /**
   * Returns the E2E passwords the ACL is rendered with.
   *
   * @return the values
   */
  private static Map<String, String> values() {
    Map<String, String> values = new HashMap<>(RedisAclTemplate.E2E_PASSWORDS);
    values.put("REDIS_DEFAULT_USER", "off");
    return values;
  }
}
