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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import de.greluc.krt.profit.basetool.testsupport.redis.RedisAclTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A start with mirroring off switches off a registry document an earlier run left in Redis, under
 * the backend's own ACL user, and leaves every other state alone (REQ-XCH-003, security review G5,
 * L2).
 */
@Testcontainers
class ExchangeRegistryMirrorClosureTest {

  private static final String KEY = "exchange:registry";

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS))
          .withExposedPorts(6379)
          .withCopyToContainer(
              Transferable.of(RedisAclTemplate.render(values())), "/etc/redis/users.acl")
          .withCommand("redis-server", "--aclfile", "/etc/redis/users.acl");

  private final ExchangeSettingsRepository settingsRepository =
      mock(ExchangeSettingsRepository.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  private LettuceConnectionFactory adminFactory;
  private LettuceConnectionFactory backendFactory;
  private StringRedisTemplate admin;
  private StringRedisTemplate backend;

  @BeforeEach
  void setUp() {
    adminFactory =
        factory(RedisAclTemplate.ADMIN_USER, RedisAclTemplate.E2E_PASSWORDS.get("REDIS_PASSWORD"));
    backendFactory =
        factory(
            RedisAclTemplate.BACKEND_USER,
            RedisAclTemplate.E2E_PASSWORDS.get("REDIS_BACKEND_PASSWORD"));
    admin = template(adminFactory);
    backend = template(backendFactory);
    admin.delete(KEY);
    when(settingsRepository.nextRevision()).thenReturn(42L);
  }

  @AfterEach
  void tearDown() {
    adminFactory.destroy();
    backendFactory.destroy();
  }

  @Test
  void aDocumentLeftBehindIsSwitchedOffAndKeepsItsClients() {
    leaveDocument(true, 7);

    closure(new DisabledExchangeRegistryMirror(), backend, true).closeLeftDocument();

    JsonNode document = stored();
    assertThat(document.get("enabled").booleanValue()).isFalse();
    assertThat(document.get("revision").longValue()).isEqualTo(42L);
    assertThat(document.get("clients").get("versekit").get("status").stringValue())
        .isEqualTo("ACTIVE");
    assertThat(count("written")).isEqualTo(1.0d);
  }

  @Test
  void aMissingOrSwitchedOffDocumentIsLeftAlone() {
    ExchangeRegistryMirrorClosure closure =
        closure(new DisabledExchangeRegistryMirror(), backend, true);

    closure.closeLeftDocument();
    assertThat(admin.opsForValue().get(KEY)).isNull();

    leaveDocument(false, 7);
    closure.closeLeftDocument();
    assertThat(stored().get("revision").longValue()).isEqualTo(7L);
    assertThat(count("unchanged")).isEqualTo(2.0d);
  }

  @Test
  void anActiveMirrorOrTheSwitchedOffClosureLeavesTheDocumentAlone() {
    leaveDocument(true, 7);
    ExchangeRegistryMirror active = mock(ExchangeRegistryMirror.class);
    when(active.isActive()).thenReturn(true);

    closure(active, backend, true).closeLeftDocument();
    closure(new DisabledExchangeRegistryMirror(), backend, false).closeLeftDocument();

    assertThat(stored().get("enabled").booleanValue()).isTrue();
    assertThat(stored().get("revision").longValue()).isEqualTo(7L);
  }

  @Test
  void aRefusedReadIsCountedAndDoesNotFailTheStart() {
    leaveDocument(true, 7);
    LettuceConnectionFactory monitoringFactory =
        factory(
            RedisAclTemplate.MONITORING_USER,
            RedisAclTemplate.E2E_PASSWORDS.get("REDIS_EXPORTER_PASSWORD"));
    try {
      closure(new DisabledExchangeRegistryMirror(), template(monitoringFactory), true)
          .closeLeftDocument();
    } finally {
      monitoringFactory.destroy();
    }

    assertThat(count("failed")).isEqualTo(1.0d);
    assertThat(stored().get("enabled").booleanValue()).isTrue();
  }

  /**
   * Builds the closure over the given mirror and template.
   *
   * @param mirror the wired mirror
   * @param template the template the closure reaches Redis with
   * @param closeWhenOff whether the closure runs at all
   * @return the closure
   */
  private @NotNull ExchangeRegistryMirrorClosure closure(
      @NotNull ExchangeRegistryMirror mirror,
      @NotNull StringRedisTemplate template,
      boolean closeWhenOff) {
    return new ExchangeRegistryMirrorClosure(
        mirror,
        template,
        new ExchangeMirrorProperties(false, KEY, Duration.ofSeconds(60), closeWhenOff),
        settingsRepository,
        meterRegistry,
        mock(PlatformTransactionManager.class));
  }

  /**
   * Leaves a document with one active client in Redis, as mirroring would have written it.
   *
   * @param enabled the document's switch
   * @param revision the document's revision
   */
  private void leaveDocument(boolean enabled, long revision) {
    TreeMap<String, ExchangeRegistrySnapshot.Client> clients = new TreeMap<>();
    clients.put(
        "versekit",
        new ExchangeRegistrySnapshot.Client(
            "VerseKit",
            ExchangeClientStatus.ACTIVE,
            List.of("exchange.connect"),
            null,
            null,
            null));
    new RedisExchangeRegistryMirror(admin, KEY, Clock.systemUTC())
        .write(new ExchangeRegistrySnapshot(enabled, clients), revision);
  }

  /**
   * Reads the stored document as the admin.
   *
   * @return the parsed document
   */
  private @NotNull JsonNode stored() {
    String raw = admin.opsForValue().get(KEY);
    assertThat(raw).isNotNull();
    return jsonMapper.readTree(raw);
  }

  /**
   * Reads the closure's outcome counter.
   *
   * @param outcome the outcome label
   * @return its count, {@code 0} when it was never counted
   */
  private double count(@NotNull String outcome) {
    Counter counter =
        meterRegistry
            .find("basetool.exchange.mirror.writes")
            .tag("phase", "switched_off")
            .tag("outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  /**
   * Opens a connection factory as one ACL user.
   *
   * @param user the ACL user
   * @param password its throwaway password
   * @return the started factory
   */
  private static @NotNull LettuceConnectionFactory factory(
      @NotNull String user, @NotNull String password) {
    RedisStandaloneConfiguration configuration =
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
    configuration.setUsername(user);
    configuration.setPassword(password);
    LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
    factory.afterPropertiesSet();
    factory.start();
    return factory;
  }

  /**
   * Wraps a factory in a string template.
   *
   * @param factory the factory
   * @return the template
   */
  private static @NotNull StringRedisTemplate template(@NotNull LettuceConnectionFactory factory) {
    StringRedisTemplate template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    return template;
  }

  /**
   * The ACL template's variables for this suite.
   *
   * @return the E2E throwaway passwords plus {@code REDIS_DEFAULT_USER=off}
   */
  private static @NotNull Map<String, String> values() {
    Map<String, String> values = new HashMap<>(RedisAclTemplate.E2E_PASSWORDS);
    values.put("REDIS_DEFAULT_USER", "off");
    return values;
  }
}
