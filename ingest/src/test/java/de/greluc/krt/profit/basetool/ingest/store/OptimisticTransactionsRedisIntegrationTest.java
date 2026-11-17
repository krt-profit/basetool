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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import de.greluc.krt.profit.basetool.testsupport.redis.RedisAclTemplate;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The optimistic transaction the gateway's Redis steps run in, against a real Redis under the
 * ingest ACL user and over the gateway's own connection settings with a pool of one connection
 * (ADR-0389): a key changed after the step read it discards the step's writes and runs the step
 * again, a step that never gets through gives up, and any failed attempt closes its connection, so
 * the next step commits on a fresh one.
 */
@Testcontainers
class OptimisticTransactionsRedisIntegrationTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS))
          .withExposedPorts(6379)
          .withCopyToContainer(
              Transferable.of(RedisAclTemplate.renderShared(values())), "/etc/redis/users.acl")
          .withCommand("redis-server", "--aclfile", "/etc/redis/users.acl");

  private static final String COUNTER = "ingest:xch:quota:a:m1:2026-11-16";

  private static final String OTHER = "ingest:xch:quota:a:m2:2026-11-16";

  private ClientResources clientResources;
  private LettuceConnectionFactory ingest;
  private LettuceConnectionFactory admin;
  private StringRedisTemplate template;
  private StringRedisTemplate observer;
  private SimpleMeterRegistry meters;
  private OptimisticTransactions transactions;

  @BeforeEach
  void setUp() {
    admin = adminConnection();
    try (RedisConnection connection = admin.getConnection()) {
      connection.serverCommands().flushAll();
    }
    observer = template(admin);
    clientResources = DefaultClientResources.create();
    ingest = gatewayConnection(clientResources);
    template = template(ingest);
    meters = new SimpleMeterRegistry();
    transactions = new OptimisticTransactions(template, meters);
    observer.opsForValue().set(COUNTER, "1");
    observer.opsForValue().set(OTHER, "1");
  }

  @AfterEach
  void tearDown() {
    ingest.destroy();
    admin.destroy();
    clientResources.shutdown();
  }

  @Test
  void aStepThatPlansNoWriteAnswersOnItsFirstAttempt() {
    AtomicInteger attempts = new AtomicInteger();

    String value =
        transactions.run(
            "test",
            List.of(COUNTER),
            () -> {
              attempts.incrementAndGet();
              return OptimisticTransactions.Plan.read(
                  String.valueOf(template.opsForValue().get(COUNTER)));
            });

    assertThat(value).isEqualTo("1");
    assertThat(attempts).hasValue(1);
  }

  @Test
  void aKeyChangedAfterTheStepReadItDiscardsTheWritesAndTheStepRunsAgain() {
    AtomicInteger attempts = new AtomicInteger();

    long count =
        transactions.run(
            "test",
            List.of(COUNTER),
            () -> {
              Long read = StoredNumbers.parse(template.opsForValue().get(COUNTER));
              assertThat(read).as("the counter holds a number").isNotNull();
              if (attempts.incrementAndGet() == 1) {
                observer.opsForValue().set(COUNTER, "5");
              }
              return OptimisticTransactions.Plan.write(
                  read + 1, () -> template.opsForValue().increment(COUNTER));
            });

    assertThat(attempts).as("the first attempt read a value that changed before EXEC").hasValue(2);
    assertThat(count).isEqualTo(6L);
    assertThat(observer.opsForValue().get(COUNTER)).isEqualTo("6");
    assertThat(conflicts(OptimisticTransactions.OUTCOME_ABORTED)).isEqualTo(1.0d);
    assertThat(conflicts(OptimisticTransactions.OUTCOME_EXHAUSTED)).isZero();
  }

  @Test
  void aStepThatMeetsAChangedKeyOnEveryAttemptGivesUp() {
    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(() ->
            transactions.run(
                "test",
                List.of(COUNTER),
                () -> {
                  observer
                      .opsForValue()
                      .set(COUNTER, Integer.toString(100 + attempts.incrementAndGet()));
                  return OptimisticTransactions.Plan.write(
                      "written", () -> template.opsForValue().set(COUNTER, "step"));
                }))
        .isInstanceOf(OptimisticLockingFailureException.class);

    assertThat(attempts.get()).isBetween(1, OptimisticTransactions.MAX_ATTEMPTS);
    assertThat(observer.opsForValue().get(COUNTER))
        .as("no attempt's write committed")
        .isEqualTo(Integer.toString(100 + attempts.get()));
    assertThat(conflicts(OptimisticTransactions.OUTCOME_ABORTED))
        .isEqualTo((double) attempts.get());
    assertThat(conflicts(OptimisticTransactions.OUTCOME_EXHAUSTED)).isEqualTo(1.0d);
  }

  @Test
  void writesThatAnswerOnlyWithAStatusCommitOnce() {
    AtomicInteger attempts = new AtomicInteger();

    String result =
        transactions.run(
            "test",
            List.of(COUNTER),
            () -> {
              attempts.incrementAndGet();
              return OptimisticTransactions.Plan.write(
                  "written", () -> template.opsForValue().set(COUNTER, "7"));
            });

    assertThat(result).isEqualTo("written");
    assertThat(attempts).as("a committed EXEC whose replies are all OK is no abort").hasValue(1);
    assertThat(observer.opsForValue().get(COUNTER)).isEqualTo("7");
  }

  @Test
  void aStepThatFailsWhileReadingLeavesNoWatchBehind() {
    assertThatThrownBy(() ->
            transactions.<String>run(
                "test",
                List.of(COUNTER),
                () -> {
                  throw new IllegalStateException("the read failed");
                }))
        .isInstanceOf(IllegalStateException.class);

    assertTheNextStepCommitsAtOnceAfter(COUNTER);
  }

  @Test
  void aStepThatFailsWhileQueueingLeavesNoTransactionBehind() {
    assertThatThrownBy(() ->
            transactions.run(
                "test",
                List.of(COUNTER),
                () ->
                    OptimisticTransactions.Plan.write(
                        "written",
                        () -> {
                          template.opsForValue().increment(COUNTER);
                          throw new IllegalStateException("the queueing failed");
                        })))
        .isInstanceOf(IllegalStateException.class);

    assertThat(observer.opsForValue().get(COUNTER))
        .as("the queued increment was discarded")
        .isEqualTo("1");
    assertTheNextStepCommitsAtOnceAfter(COUNTER);
  }

  @Test
  void aMultiThatReachedRedisButFailedLeavesNoTransactionBehind() {
    StringRedisTemplate failingMulti = new FailingMultiTemplate(ingest);
    OptimisticTransactions failing = new OptimisticTransactions(failingMulti, meters);

    assertThatThrownBy(() ->
            failing.run(
                "test",
                List.of(COUNTER),
                () ->
                    OptimisticTransactions.Plan.write(
                        "written", () -> failingMulti.opsForValue().increment(COUNTER))))
        .isInstanceOf(QueryTimeoutException.class);

    assertThat(observer.opsForValue().get(COUNTER)).isEqualTo("1");
    assertTheNextStepCommitsAtOnceAfter(COUNTER);
  }

  @Test
  void anErrorInsideAStepClosesItsConnectionToo() {
    assertThatThrownBy(() ->
            transactions.<String>run(
                "test",
                List.of(COUNTER),
                () -> {
                  throw new AssertionError("the step broke");
                }))
        .isInstanceOf(AssertionError.class);

    assertTheNextStepCommitsAtOnceAfter(COUNTER);
  }

  @Test
  void aConnectionKilledBetweenTwoStepsIsReplacedNotReused() throws Exception {
    assertTheNextStepCommitsAtOnceAfter(OTHER);
    org.testcontainers.containers.Container.ExecResult killed =
        REDIS.execInContainer(
            "redis-cli",
            "--no-auth-warning",
            "--user",
            RedisAclTemplate.ADMIN_USER,
            "--pass",
            RedisAclTemplate.E2E_PASSWORDS.get("REDIS_PASSWORD"),
            "CLIENT",
            "KILL",
            "USER",
            RedisAclTemplate.INGEST_USER);
    assertThat(killed.getStdout().strip()).as("connections killed").isEqualTo("1");

    awaitDisconnectSeenByTheClient();
    assertTheNextStepCommitsAtOnceAfter(COUNTER);
  }

  /**
   * Waits until the client has seen the killed connection close, which Lettuce learns from the
   * socket a moment after Redis closed it; a borrow before that would still be lent the dead
   * connection and fail, not replay.
   */
  private void awaitDisconnectSeenByTheClient() {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      try (RedisConnection probe = ingest.getConnection()) {
        probe.ping();
        return;
      } catch (org.springframework.dao.DataAccessException expected) {
        Thread.onSpinWait();
      }
    }
    throw new AssertionError("the gateway's pool never lent a live connection again");
  }

  /**
   * Changes a key an earlier step watched, then runs a step on another key over the one pooled
   * connection, which must commit on its first attempt: a watch, a transaction or an unanswered
   * command left on the connection would abort or refuse it.
   *
   * @param watchedBefore the key the earlier step watched
   */
  private void assertTheNextStepCommitsAtOnceAfter(@NotNull String watchedBefore) {
    observer.opsForValue().set(watchedBefore, "9");
    AtomicInteger attempts = new AtomicInteger();
    String expected = Long.toString(Long.parseLong(observer.opsForValue().get(OTHER)) + 1);

    String result =
        transactions.run(
            "test",
            List.of(OTHER),
            () -> {
              attempts.incrementAndGet();
              return OptimisticTransactions.Plan.write(
                  "written", () -> template.opsForValue().increment(OTHER));
            });

    assertThat(result).isEqualTo("written");
    assertThat(attempts).hasValue(1);
    assertThat(observer.opsForValue().get(OTHER)).isEqualTo(expected);
  }

  /**
   * Returns a conflict count of the test step.
   *
   * @param outcome the outcome tag
   * @return the count, zero when nothing was counted
   */
  private double conflicts(@NotNull String outcome) {
    Counter counter =
        meters
            .find(MetricNames.EXCHANGE_STORE_CONFLICTS)
            .tag(MetricNames.TAG_STEP, "test")
            .tag(MetricNames.TAG_OUTCOME, outcome)
            .counter();
    return counter == null ? 0.0d : counter.count();
  }

  /**
   * Connects as the gateway does, through {@link RedisClientConfig}, with one pooled connection.
   *
   * @param resources the Lettuce client resources
   * @return the started factory
   */
  private static @NotNull LettuceConnectionFactory gatewayConnection(
      @NotNull ClientResources resources) {
    DataRedisProperties redis = new DataRedisProperties();
    redis.setHost(REDIS.getHost());
    redis.setPort(REDIS.getMappedPort(6379));
    redis.setUsername(RedisAclTemplate.INGEST_USER);
    redis.setPassword(RedisAclTemplate.E2E_PASSWORDS.get("REDIS_INGEST_PASSWORD"));
    redis.getLettuce().getPool().setMaxActive(1);
    LettuceConnectionFactory factory = RedisClientConfig.connectionFactory(redis, resources);
    factory.afterPropertiesSet();
    factory.start();
    return factory;
  }

  /**
   * Connects as the operator's {@code admin} user, for what the test observes.
   *
   * @return the started factory
   */
  private static @NotNull LettuceConnectionFactory adminConnection() {
    RedisStandaloneConfiguration config =
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
    config.setUsername(RedisAclTemplate.ADMIN_USER);
    config.setPassword(RedisAclTemplate.E2E_PASSWORDS.get("REDIS_PASSWORD"));
    LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
    factory.afterPropertiesSet();
    factory.start();
    return factory;
  }

  /**
   * Builds a string template over a factory.
   *
   * @param factory the connection factory
   * @return the template
   */
  private static @NotNull StringRedisTemplate template(@NotNull LettuceConnectionFactory factory) {
    StringRedisTemplate template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    return template;
  }

  /**
   * Returns the E2E passwords the ACL is rendered with.
   *
   * @return the values
   */
  private static @NotNull Map<String, String> values() {
    Map<String, String> values = new HashMap<>(RedisAclTemplate.E2E_PASSWORDS);
    values.put("REDIS_DEFAULT_USER", "off");
    return values;
  }

  /**
   * A template whose {@code MULTI} reaches Redis and then fails on the client, as a {@code MULTI}
   * whose reply timed out does.
   */
  private static final class FailingMultiTemplate extends StringRedisTemplate {

    /**
     * Creates the template over a factory.
     *
     * @param factory the connection factory
     */
    FailingMultiTemplate(@NotNull LettuceConnectionFactory factory) {
      super(factory);
    }

    /**
     * Sends {@code MULTI} and then fails.
     *
     * @throws QueryTimeoutException always, after Redis opened the transaction
     */
    @Override
    public void multi() {
      super.multi();
      throw new QueryTimeoutException("the MULTI reply timed out");
    }
  }
}
