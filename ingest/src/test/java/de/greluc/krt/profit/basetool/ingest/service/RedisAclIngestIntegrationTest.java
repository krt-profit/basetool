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

import de.greluc.krt.profit.basetool.ingest.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.ingest.support.TestProperties;
import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import de.greluc.krt.profit.basetool.testsupport.redis.RedisAclTemplate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * <p>The gateway writes {@code ingest:handoff:<sub>:<id>} with a TTL and keeps the per-subject
 * index list {@code ingest:handoff-index:<sub>}, evicting past the cap. That is all it may do: a
 * session key, {@code SCAN} (which would list every session id) and every channel are refused.
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
            template(ingest),
            JsonMapper.builder().build(),
            TestProperties.ingest("max-handoffs-per-subject", "1"));

    String first = service.stage("sub-acl", HandoffKind.REFINERY, "{\"goodsMatched\":1}");
    String second = service.stage("sub-acl", HandoffKind.REFINERY, "{\"goodsMatched\":2}");

    StringRedisTemplate observer = template(admin);
    assertThat(observer.hasKey("ingest:handoff:sub-acl:" + second)).isTrue();
    assertThat(observer.hasKey("ingest:handoff:sub-acl:" + first))
        .as("the cap evicted the first handoff: LPOP + DEL ran under the ingest user")
        .isFalse();
    assertThat(observer.getExpire("ingest:handoff-index:sub-acl")).isPositive();
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
