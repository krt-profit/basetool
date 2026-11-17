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

import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import de.greluc.krt.profit.basetool.testsupport.redis.RedisAclTemplate;
import io.lettuce.core.api.StatefulConnection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The optimistic transaction the gateway's Redis steps run in, against a real Redis under the
 * ingest ACL user and over a pool with one connection for transactions (ADR-0389): a key changed
 * after the step read it discards the step's writes and runs the step again, a step that never gets
 * through gives up, and a failed step leaves the pooled connection without a watch or an open
 * transaction.
 */
@Testcontainers
class OptimisticTransactionRedisIntegrationTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS))
          .withExposedPorts(6379)
          .withCopyToContainer(
              Transferable.of(RedisAclTemplate.render(values())), "/etc/redis/users.acl")
          .withCommand("redis-server", "--aclfile", "/etc/redis/users.acl");

  private static final String COUNTER = "ingest:xch:quota:a:m1:2026-11-16";

  private static final String OTHER = "ingest:xch:quota:a:m2:2026-11-16";

  /**
   * The pooled connections left for transactions; the factory's shared connection holds one more
   * from the same pool for as long as the factory runs.
   */
  private static final int TRANSACTION_CONNECTIONS = 1;

  private LettuceConnectionFactory ingest;
  private LettuceConnectionFactory admin;
  private StringRedisTemplate template;
  private StringRedisTemplate observer;

  @BeforeEach
  void setUp() {
    admin = connect(RedisAclTemplate.ADMIN_USER, "REDIS_PASSWORD", false);
    try (RedisConnection connection = admin.getConnection()) {
      connection.serverCommands().flushAll();
    }
    observer = template(admin);
    ingest = connect(RedisAclTemplate.INGEST_USER, "REDIS_INGEST_PASSWORD", true);
    template = template(ingest);
    observer.opsForValue().set(COUNTER, "1");
    observer.opsForValue().set(OTHER, "1");
  }

  @AfterEach
  void tearDown() {
    ingest.destroy();
    admin.destroy();
  }

  @Test
  void aStepThatPlansNoWriteAnswersOnItsFirstAttempt() {
    AtomicInteger attempts = new AtomicInteger();

    String value =
        OptimisticTransaction.run(
            template,
            List.of(COUNTER),
            () -> {
              attempts.incrementAndGet();
              return OptimisticTransaction.Plan.read(
                  String.valueOf(template.opsForValue().get(COUNTER)));
            });

    assertThat(value).isEqualTo("1");
    assertThat(attempts).hasValue(1);
  }

  @Test
  void aKeyChangedAfterTheStepReadItDiscardsTheWritesAndTheStepRunsAgain() {
    AtomicInteger attempts = new AtomicInteger();

    long count =
        OptimisticTransaction.run(
            template,
            List.of(COUNTER),
            () -> {
              Long read = StoredNumbers.parse(template.opsForValue().get(COUNTER));
              assertThat(read).as("the counter holds a number").isNotNull();
              if (attempts.incrementAndGet() == 1) {
                observer.opsForValue().set(COUNTER, "5");
              }
              return OptimisticTransaction.Plan.write(
                  read + 1, () -> template.opsForValue().increment(COUNTER));
            });

    assertThat(attempts).as("the first attempt read a value that changed before EXEC").hasValue(2);
    assertThat(count).isEqualTo(6L);
    assertThat(observer.opsForValue().get(COUNTER)).isEqualTo("6");
  }

  @Test
  void aStepThatMeetsAChangedKeyOnEveryAttemptGivesUp() {
    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(() ->
            OptimisticTransaction.run(
                template,
                List.of(COUNTER),
                () -> {
                  observer
                      .opsForValue()
                      .set(COUNTER, Integer.toString(100 + attempts.incrementAndGet()));
                  return OptimisticTransaction.Plan.write(
                      "written", () -> template.opsForValue().set(COUNTER, "step"));
                }))
        .isInstanceOf(OptimisticLockingFailureException.class);

    assertThat(attempts).hasValue(OptimisticTransaction.MAX_ATTEMPTS);
    assertThat(observer.opsForValue().get(COUNTER))
        .as("no attempt's write committed")
        .isEqualTo(Integer.toString(100 + OptimisticTransaction.MAX_ATTEMPTS));
  }

  @Test
  void aStepThatFailsWhileReadingLeavesNoWatchBehind() {
    assertThatThrownBy(() ->
            OptimisticTransaction.<String>run(
                template,
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
            OptimisticTransaction.run(
                template,
                List.of(COUNTER),
                () ->
                    OptimisticTransaction.Plan.write(
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

  /**
   * Changes a key an earlier step watched, then runs a step on another key over the same pooled
   * connection, which must commit on its first attempt: a watch or a transaction left on the
   * connection would abort or refuse it.
   *
   * @param watchedBefore the key the failed step watched
   */
  private void assertTheNextStepCommitsAtOnceAfter(@NotNull String watchedBefore) {
    observer.opsForValue().set(watchedBefore, "9");
    AtomicInteger attempts = new AtomicInteger();

    String result =
        OptimisticTransaction.run(
            template,
            List.of(OTHER),
            () -> {
              attempts.incrementAndGet();
              return OptimisticTransaction.Plan.write(
                  "written", () -> template.opsForValue().increment(OTHER));
            });

    assertThat(result).isEqualTo("written");
    assertThat(attempts).hasValue(1);
    assertThat(observer.opsForValue().get(OTHER)).isEqualTo("2");
  }

  /**
   * Connects as one ACL user.
   *
   * @param user the user
   * @param passwordVariable the variable naming its E2E password
   * @param pooled whether transactions borrow from a pool with {@link #TRANSACTION_CONNECTIONS}
   *     connection for them
   * @return the started factory
   */
  private static @NotNull LettuceConnectionFactory connect(
      @NotNull String user, @NotNull String passwordVariable, boolean pooled) {
    RedisStandaloneConfiguration config =
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
    config.setUsername(user);
    config.setPassword(RedisAclTemplate.E2E_PASSWORDS.get(passwordVariable));
    LettuceConnectionFactory factory;
    if (pooled) {
      GenericObjectPoolConfig<StatefulConnection<?, ?>> pool = new GenericObjectPoolConfig<>();
      pool.setMaxTotal(TRANSACTION_CONNECTIONS + 1);
      factory =
          new LettuceConnectionFactory(
              config, LettucePoolingClientConfiguration.builder().poolConfig(pool).build());
    } else {
      factory = new LettuceConnectionFactory(config);
    }
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
}
