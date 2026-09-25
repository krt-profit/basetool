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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.FlushMode;
import org.springframework.session.Session;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.config.ConfigureRedisAction;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regression coverage for the Redis-session {@link JsonMapper} configuration in {@link
 * RedisSessionConfig}. The original bug was a 500 surfacing on POST {@code /personal-inventory/add}
 * whenever the submitted form failed validation: the page controller pushed the {@link
 * BeanPropertyBindingResult} into a {@code RedirectAttributes} flash attribute, the redirect commit
 * serialised the FlashMap to Redis and Jackson exploded on the {@code BindingResult -> model ->
 * BindingResult -> ...} self-reference cycle with {@code Document nesting depth (501) exceeds the
 * maximum (500)}.
 *
 * <p>{@link RedisSessionConfig#buildSessionJsonMapper(ClassLoader)} now installs a Jackson mix-in
 * that hides {@code BindingResult.getModel()} from the serialiser, breaking the cycle without
 * dropping the field errors / target / object-name. These tests pin that behaviour so it cannot
 * silently regress when the configuration is touched in the future.
 *
 * <p>Also pins {@link RedisSessionConfig#sessionRepositoryCustomizer(ObjectProvider)}: the
 * configurable {@code spring.session.redis.flush-mode} must bind leniently (case- and {@code
 * -}/{@code _}-insensitive) and fall back to the durable {@code IMMEDIATE} default on an
 * unrecognised value rather than crashing startup, while the session timeout and key namespace stay
 * applied.
 */
class RedisSessionConfigTest {

  private final JsonMapper mapper =
      RedisSessionConfig.buildSessionJsonMapper(getClass().getClassLoader());

  @Test
  void bindingResultIsSerialisedWithoutTheSelfReferencingModelProperty() {
    SampleForm form = new SampleForm();
    form.setName("");
    BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(form, "sampleForm");
    bindingResult.rejectValue("name", "NotBlank", "must not be blank");

    Map<String, Object> flashAttributes = new HashMap<>();
    flashAttributes.put(BindingResult.MODEL_KEY_PREFIX + "sampleForm", bindingResult);
    flashAttributes.put("sampleForm", form);

    String json = mapper.writeValueAsString(flashAttributes);

    assertThat(json)
        .as("Mix-in must strip the synthesised `model` property that re-contains the BindingResult")
        .doesNotContain("\"model\"");
    assertThat(json)
        .as("Field errors must survive — th:errors needs them after the FlashMap round-trip")
        .contains("NotBlank")
        .contains("must not be blank");
    assertThat(json)
        .as("Object name must survive so Spring can re-attach the BindingResult to the right model")
        .contains("sampleForm");
  }

  /**
   * The flush mode is resolved leniently: any case and {@code -}/{@code _} spelling of a valid
   * constant binds, and an unrecognised value degrades to the durable {@code IMMEDIATE} default.
   * The lowercase {@code on_save} case is the load-bearing one — it is the spelling Spring's own
   * docs use, and a direct {@code @Value FlushMode} binding would crash startup on it.
   *
   * @param configured the raw {@code spring.session.redis.flush-mode} value
   * @param expected the {@link FlushMode} the customizer must apply to the repository
   */
  @ParameterizedTest
  @CsvSource({
    "IMMEDIATE,IMMEDIATE",
    "ON_SAVE,ON_SAVE",
    "on_save,ON_SAVE",
    "on-save,ON_SAVE",
    "Immediate,IMMEDIATE",
    "bogus,IMMEDIATE"
  })
  void customizerResolvesFlushModeLeniently(String configured, FlushMode expected) {
    RedisIndexedSessionRepository repository = applyCustomizer(configured);
    verify(repository).setFlushMode(expected);
  }

  /**
   * The customizer applies the short <em>anonymous</em> idle window (REQ-SEC-025, ADR-0088) as the
   * repository default — a real login later promotes its session to the long authenticated window
   * in {@code SessionLifetimeUpgradeSuccessHandler} — plus the Redis key namespace.
   */
  @Test
  void customizerAppliesAnonymousTimeoutAndNamespace() {
    RedisIndexedSessionRepository repository = applyCustomizer("IMMEDIATE");
    verify(repository).setDefaultMaxInactiveInterval(Duration.ofMinutes(30));
    verify(repository).setRedisKeyNamespace("basetool:session");
  }

  /**
   * Security audit gap-fill: the concurrent-session cap ({@code maximumSessions}) must be backed by
   * the Redis session store, because with {@code @EnableRedisIndexedHttpSession} the default
   * in-memory registry never sees the (Spring-Session-owned) sessions. The config exposes a {@link
   * SpringSessionBackedSessionRegistry} built from the Redis {@link
   * FindByIndexNameSessionRepository}.
   */
  @Test
  void sessionRegistryIsBackedByTheRedisSessionRepository() {
    RedisSessionConfig config = new RedisSessionConfig();
    @SuppressWarnings("unchecked")
    FindByIndexNameSessionRepository<Session> repository =
        mock(FindByIndexNameSessionRepository.class);

    SpringSessionBackedSessionRegistry<Session> registry = config.sessionRegistry(repository);

    assertThat(registry).isNotNull();
  }

  /**
   * With {@code app.session.configure-keyspace-notifications} at its default the startup action is
   * the ACL-tolerant one (REQ-SEC-068), which still runs Spring Session's {@code CONFIG} call and
   * still fails the startup on an unreachable Redis (ADR-0084).
   */
  @Test
  void keyspaceActionIsTheTolerantOneByDefault() {
    RedisSessionConfig config = new RedisSessionConfig();
    ReflectionTestUtils.setField(config, "configureKeyspaceNotifications", true);

    assertThat(config.configureRedisAction())
        .isInstanceOf(TolerantKeyspaceNotificationsAction.class);
  }

  /**
   * Switched off -- only the image build's AOT training run does that (IMG-PERF-12) -- the action
   * is Spring Session's {@code NO_OP}, so the context refresh opens no Redis connection at all.
   */
  @Test
  void keyspaceActionIsNoOpWhenSwitchedOff() {
    RedisSessionConfig config = new RedisSessionConfig();
    ReflectionTestUtils.setField(config, "configureKeyspaceNotifications", false);

    assertThat(config.configureRedisAction()).isSameAs(ConfigureRedisAction.NO_OP);
  }

  /**
   * The AOT switch wins over the username: switched off, even a per-service ACL user gets {@code
   * NO_OP}, so the training run opens no connection whatever {@code REDIS_USERNAME} says.
   */
  @Test
  void keyspaceActionIsNoOpWhenSwitchedOffUnderANamedUserToo() {
    RedisSessionConfig config = new RedisSessionConfig();
    ReflectionTestUtils.setField(config, "configureKeyspaceNotifications", false);
    ReflectionTestUtils.setField(config, "redisUsername", "basetool-frontend");

    assertThat(config.configureRedisAction()).isSameAs(ConfigureRedisAction.NO_OP);
  }

  /**
   * Under the shared {@code default} user — no username, a blank one, or {@code default} spelled
   * out — the startup step is the pre-rollout one, and it still sends Spring Session's {@code
   * CONFIG GET notify-keyspace-events} (and no {@code PING}).
   *
   * @param username the configured {@code spring.data.redis.username}; {@code <null>} is unset.
   */
  @ParameterizedTest(name = "username=[{0}]")
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "default", " default "})
  void underTheSharedDefaultUserTheConfigPathIsUnchanged(String username) {
    RedisSessionConfig config = new RedisSessionConfig();
    ReflectionTestUtils.setField(config, "configureKeyspaceNotifications", true);
    ReflectionTestUtils.setField(config, "redisUsername", username);
    RedisConnection connection = mock(RedisConnection.class);
    RedisServerCommands server = mock(RedisServerCommands.class);
    when(connection.serverCommands()).thenReturn(server);
    Properties current = new Properties();
    current.setProperty("notify-keyspace-events", "Egx");
    when(server.getConfig("notify-keyspace-events")).thenReturn(current);

    ConfigureRedisAction action = config.configureRedisAction();
    action.configure(connection);

    assertThat(action).isInstanceOf(TolerantKeyspaceNotificationsAction.class);
    verify(server).getConfig("notify-keyspace-events");
    verify(connection, never()).ping();
  }

  /**
   * The 2026-09-25 production finding: under the frontend's own ACL user the startup step must send
   * no {@code CONFIG} at all — every refused {@code CONFIG GET} is counted by Redis and fed {@code
   * RedisAclDenials} on each restart — and must still prove the store answers, with a {@code PING}
   * its {@code +@connection} allows.
   *
   * @param username a per-service ACL user, including one a relaxed {@code .env} padded.
   */
  @ParameterizedTest(name = "username=[{0}]")
  @ValueSource(strings = {"basetool-frontend", " basetool-frontend ", "Default"})
  void underANamedAclUserNoConfigIsSentButAPingIs(String username) {
    RedisSessionConfig config = new RedisSessionConfig();
    ReflectionTestUtils.setField(config, "configureKeyspaceNotifications", true);
    ReflectionTestUtils.setField(config, "redisUsername", username);
    RedisConnection connection = mock(RedisConnection.class);
    when(connection.ping()).thenReturn("PONG");
    RedisServerCommands server = mock(RedisServerCommands.class);
    when(connection.serverCommands()).thenReturn(server);
    Properties current = new Properties();
    current.setProperty("notify-keyspace-events", "Egx");
    when(server.getConfig("notify-keyspace-events")).thenReturn(current);

    ConfigureRedisAction action = config.configureRedisAction();
    action.configure(connection);

    verify(connection, never()).serverCommands();
    verify(connection).ping();
    verifyNoMoreInteractions(connection, server);
    assertThat(action).isInstanceOf(ServerConfiguredKeyspaceNotificationsAction.class);
  }

  /**
   * Instantiates {@link RedisSessionConfig} with the given flush-mode value (plus fixed timeout and
   * namespace), runs its {@link
   * RedisSessionConfig#sessionRepositoryCustomizer(org.springframework.beans.factory.ObjectProvider)}
   * against a mock repository, and returns that mock for verification.
   *
   * @param flushModeValue the raw {@code spring.session.redis.flush-mode} value to inject
   * @return the mock repository the customizer was applied to
   */
  private static RedisIndexedSessionRepository applyCustomizer(String flushModeValue) {
    RedisSessionConfig config = new RedisSessionConfig();
    ReflectionTestUtils.setField(config, "anonymousSessionTimeout", Duration.ofMinutes(30));
    ReflectionTestUtils.setField(config, "redisNamespace", "basetool:session");
    ReflectionTestUtils.setField(config, "flushModeValue", flushModeValue);
    SessionRepositoryCustomizer<RedisIndexedSessionRepository> customizer =
        config.sessionRepositoryCustomizer(meterRegistryProvider());
    RedisIndexedSessionRepository repository = mock(RedisIndexedSessionRepository.class);
    customizer.customize(repository);
    return repository;
  }

  /**
   * Builds the {@code ObjectProvider<MeterRegistry>} the customizer hands to the session mapper.
   *
   * <p>Backed by a real bean factory rather than a stub, so the lazy {@code getIfAvailable()}
   * resolution the production wiring depends on is the one exercised here.
   *
   * @return a provider over a throwaway {@link SimpleMeterRegistry}
   */
  private static org.springframework.beans.factory.ObjectProvider<MeterRegistry>
      meterRegistryProvider() {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerSingleton("meterRegistry", new SimpleMeterRegistry());
    return beanFactory.getBeanProvider(MeterRegistry.class);
  }

  /**
   * Simple form bean with a single property. We intentionally avoid one of the real frontend form
   * classes here so the test does not depend on their evolving validation annotations — the cycle
   * this test reproduces lives in Spring's {@code BeanPropertyBindingResult}, not in our forms.
   */
  @SuppressWarnings("unused")
  @Getter
  @Setter
  public static class SampleForm {
    private String name;
  }
}
