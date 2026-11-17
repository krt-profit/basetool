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

package de.greluc.krt.profit.basetool.frontend.kernel.session;

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
import org.springframework.session.data.redis.config.ConfigureRedisAction;
import org.springframework.session.events.SessionCreatedEvent;
import org.springframework.session.events.SessionDeletedEvent;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the frontend's Redis work under its own ACL users against the committed ACL templates with
 * {@code default} disabled (REQ-SEC-068, ADR-0207): Spring Session on the session store, live sync
 * and the handoff pickup on the shared Redis (REQ-SEC-089, ADR-0388), checking both what each user
 * may and may not do.
 *
 * <p>{@link #theCommittedE2eAclsAreTheTemplates} keeps {@code docker/test-redis/session-users.acl}
 * and {@code shared-users.acl} equal to the templates.
 */
@Testcontainers
class RedisAclFrontendIntegrationTest {

  /** The templates' variables for this suite: the E2E throwaway passwords, {@code default} off. */
  private static final Map<String, String> VALUES = values();

  @Container
  static final GenericContainer<?> SESSION_STORE =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS))
          .withExposedPorts(6379)
          .withCopyToContainer(
              Transferable.of(RedisAclTemplate.renderSession(VALUES)), "/etc/redis/users.acl")
          .withCommand(
              "redis-server",
              "--aclfile",
              "/etc/redis/users.acl",
              "--notify-keyspace-events",
              "Egx");

  @Container
  static final GenericContainer<?> SHARED =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS))
          .withExposedPorts(6379)
          .withCopyToContainer(
              Transferable.of(RedisAclTemplate.renderShared(VALUES)), "/etc/redis/users.acl")
          .withCommand("redis-server", "--aclfile", "/etc/redis/users.acl");

  /** The session store's frontend password variable. */
  private static final String SESSION_FRONTEND = "REDIS_SESSION_FRONTEND_PASSWORD";

  /** The session store's admin password variable. */
  private static final String SESSION_ADMIN = "REDIS_SESSION_PASSWORD";

  /** The shared Redis's frontend password variable. */
  private static final String SHARED_FRONTEND = "REDIS_FRONTEND_PASSWORD";

  /** The shared Redis's admin password variable. */
  private static final String SHARED_ADMIN = "REDIS_PASSWORD";

  private final List<LettuceConnectionFactory> factories = new ArrayList<>();
  private final List<RedisMessageListenerContainer> containers = new ArrayList<>();

  @AfterEach
  void tearDown() {
    containers.forEach(RedisMessageListenerContainer::stop);
    factories.forEach(LettuceConnectionFactory::destroy);
  }

  @Test
  void theCommittedE2eAclsAreTheTemplates() throws IOException {
    assertThat(committed("docker/test-redis/session-users.acl"))
        .as("docker/test-redis/session-users.acl must be re-rendered whenever its template changes")
        .isEqualTo(RedisAclTemplate.renderSession(VALUES));
    assertThat(committed("docker/test-redis/shared-users.acl"))
        .as("docker/test-redis/shared-users.acl must be re-rendered whenever its template changes")
        .isEqualTo(RedisAclTemplate.renderShared(VALUES));
  }

  @Test
  void theSessionStoreKnowsNoUserOfTheBackendOrTheGateway() {
    StringRedisTemplate admin =
        template(connect(SESSION_STORE, RedisAclTemplate.ADMIN_USER, SESSION_ADMIN));

    assertThat(aclUser(admin, RedisAclTemplate.FRONTEND_USER))
        .as("the frontend's user exists on the session store")
        .isNotNull();
    assertThat(aclUser(admin, RedisAclTemplate.BACKEND_USER))
        .as("ACL GETUSER answers nil for a user the session store does not know")
        .isNull();
    assertThat(aclUser(admin, RedisAclTemplate.INGEST_USER)).isNull();
  }

  /**
   * Asks an instance for one ACL user's rules.
   *
   * @param admin a template authenticated as {@code admin}
   * @param user the ACL user
   * @return the last element of {@code ACL GETUSER}'s reply, or {@code null} when the user does not
   *     exist
   */
  private static Object aclUser(StringRedisTemplate admin, String user) {
    return admin.execute(
        (org.springframework.data.redis.core.RedisCallback<Object>)
            connection -> connection.execute("ACL", bytes("GETUSER"), bytes(user)));
  }

  @Test
  void springSessionStoresFindsRenamesAndDeletesUnderTheFrontendUser() throws Exception {
    LettuceConnectionFactory factory =
        connect(SESSION_STORE, RedisAclTemplate.FRONTEND_USER, SESSION_FRONTEND);
    RedisIndexedSessionRepository repository = sessionRepository(factory);
    List<ApplicationEvent> events = new CopyOnWriteArrayList<>();
    CountDownLatch created = new CountDownLatch(1);
    CountDownLatch deleted = new CountDownLatch(1);
    repository.setApplicationEventPublisher(event -> {
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
    session.setAttribute("basetool.activeOrgUnitId", "00000000-0000-0000-0000-000000000001");
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
        connect(SESSION_STORE, RedisAclTemplate.FRONTEND_USER, SESSION_FRONTEND);
    try (RedisConnection connection = factory.getConnection()) {
      new TolerantKeyspaceNotificationsAction().configure(connection);
    }
    LettuceConnectionFactory admin =
        connect(SESSION_STORE, RedisAclTemplate.ADMIN_USER, SESSION_ADMIN);
    try (RedisConnection connection = admin.getConnection()) {
      Properties config = connection.serverCommands().getConfig("notify-keyspace-events");
      assertThat(config.getProperty("notify-keyspace-events"))
          .contains("E")
          .contains("g")
          .contains("x");
    }
  }

  /**
   * Verifies that the startup step chosen for the frontend's ACL user does not increase Redis'
   * {@code acl_access_denied_cmd} counter, while the {@code CONFIG} step does.
   */
  @Test
  void theStartupStepPickedForTheFrontendUserIsNeverRefused() {
    LettuceConnectionFactory factory =
        connect(SESSION_STORE, RedisAclTemplate.FRONTEND_USER, SESSION_FRONTEND);
    StringRedisTemplate admin =
        template(connect(SESSION_STORE, RedisAclTemplate.ADMIN_USER, SESSION_ADMIN));

    long before = deniedCommands(admin);
    try (RedisConnection connection = factory.getConnection()) {
      RedisSessionConfig.selectConfigureRedisAction(true, RedisAclTemplate.FRONTEND_USER)
          .configure(connection);
    }
    assertThat(deniedCommands(admin))
        .as("refused commands after the startup step under basetool-frontend")
        .isEqualTo(before);

    try (RedisConnection connection = factory.getConnection()) {
      new TolerantKeyspaceNotificationsAction().configure(connection);
    }
    assertThat(deniedCommands(admin))
        .as("the CONFIG GET of before is refused and counted")
        .isEqualTo(before + 1);
  }

  /**
   * Dropping {@code CONFIG} did not drop the fail-fast start (ADR-0084): with a wrong password the
   * startup step under the frontend's user still throws, so the context refresh fails.
   */
  @Test
  void theStartupStepForTheFrontendUserStillFailsOnWrongCredentials() {
    LettuceConnectionFactory factory =
        connectWithUsername(
            SESSION_STORE, RedisAclTemplate.FRONTEND_USER, "not-the-frontend-password");
    ConfigureRedisAction action =
        RedisSessionConfig.selectConfigureRedisAction(true, RedisAclTemplate.FRONTEND_USER);

    assertThatThrownBy(
            () -> {
              try (RedisConnection connection = factory.getConnection()) {
                action.configure(connection);
              }
            })
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void anyOtherFailureOfTheStartupStepStillFailsTheStartup() {
    TolerantKeyspaceNotificationsAction action =
        new TolerantKeyspaceNotificationsAction(connection -> {
          throw new RedisSystemException("Unable to connect to Redis", new IllegalStateException());
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
  void liveSyncPublishesAndReceivesOnBothChannelsOfTheSharedRedis() throws Exception {
    LettuceConnectionFactory factory =
        connect(SHARED, RedisAclTemplate.FRONTEND_USER, SHARED_FRONTEND);
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
    StringRedisTemplate sessionStore =
        template(connect(SESSION_STORE, RedisAclTemplate.FRONTEND_USER, SESSION_FRONTEND));
    assertThatThrownBy(() -> sessionStore.convertAndSend("basetool:livesync:changed", "{}"))
        .as("live sync does not run on the session store")
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aStagedHandoffIsConsumedAndTheGatewayIndexStaysOutOfReach() {
    StringRedisTemplate admin =
        template(connect(SHARED, RedisAclTemplate.ADMIN_USER, SHARED_ADMIN));
    admin.opsForValue().set("ingest:handoff:sub-1:hid-1", "{\"kind\":\"REFINERY\"}");
    admin.opsForList().rightPush("ingest:handoff-index:sub-1", "hid-1");
    StringRedisTemplate frontend =
        template(connect(SHARED, RedisAclTemplate.FRONTEND_USER, SHARED_FRONTEND));

    assertThat(frontend.opsForValue().getAndDelete("ingest:handoff:sub-1:hid-1"))
        .isEqualTo("{\"kind\":\"REFINERY\"}");
    assertThatThrownBy(() -> frontend.opsForList().leftPop("ingest:handoff-index:sub-1"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> frontend.opsForValue().set("other:key", "v"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> frontend.opsForHash().entries("basetool:session:sessions:x"))
        .as("the shared Redis's frontend user reaches no session key")
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void theSessionCountIsScannedAndTheHealthIndicatorsInfoAnswers() {
    LettuceConnectionFactory factory =
        connect(SESSION_STORE, RedisAclTemplate.FRONTEND_USER, SESSION_FRONTEND);
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
    LettuceConnectionFactory session =
        connectWithoutUsername(SESSION_STORE, VALUES.get(SESSION_ADMIN));
    LettuceConnectionFactory shared = connectWithoutUsername(SHARED, VALUES.get(SHARED_ADMIN));

    assertThatThrownBy(() -> template(session).opsForValue().get("basetool:session:x"))
        .as("password-only AUTH lands on the session store's `default`, which is off")
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> template(shared).opsForValue().get("ingest:handoff:x"))
        .as("password-only AUTH lands on the shared Redis's `default`, which is off")
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void beforeTheRolloutAPasswordOnlyAuthIsExactlyTodaysDefaultUser() {
    StringRedisTemplate admin =
        template(connect(SESSION_STORE, RedisAclTemplate.ADMIN_USER, SESSION_ADMIN));
    admin.execute(
        (org.springframework.data.redis.core.RedisCallback<Object>)
            connection ->
                connection.execute("ACL", bytes("SETUSER"), bytes("default"), bytes("on")));
    try {
      StringRedisTemplate legacy =
          template(connectWithUsername(SESSION_STORE, "", VALUES.get(SESSION_ADMIN)));
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
   * The session store's {@code ACL DRYRUN} table: for each user, a command it needs and must be
   * allowed, or one it must never run.
   *
   * @param user the ACL user.
   * @param command the command line, space-separated.
   * @param allowed whether the ACL must allow it.
   */
  @ParameterizedTest(name = "session store, {0}: {1} -> {2}")
  @CsvSource({
    "basetool-frontend, HGETALL basetool:session:sessions:x, true",
    "basetool-frontend, HSET basetool:session:sessions:x f v, true",
    "basetool-frontend, RENAME basetool:session:sessions:a basetool:session:sessions:b, true",
    "basetool-frontend, SADD basetool:session:index:x id, true",
    "basetool-frontend, APPEND basetool:session:sessions:expires:x v, true",
    "basetool-frontend, SCAN 0, true",
    "basetool-frontend, PUBLISH basetool:session:event:0:created:x m, true",
    "basetool-frontend, PSUBSCRIBE basetool:session:event:0:created:*, true",
    "basetool-frontend, SUBSCRIBE __keyevent@0__:expired, true",
    "basetool-frontend, INFO server, true",
    "basetool-frontend, PING, true",
    "basetool-frontend, GETDEL ingest:handoff:sub:id, false",
    "basetool-frontend, GET exchange:registry, false",
    "basetool-frontend, PUBLISH basetool:livesync:changed m, false",
    "basetool-frontend, SUBSCRIBE basetool:livesync:presence, false",
    "basetool-frontend, SET other:key v, false",
    "basetool-frontend, PUBLISH basetool:notify:published m, false",
    "basetool-frontend, PSUBSCRIBE *, false",
    "basetool-frontend, CONFIG SET dir /tmp, false",
    "basetool-frontend, CONFIG GET notify-keyspace-events, false",
    "basetool-frontend, KEYS *, false",
    "basetool-frontend, FLUSHALL, false",
    "basetool-frontend, ACL LIST, false",
    "monitoring, INFO, true",
    "monitoring, CONFIG GET maxmemory, true",
    "monitoring, SCAN 0, false",
    "monitoring, RANDOMKEY, false",
    "monitoring, GET basetool:session:sessions:x, false",
    "monitoring, KEYS *, false",
    "monitoring, FLUSHALL, false",
    "monitoring, ACL LIST, false",
    "monitoring, LATENCY LATEST, true",
    "monitoring, PSUBSCRIBE basetool:session:event:0:created:*, false",
    "monitoring, CLIENT TRACKING ON BCAST PREFIX basetool:session:, false",
    "monitoring, CLIENT KILL USER basetool-frontend, false",
    "monitoring, CLIENT PAUSE 1000 ALL, false",
    "monitoring, CLIENT LIST, false",
    "monitoring, SLOWLOG GET 10, false",
    "basetool-frontend, CLIENT TRACKING ON BCAST PREFIX basetool:session:, false",
    "basetool-frontend, EVAL return 0, false",
    "admin, ACL LOAD, true"
  })
  void theSessionStoreAclMatrixHoldsForEveryUser(String user, String command, boolean allowed) {
    assertDryRun(SESSION_STORE, SESSION_ADMIN, user, command, allowed);
  }

  /**
   * The shared Redis's {@code ACL DRYRUN} table: for each user, a command it needs and must be
   * allowed, or one it must never run.
   *
   * @param user the ACL user.
   * @param command the command line, space-separated.
   * @param allowed whether the ACL must allow it.
   */
  @ParameterizedTest(name = "shared, {0}: {1} -> {2}")
  @CsvSource({
    "basetool-frontend, GETDEL ingest:handoff:sub:id, true",
    "basetool-frontend, PUBLISH basetool:livesync:changed m, true",
    "basetool-frontend, PUBLISH basetool:livesync:presence m, true",
    "basetool-frontend, SUBSCRIBE basetool:livesync:changed, true",
    "basetool-frontend, INFO server, true",
    "basetool-frontend, PING, true",
    "basetool-frontend, HGETALL basetool:session:sessions:x, false",
    "basetool-frontend, SCAN 0, false",
    "basetool-frontend, PSUBSCRIBE basetool:session:event:0:created:*, false",
    "basetool-frontend, SUBSCRIBE __keyevent@0__:expired, false",
    "basetool-frontend, GET ingest:handoff:sub:id, false",
    "basetool-frontend, GET ingest:handoff-index:sub, false",
    "basetool-frontend, GET exchange:registry, false",
    "basetool-frontend, SET other:key v, false",
    "basetool-frontend, PUBLISH basetool:notify:published m, false",
    "basetool-frontend, PSUBSCRIBE *, false",
    "basetool-frontend, CONFIG SET dir /tmp, false",
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
    "basetool-backend, CONFIG GET maxmemory, false",
    "basetool-backend, CONFIG SET dir /tmp, false",
    "basetool-backend, FLUSHALL, false",
    "basetool-backend, FLUSHDB, false",
    "basetool-backend, ACL LIST, false",
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
    "basetool-ingest, FLUSHALL, false",
    "basetool-ingest, CONFIG GET maxmemory, false",
    "basetool-ingest, CONFIG SET dir /tmp, false",
    "basetool-ingest, KEYS *, false",
    "basetool-ingest, ACL LIST, false",
    "basetool-ingest, WATCH ingest:xch:budget:all, true",
    "basetool-ingest, MULTI, true",
    "basetool-ingest, EXEC, true",
    "basetool-ingest, EVAL return 0, false",
    "basetool-ingest, EVALSHA 0000000000000000000000000000000000000000 0, false",
    "basetool-ingest, FUNCTION LIST, false",
    "monitoring, INFO, true",
    "monitoring, CONFIG GET maxmemory, true",
    "monitoring, SCAN 0, false",
    "monitoring, RANDOMKEY, false",
    "monitoring, GET basetool:session:sessions:x, false",
    "monitoring, CONFIG SET dir /tmp, false",
    "monitoring, KEYS *, false",
    "monitoring, FLUSHALL, false",
    "monitoring, FLUSHDB, false",
    "monitoring, ACL LIST, false",
    "monitoring, SUBSCRIBE basetool:livesync:changed, false",
    "monitoring, CLIENT TRACKING ON BCAST PREFIX basetool:session:, false",
    "monitoring, CLIENT KILL USER basetool-frontend, false",
    "monitoring, CLIENT PAUSE 1000 ALL, false",
    "monitoring, CLIENT LIST, false",
    "monitoring, SLOWLOG GET 10, false",
    "basetool-frontend, CLIENT TRACKING ON BCAST PREFIX basetool:session:, false",
    "basetool-backend, CLIENT TRACKING ON BCAST PREFIX exchange:, false",
    "basetool-ingest, CLIENT TRACKING ON BCAST PREFIX ingest:, false",
    "admin, ACL LOAD, true"
  })
  void theSharedAclMatrixHoldsForEveryUser(String user, String command, boolean allowed) {
    assertDryRun(SHARED, SHARED_ADMIN, user, command, allowed);
  }

  /**
   * Asks one instance whether its ACL lets a user run a command, and asserts the answer.
   *
   * @param redis the instance
   * @param adminPassword the variable holding that instance's admin password
   * @param user the ACL user
   * @param command the command line, space-separated
   * @param allowed whether the ACL must allow it
   */
  private void assertDryRun(
      GenericContainer<?> redis,
      String adminPassword,
      String user,
      String command,
      boolean allowed) {
    StringRedisTemplate admin =
        template(connect(redis, RedisAclTemplate.ADMIN_USER, adminPassword));
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
   * Reads how many commands Redis has refused under its ACL since it started.
   *
   * @param admin a template authenticated as {@code admin}, which may run {@code INFO}.
   * @return {@code INFO stats}' {@code acl_access_denied_cmd}.
   */
  private static long deniedCommands(StringRedisTemplate admin) {
    Properties stats =
        admin.execute(
            (org.springframework.data.redis.core.RedisCallback<Properties>)
                connection -> connection.serverCommands().info("stats"));
    assertThat(stats).isNotNull();
    return Long.parseLong(stats.getProperty("acl_access_denied_cmd", "0").strip());
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
   * Reads a committed file with Unix line ends.
   *
   * @param relative the path relative to the repository root
   * @return its text
   * @throws IOException when it cannot be read
   */
  private static String committed(String relative) throws IOException {
    return Files.readString(RedisAclTemplate.repositoryPath(relative), StandardCharsets.UTF_8)
        .replace("\r\n", "\n");
  }

  /**
   * Connects to an instance as an ACL user.
   *
   * @param redis the instance
   * @param user the ACL user.
   * @param passwordVariable the template variable holding its password.
   * @return a started connection factory, destroyed after the test.
   */
  private LettuceConnectionFactory connect(
      GenericContainer<?> redis, String user, String passwordVariable) {
    return connectWithUsername(redis, user, VALUES.get(passwordVariable));
  }

  /**
   * Connects with a password-only {@code AUTH}, as an application with an empty {@code
   * REDIS_USERNAME} does.
   *
   * @param redis the instance
   * @param password the password
   * @return a started connection factory, destroyed after the test
   */
  private LettuceConnectionFactory connectWithoutUsername(
      GenericContainer<?> redis, String password) {
    return connectWithUsername(redis, null, password);
  }

  /**
   * Connects with the given credentials, the way Spring Boot builds the factory from {@code
   * spring.data.redis.username} / {@code password}.
   *
   * @param redis the instance
   * @param username the username; {@code null} or empty means a password-only {@code AUTH}.
   * @param password the password.
   * @return a started connection factory, destroyed after the test.
   */
  private LettuceConnectionFactory connectWithUsername(
      GenericContainer<?> redis, String username, String password) {
    RedisStandaloneConfiguration configuration =
        new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
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
   * Waits until a listener container's subscriptions are live, failing when they never are.
   *
   * @param container the container
   * @throws InterruptedException when interrupted while waiting
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
