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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import de.greluc.krt.profit.basetool.testsupport.redis.RedisAclTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.FlushMode;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository.RedisSession;
import org.springframework.session.events.SessionCreatedEvent;
import org.springframework.session.events.SessionDeletedEvent;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the frontend's real Redis work under its own ACL user, against the committed ACL template
 * with {@code default} switched off — the state production reaches at the end of the per-service
 * rollout (REQ-SEC-068, ADR-0207).
 *
 * <p>What it proves, in the order a member meets it: Spring Session stores, indexes, renames and
 * deletes a session and hears its created and deleted events; the keyspace-notification startup
 * step survives a user that may not run {@code CONFIG}; live sync publishes and receives on both
 * channels; a staged handoff is consumed; the session count is scanned and the health indicator's
 * {@code INFO} answers. And what it refuses: the gateway's index keys, any foreign key, {@code
 * CONFIG}, {@code KEYS}, {@code FLUSHALL}, the backend's notification channel.
 *
 * <p>{@link #theAclMatrixHoldsForEveryUser} is the {@code ACL DRYRUN} table for all five users, and
 * {@link #theCommittedE2eAclIsTheTemplate} keeps {@code docker/test-redis/users.acl} — what the E2E
 * stack loads — equal to the template, so the Playwright suite runs these same rules.
 */
@Testcontainers
class RedisAclFrontendIntegrationTest {

  /** The template's variables for this suite: the E2E throwaway passwords, {@code default} off. */
  private static final Map<String, String> VALUES = values();

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS))
          .withExposedPorts(6379)
          .withCopyToContainer(
              Transferable.of(RedisAclTemplate.render(VALUES)), "/etc/redis/users.acl")
          .withCommand(
              "redis-server",
              "--aclfile",
              "/etc/redis/users.acl",
              "--notify-keyspace-events",
              "Egx");

  private final List<LettuceConnectionFactory> factories = new ArrayList<>();
  private final List<RedisMessageListenerContainer> containers = new ArrayList<>();

  @AfterEach
  void tearDown() {
    containers.forEach(RedisMessageListenerContainer::stop);
    factories.forEach(LettuceConnectionFactory::destroy);
  }

  @Test
  void theCommittedE2eAclIsTheTemplate() throws IOException {
    String committed =
        Files.readString(
            RedisAclTemplate.repositoryPath("docker/test-redis/users.acl"), StandardCharsets.UTF_8);

    assertThat(committed.replace("\r\n", "\n"))
        .as("docker/test-redis/users.acl must be re-rendered whenever the template changes")
        .isEqualTo(RedisAclTemplate.render(VALUES));
  }

  @Test
  void springSessionStoresFindsRenamesAndDeletesUnderTheFrontendUser() throws Exception {
    LettuceConnectionFactory factory =
        connect(RedisAclTemplate.FRONTEND_USER, "REDIS_FRONTEND_PASSWORD");
    RedisIndexedSessionRepository repository = sessionRepository(factory);
    List<ApplicationEvent> events = new CopyOnWriteArrayList<>();
    CountDownLatch created = new CountDownLatch(1);
    CountDownLatch deleted = new CountDownLatch(1);
    repository.setApplicationEventPublisher(
        event -> {
          if (event instanceof ApplicationEvent applicationEvent) {
            events.add(applicationEvent);
          }
          if (event instanceof SessionCreatedEvent) {
            created.countDown();
          }
          if (event instanceof SessionDeletedEvent) {
            deleted.countDown();
          }
        });
    // Subscribed exactly as Spring Session's own configuration subscribes the repository: two
    // keyspace-event channels and the created-event PATTERN, which the ACL has to spell literally.
    RedisMessageListenerContainer listener = listenerContainer(factory);
    listener.addMessageListener(
        repository,
        List.of(
            new ChannelTopic(repository.getSessionDeletedChannel()),
            new ChannelTopic(repository.getSessionExpiredChannel())));
    listener.addMessageListener(
        repository, new PatternTopic(repository.getSessionCreatedChannelPrefix() + "*"));
    waitUntilListening(listener);

    RedisSession session = repository.createSession();
    session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "member");
    session.setAttribute("iridium.activeOrgUnitId", "00000000-0000-0000-0000-000000000001");
    repository.save(session);
    assertThat(created.await(5, TimeUnit.SECONDS)).as("session-created event received").isTrue();

    assertThat(repository.findById(session.getId())).isNotNull();
    assertThat(repository.findByPrincipalName("member")).containsKey(session.getId());

    RedisSession reloaded = repository.findById(session.getId());
    String renamed = reloaded.changeSessionId();
    repository.save(reloaded);
    assertThat(repository.findById(renamed))
        .as("the RENAME of a session-fixation change")
        .isNotNull();

    repository.deleteById(renamed);
    assertThat(deleted.await(5, TimeUnit.SECONDS)).as("keyspace del event received").isTrue();
    assertThat(repository.findById(renamed)).isNull();
    repository.cleanUpExpiredSessions();
    assertThat(events).isNotEmpty();
  }

  @Test
  void theKeyspaceStartupStepSurvivesAUserThatMayNotRunConfig() {
    LettuceConnectionFactory factory =
        connect(RedisAclTemplate.FRONTEND_USER, "REDIS_FRONTEND_PASSWORD");
    try (RedisConnection connection = factory.getConnection()) {
      new TolerantKeyspaceNotificationsAction().configure(connection);
    }
    // And the server really carries the setting the frontend no longer applies itself.
    LettuceConnectionFactory admin = connect(RedisAclTemplate.ADMIN_USER, "REDIS_PASSWORD");
    try (RedisConnection connection = admin.getConnection()) {
      Properties config = connection.serverCommands().getConfig("notify-keyspace-events");
      assertThat(config.getProperty("notify-keyspace-events"))
          .contains("E")
          .contains("g")
          .contains("x");
    }
  }

  @Test
  void anyOtherFailureOfTheStartupStepStillFailsTheStartup() {
    TolerantKeyspaceNotificationsAction action =
        new TolerantKeyspaceNotificationsAction(
            connection -> {
              throw new RedisSystemException(
                  "Unable to connect to Redis", new IllegalStateException());
            });

    assertThatThrownBy(() -> action.configure(org.mockito.Mockito.mock(RedisConnection.class)))
        .isInstanceOf(DataAccessException.class);
    assertThat(TolerantKeyspaceNotificationsAction.isAclRefusal(new IllegalStateException("x")))
        .isFalse();
    assertThat(
            TolerantKeyspaceNotificationsAction.isAclRefusal(
                new RedisSystemException(
                    "Error in execution",
                    new IllegalStateException("NOPERM User may not run 'config|get'"))))
        .isTrue();
  }

  @Test
  void liveSyncPublishesAndReceivesOnBothChannels() throws Exception {
    LettuceConnectionFactory factory =
        connect(RedisAclTemplate.FRONTEND_USER, "REDIS_FRONTEND_PASSWORD");
    StringRedisTemplate template = template(factory);
    RedisMessageListenerContainer listener = listenerContainer(factory);
    CountDownLatch received = new CountDownLatch(2);
    listener.addMessageListener(
        (message, pattern) -> received.countDown(),
        List.of(
            new ChannelTopic("basetool:livesync:changed"),
            new ChannelTopic("basetool:livesync:presence")));
    waitUntilListening(listener);

    template.convertAndSend("basetool:livesync:changed", "{}");
    template.convertAndSend("basetool:livesync:presence", "{}");

    assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
    assertThatThrownBy(() -> template.convertAndSend("basetool:notify:published", "{}"))
        .as("the backend's notification channel is not the frontend's")
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aStagedHandoffIsConsumedAndTheGatewayIndexStaysOutOfReach() {
    StringRedisTemplate admin = template(connect(RedisAclTemplate.ADMIN_USER, "REDIS_PASSWORD"));
    admin.opsForValue().set("ingest:handoff:sub-1:hid-1", "{\"kind\":\"REFINERY\"}");
    admin.opsForList().rightPush("ingest:handoff-index:sub-1", "hid-1");
    StringRedisTemplate frontend =
        template(connect(RedisAclTemplate.FRONTEND_USER, "REDIS_FRONTEND_PASSWORD"));

    assertThat(frontend.opsForValue().getAndDelete("ingest:handoff:sub-1:hid-1"))
        .isEqualTo("{\"kind\":\"REFINERY\"}");
    assertThatThrownBy(() -> frontend.opsForList().leftPop("ingest:handoff-index:sub-1"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> frontend.opsForValue().set("other:key", "v"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void theSessionCountIsScannedAndTheHealthIndicatorsInfoAnswers() {
    LettuceConnectionFactory factory =
        connect(RedisAclTemplate.FRONTEND_USER, "REDIS_FRONTEND_PASSWORD");
    StringRedisTemplate template = template(factory);
    template.opsForHash().put("basetool:session:sessions:scan-probe", "lastAccessedTime", "1");

    int found = 0;
    try (Cursor<String> cursor =
        template.scan(ScanOptions.scanOptions().match("basetool:session:sessions:*").build())) {
      while (cursor.hasNext()) {
        cursor.next();
        found++;
      }
    }
    assertThat(found).isPositive();
    try (RedisConnection connection = factory.getConnection()) {
      assertThat(connection.serverCommands().info("server")).isNotEmpty();
      assertThatThrownBy(() -> connection.keyCommands().keys("*".getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(() -> connection.serverCommands().flushAll())
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(() -> connection.serverCommands().setConfig("dir", "/tmp"))
          .isInstanceOf(DataAccessException.class);
    }
  }

  @Test
  void theDefaultUserIsOffAndAPasswordOnlyAuthNoLongerWorks() {
    LettuceConnectionFactory legacy = connectWithoutUsername(VALUES.get("REDIS_PASSWORD"));

    assertThatThrownBy(() -> template(legacy).opsForValue().get("basetool:session:x"))
        .as("password-only AUTH lands on `default`, which is off")
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void beforeTheRolloutAPasswordOnlyAuthIsExactlyTodaysDefaultUser() {
    // The merged-but-not-rolled-out state: every app still has an EMPTY REDIS_USERNAME and the
    // shared REDIS_PASSWORD, and `default` is on. That must be a password-only AUTH that works.
    StringRedisTemplate admin = template(connect(RedisAclTemplate.ADMIN_USER, "REDIS_PASSWORD"));
    admin.execute(
        (org.springframework.data.redis.core.RedisCallback<Object>)
            connection ->
                connection.execute("ACL", bytes("SETUSER"), bytes("default"), bytes("on")));
    try {
      StringRedisTemplate legacy = template(connectWithUsername("", VALUES.get("REDIS_PASSWORD")));
      legacy.opsForValue().set("basetool:session:legacy-probe", "v");
      assertThat(legacy.opsForValue().getAndDelete("basetool:session:legacy-probe")).isEqualTo("v");
    } finally {
      admin.execute(
          (org.springframework.data.redis.core.RedisCallback<Object>)
              connection ->
                  connection.execute("ACL", bytes("SETUSER"), bytes("default"), bytes("off")));
    }
  }

  /**
   * The {@code ACL DRYRUN} table: for each user, a command it needs and must be allowed, or one it
   * must never run.
   *
   * @param user the ACL user.
   * @param command the command line, space-separated.
   * @param allowed whether the ACL must allow it.
   */
  @ParameterizedTest(name = "{0}: {1} -> {2}")
  @CsvSource({
    "basetool-frontend, HGETALL basetool:session:sessions:x, true",
    "basetool-frontend, HSET basetool:session:sessions:x f v, true",
    "basetool-frontend, RENAME basetool:session:sessions:a basetool:session:sessions:b, true",
    "basetool-frontend, SADD basetool:session:index:x id, true",
    "basetool-frontend, APPEND basetool:session:sessions:expires:x v, true",
    "basetool-frontend, SCAN 0, true",
    "basetool-frontend, GETDEL ingest:handoff:sub:id, true",
    "basetool-frontend, PUBLISH basetool:session:event:0:created:x m, true",
    "basetool-frontend, PSUBSCRIBE basetool:session:event:0:created:*, true",
    "basetool-frontend, SUBSCRIBE __keyevent@0__:expired, true",
    "basetool-frontend, PUBLISH basetool:livesync:changed m, true",
    "basetool-frontend, INFO server, true",
    "basetool-frontend, GET ingest:handoff-index:sub, false",
    "basetool-frontend, SET other:key v, false",
    "basetool-frontend, PUBLISH basetool:notify:published m, false",
    "basetool-frontend, PSUBSCRIBE *, false",
    "basetool-frontend, CONFIG SET dir /tmp, false",
    "basetool-frontend, CONFIG GET notify-keyspace-events, false",
    "basetool-frontend, KEYS *, false",
    "basetool-frontend, FLUSHALL, false",
    "basetool-frontend, ACL LIST, false",
    "basetool-backend, PUBLISH basetool:notify:published m, true",
    "basetool-backend, PUBLISH basetool:livesync:changed m, true",
    "basetool-backend, SUBSCRIBE basetool:notify:published, true",
    "basetool-backend, SUBSCRIBE basetool:livesync:changed, true",
    "basetool-backend, INFO server, true",
    "basetool-backend, PUBLISH basetool:livesync:presence m, false",
    "basetool-backend, GET basetool:session:sessions:x, false",
    "basetool-backend, SET ingest:handoff:a b, false",
    "basetool-backend, SCAN 0, false",
    "basetool-backend, KEYS *, false",
    "basetool-ingest, SET ingest:handoff:s:i v PX 1000, true",
    "basetool-ingest, RPUSH ingest:handoff-index:s i, true",
    "basetool-ingest, LPOP ingest:handoff-index:s, true",
    "basetool-ingest, EXPIRE ingest:handoff-index:s 60, true",
    "basetool-ingest, DEL ingest:handoff:s:i, true",
    "basetool-ingest, INFO server, true",
    "basetool-ingest, GET basetool:session:sessions:x, false",
    "basetool-ingest, SCAN 0, false",
    "basetool-ingest, PUBLISH basetool:livesync:changed m, false",
    "basetool-ingest, FLUSHDB, false",
    "monitoring, INFO, true",
    "monitoring, CONFIG GET maxmemory, true",
    "monitoring, SCAN 0, false",
    "monitoring, RANDOMKEY, false",
    "monitoring, GET basetool:session:sessions:x, false",
    "monitoring, CONFIG SET dir /tmp, false",
    "admin, ACL LOAD, true"
  })
  void theAclMatrixHoldsForEveryUser(String user, String command, boolean allowed) {
    StringRedisTemplate admin = template(connect(RedisAclTemplate.ADMIN_USER, "REDIS_PASSWORD"));
    List<byte[]> args = new ArrayList<>();
    args.add(bytes("DRYRUN"));
    args.add(bytes(user));
    for (String word : command.split(" ")) {
      args.add(bytes(word));
    }

    Object reply =
        admin.execute(
            (org.springframework.data.redis.core.RedisCallback<Object>)
                connection -> connection.execute("ACL", args.toArray(new byte[0][])));
    String verdict =
        reply instanceof byte[] raw
            ? new String(raw, StandardCharsets.UTF_8)
            : String.valueOf(reply);

    assertThat("OK".equals(verdict)).as("%s: %s -> %s", user, command, verdict).isEqualTo(allowed);
  }

  /**
   * The template's variables for this suite.
   *
   * @return the E2E throwaway passwords plus {@code REDIS_DEFAULT_USER=off}.
   */
  private static Map<String, String> values() {
    Map<String, String> values = new HashMap<>(RedisAclTemplate.E2E_PASSWORDS);
    values.put("REDIS_DEFAULT_USER", "off");
    return Map.copyOf(values);
  }

  /**
   * Connects as an ACL user.
   *
   * @param user the ACL user.
   * @param passwordVariable the template variable holding its password.
   * @return a started connection factory, destroyed after the test.
   */
  private LettuceConnectionFactory connect(String user, String passwordVariable) {
    return connectWithUsername(user, VALUES.get(passwordVariable));
  }

  /**
   * Connects with no username at all — a password-only {@code AUTH}, which is what an application
   * with an empty {@code REDIS_USERNAME} sends.
   *
   * @param password the password.
   * @return a started connection factory, destroyed after the test.
   */
  private LettuceConnectionFactory connectWithoutUsername(String password) {
    return connectWithUsername(null, password);
  }

  /**
   * Connects with the given credentials, the way Spring Boot builds the factory from {@code
   * spring.data.redis.username} / {@code password}.
   *
   * @param username the username; {@code null} or empty means a password-only {@code AUTH}.
   * @param password the password.
   * @return a started connection factory, destroyed after the test.
   */
  private LettuceConnectionFactory connectWithUsername(String username, String password) {
    RedisStandaloneConfiguration configuration =
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
    configuration.setUsername(username);
    configuration.setPassword(password);
    LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
    factory.afterPropertiesSet();
    factory.start();
    factories.add(factory);
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
   * Starts a listener container over a factory.
   *
   * @param factory the connection factory.
   * @return the running container, stopped after the test.
   */
  private RedisMessageListenerContainer listenerContainer(LettuceConnectionFactory factory) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(factory);
    container.afterPropertiesSet();
    container.start();
    containers.add(container);
    return container;
  }

  /**
   * Waits until a listener container's subscriptions are live, failing when they never are — an ACL
   * that refuses a channel shows up here, not as a missing message five seconds later.
   *
   * @param container the container.
   * @throws InterruptedException when interrupted while waiting.
   */
  private static void waitUntilListening(RedisMessageListenerContainer container)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!container.isListening() && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertThat(container.isListening()).as("the subscriptions are live").isTrue();
    Thread.sleep(200);
  }

  /**
   * Builds the session repository exactly as {@code RedisSessionConfig} does in production.
   *
   * @param factory the connection factory.
   * @return the repository.
   */
  private static RedisIndexedSessionRepository sessionRepository(LettuceConnectionFactory factory) {
    RedisSerializer<Object> serializer =
        new FaultTolerantSessionSerializer(
            new GenericJacksonJsonRedisSerializer(
                // The session TYPE allow-list is REQ-SEC-067's subject and has its own suite; this
                // one is about Redis PERMISSIONS, so it reads with the validator production ships
                // by
                // default rather than letting a type refusal masquerade as an ACL failure.
                RedisSessionConfig.buildSessionJsonMapper(
                    RedisAclFrontendIntegrationTest.class.getClassLoader(),
                    SessionTypeAllowList.validatorBuilder(
                        SessionTypeAllowList.Mode.REPORT,
                        SessionTypeAllowList.RefusalListener.NONE))),
            registryProvider());
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setKeySerializer(RedisSerializer.string());
    template.setHashKeySerializer(RedisSerializer.string());
    template.setDefaultSerializer(serializer);
    template.setConnectionFactory(factory);
    template.afterPropertiesSet();
    RedisIndexedSessionRepository repository = new RedisIndexedSessionRepository(template);
    // What @EnableRedisIndexedHttpSession does with the session serializer bean: the pub/sub path
    // (the created event's payload) reads with the default serializer, not the template's.
    repository.setDefaultSerializer(serializer);
    repository.setDefaultMaxInactiveInterval(Duration.ofMinutes(30));
    repository.setRedisKeyNamespace("basetool:session");
    repository.setFlushMode(FlushMode.IMMEDIATE);
    repository.setRedisSessionMapper(new SessionAttributeDiagnosticMapper(registryProvider()));
    return repository;
  }

  /**
   * A provider over a throwaway registry, the shape {@code RedisSessionConfig} hands its parts.
   *
   * @return the provider.
   */
  private static ObjectProvider<MeterRegistry> registryProvider() {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerSingleton("meterRegistry", new SimpleMeterRegistry());
    return beanFactory.getBeanProvider(MeterRegistry.class);
  }

  /**
   * Encodes a command word.
   *
   * @param word the word.
   * @return its UTF-8 bytes.
   */
  private static byte[] bytes(String word) {
    return word.getBytes(StandardCharsets.UTF_8);
  }
}
