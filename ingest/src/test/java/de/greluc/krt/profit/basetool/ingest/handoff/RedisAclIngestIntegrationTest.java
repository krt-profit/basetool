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

package de.greluc.krt.profit.basetool.ingest.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.ingest.support.TestProperties;
import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import de.greluc.krt.profit.basetool.testsupport.redis.RedisAclTemplate;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the gateway's real handoff staging under its own ACL user, against the committed ACL
 * template with {@code default} switched off (REQ-SEC-068, ADR-0207).
 *
 * <p>The gateway writes {@code ingest:handoff:<sub>:<id>} with a TTL and keeps an index list under
 * {@code ingest:handoff-index:}, evicting past the cap. That is all it may do: a session key,
 * {@code SCAN} (which would list every session id) and every channel are refused.
 */
@Testcontainers
class RedisAclIngestIntegrationTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS))
          .withExposedPorts(6379)
          .withCopyToContainer(
              Transferable.of(RedisAclTemplate.render(values())), "/etc/redis/users.acl")
          .withCommand("redis-server", "--aclfile", "/etc/redis/users.acl");

  private LettuceConnectionFactory ingest;
  private LettuceConnectionFactory admin;

  @BeforeEach
  void setUp() {
    ingest = connect(RedisAclTemplate.INGEST_USER, "REDIS_INGEST_PASSWORD");
    admin = connect(RedisAclTemplate.ADMIN_USER, "REDIS_PASSWORD");
  }

  @AfterEach
  void tearDown() {
    ingest.destroy();
    admin.destroy();
  }

  @Test
  void stagingAndTheCapEvictionRunUnderTheIngestUser() {
    HandoffStagingService service =
        new HandoffStagingService(
            template(ingest), JsonMapper.builder().build(), TestProperties.ingest());

    String first =
        service
            .stageDraft("versekit", "sub-acl", HandoffKind.REFINERY, "{\"goodsMatched\":1}", 1)
            .handoffId();
    String second =
        service
            .stageDraft("versekit", "sub-acl", HandoffKind.REFINERY, "{\"goodsMatched\":2}", 1)
            .handoffId();

    StringRedisTemplate observer = template(admin);
    assertThat(observer.hasKey("ingest:handoff:sub-acl:" + second)).isTrue();
    assertThat(observer.hasKey("ingest:handoff:sub-acl:" + first))
        .as("the cap evicted the first handoff: LPOP + DEL ran under the ingest user")
        .isFalse();
    assertThat(observer.getExpire("ingest:handoff-index:drafts:versekit:sub-acl")).isPositive();
  }

  @Test
  void theExchangeRegistryIsReadOnlyForTheIngestUser() {
    template(admin).opsForValue().set("exchange:registry", "{}");
    StringRedisTemplate template = template(ingest);

    assertThat(template.opsForValue().get("exchange:registry")).isEqualTo("{}");
    assertThatThrownBy(() -> template.opsForValue().set("exchange:registry", "{\"enabled\":true}"))
        .as("the gateway must never rewrite its own registry")
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> template.delete("exchange:registry"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void theGatewayCountsExchangeQuotasInItsOwnKeysOnly() {
    StringRedisTemplate template = template(ingest);

    assertThat(template.opsForValue().increment("ingest:xch:quota:versekit:m-1:2026-09-27"))
        .isEqualTo(1L);
    assertThatThrownBy(() -> template.opsForValue().increment("exchange:registry"))
        .as("the registry mirror stays read-only")
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void theBudgetAndTheIdempotencyLockRunUnderTheIngestUser() {
    StringRedisTemplate template = template(ingest);
    String budget = "ingest:xch:budget:m:versekit:m-1";

    assertThat(template.opsForZSet().add(budget, "ingest:xch:idem:k|512", 1_000.0)).isTrue();
    assertThat(template.opsForZSet().add(budget, "ingest:xch:idem:j|256", 2_000.0)).isTrue();
    assertThat(template.opsForZSet().rangeByScore(budget, 0, 1_500.0))
        .containsExactly("ingest:xch:idem:k|512");
    assertThat(template.opsForZSet().remove(budget, "ingest:xch:idem:k|512")).isEqualTo(1L);
    assertThat(template.opsForZSet().score(budget, "ingest:xch:idem:j|256")).isEqualTo(2_000.0);
    assertThat(template.opsForZSet().range(budget, 0, -1)).containsExactly("ingest:xch:idem:j|256");
    assertThat(
            template
                .opsForValue()
                .setIfAbsent("ingest:xch:idem-lock:versekit:m-1:h", "1", Duration.ofMinutes(2)))
        .isTrue();
    assertThatThrownBy(() -> template.opsForZSet().add("exchange:registry", "x", 1.0))
        .as("the registry mirror stays read-only")
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void commandsNoCodePathSendsAreRefusedOnItsOwnKeys() {
    StringRedisTemplate template = template(ingest);
    String budget = "ingest:xch:budget:m:versekit:m-2";
    assertThat(template.opsForZSet().add(budget, "ingest:xch:idem:k|512", 1_000.0)).isTrue();

    assertThatThrownBy(() -> template.opsForZSet().removeRangeByScore(budget, 0, 1_500.0))
        .as("the budget prunes with ZRANGEBYSCORE and ZREM inside its script")
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> template.unlink(budget))
        .as("keys are deleted with DEL")
        .isInstanceOf(DataAccessException.class);
    assertThat(template.delete(budget)).isTrue();
  }

  @Test
  void everythingBeyondItsOwnKeysIsRefused() {
    StringRedisTemplate template = template(ingest);

    assertThatThrownBy(() -> template.opsForValue().get("basetool:session:sessions:x"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> template.convertAndSend("basetool:livesync:changed", "{}"))
        .isInstanceOf(DataAccessException.class);
    try (RedisConnection connection = ingest.getConnection()) {
      assertThatThrownBy(
              () ->
                  connection
                      .keyCommands()
                      .scan(org.springframework.data.redis.core.ScanOptions.NONE)
                      .hasNext())
          .as("SCAN is not key-checked and would list every session id")
          .isInstanceOf(DataAccessException.class);
      assertThat(connection.serverCommands().info("server")).isNotEmpty();
    }
  }

  @Test
  void aScriptRunsUnderTheIngestUsersOwnRules() {
    template(admin).opsForValue().set("basetool:session:sessions:x", "secret");
    StringRedisTemplate template = template(ingest);
    RedisScript<String> get =
        new DefaultRedisScript<>("return redis.call('GET', KEYS[1])", String.class);
    RedisScript<String> getUndeclared =
        new DefaultRedisScript<>(
            "return redis.call('GET', 'basetool:session:sessions:x')", String.class);
    RedisScript<Long> zrem =
        new DefaultRedisScript<>("return redis.call('ZREM', KEYS[1], ARGV[1])", Long.class);
    template.opsForValue().set("ingest:xch:idem-lock:acl", "token");

    assertThat(template.execute(get, List.of("ingest:xch:idem-lock:acl"))).isEqualTo("token");
    assertThat(template.execute(zrem, List.of("ingest:xch:budget:all"), "x")).isZero();
    assertThatThrownBy(() -> template.execute(get, List.of("basetool:session:sessions:x")))
        .as("a declared foreign key is refused")
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> template.execute(getUndeclared, List.of()))
        .as("a script cannot reach a key its user could not")
        .isInstanceOf(DataAccessException.class);
  }

  /**
   * Connects as an ACL user.
   *
   * @param user the ACL user.
   * @param passwordVariable the template variable holding its password.
   * @return a started connection factory.
   */
  private static LettuceConnectionFactory connect(String user, String passwordVariable) {
    RedisStandaloneConfiguration configuration =
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
    configuration.setUsername(user);
    configuration.setPassword(RedisAclTemplate.E2E_PASSWORDS.get(passwordVariable));
    LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
    factory.afterPropertiesSet();
    factory.start();
    return factory;
  }

  /**
   * Builds a string template over a factory.
   *
   * @param factory the connection factory.
   * @return the template.
   */
  private static StringRedisTemplate template(LettuceConnectionFactory factory) {
    StringRedisTemplate template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    return template;
  }

  /**
   * The template's variables for this suite.
   *
   * @return the E2E throwaway passwords plus {@code REDIS_DEFAULT_USER=off}.
   */
  private static Map<String, String> values() {
    Map<String, String> values = new HashMap<>(RedisAclTemplate.E2E_PASSWORDS);
    values.put("REDIS_DEFAULT_USER", "off");
    return values;
  }
}
