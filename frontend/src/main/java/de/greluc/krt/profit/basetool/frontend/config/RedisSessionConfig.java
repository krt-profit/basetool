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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.FlushMode;
import org.springframework.session.Session;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.config.ConfigureRedisAction;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.validation.AbstractBindingResult;
import org.springframework.validation.Errors;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * Configures Redis-backed Spring Session with a Jackson 3 JSON serializer that preserves the
 * OAuth2/OIDC authentication context.
 *
 * <p>Applies the two-tier session timeout (REQ-SEC-025, ADR-0088): a short anonymous window by
 * default, extended to the authenticated window on login. Inactive in the {@code test} profile.
 */
@Configuration
@EnableRedisIndexedHttpSession
@Profile("!test")
@Slf4j
public class RedisSessionConfig {

  /**
   * Names of final classes the servlet container writes into the session, which get a forced
   * {@code @class} type id via {@link ForcedTypeIdMixin}.
   *
   * <p>Only for types written by a dependency; application code must not write final types.
   */
  static final List<String> CONTAINER_WRITTEN_FINAL_SESSION_TYPES =
      List.of("org.apache.tomcat.websocket.server.WsHttpSessionBindingListener");

  /**
   * The name of Redis's built-in {@code default} user — the one a password-only {@code AUTH} lands
   * on, and the only user an application may authenticate as that is allowed {@code CONFIG}
   * (REQ-SEC-068).
   */
  static final String REDIS_DEFAULT_USER = "default";

  /**
   * Raw {@code app.session.type-allow-list} value (default {@code report}), parsed leniently by
   * {@link SessionTypeAllowList.Mode#parse(String)} (REQ-SEC-067, ADR-0206).
   */
  @Value("${app.session.type-allow-list:report}")
  private String typeAllowListValue;

  /**
   * Whether startup configures Redis keyspace notifications at all ({@code
   * app.session.configure-keyspace-notifications}, default {@code true}).
   *
   * <p>Only the image build's AOT training run switches it off (ADR-0209).
   */
  @Value("${app.session.configure-keyspace-notifications:true}")
  private boolean configureKeyspaceNotifications;

  /**
   * The Redis ACL user the frontend authenticates as ({@code spring.data.redis.username}); selects
   * the startup step in {@link #configureRedisAction()} (REQ-SEC-068, ADR-0207).
   *
   * <p>Empty or {@code default} means the shared {@code default} user.
   */
  @Value("${spring.data.redis.username:}")
  private @Nullable String redisUsername;

  /**
   * Idle timeout for new, anonymous sessions ({@code app.session.anonymous-timeout}, default 30m),
   * applied as the repository default by {@link #sessionRepositoryCustomizer(ObjectProvider)}
   * (REQ-SEC-025, ADR-0088).
   */
  @Value("${app.session.anonymous-timeout:30m}")
  private Duration anonymousSessionTimeout;

  /**
   * Redis key namespace ({@code spring.session.redis.namespace}, default {@code basetool:session}),
   * applied by {@link #sessionRepositoryCustomizer(ObjectProvider)}.
   */
  @Value("${spring.session.redis.namespace:basetool:session}")
  private String redisNamespace;

  /**
   * Raw {@code spring.session.redis.flush-mode} value (default {@code IMMEDIATE}), parsed leniently
   * by {@link #resolveFlushMode()} and applied by {@link
   * #sessionRepositoryCustomizer(ObjectProvider)}.
   */
  @Value("${spring.session.redis.flush-mode:IMMEDIATE}")
  private String flushModeValue;

  /**
   * Provides the Jackson 3 {@link RedisSerializer} for session data, with the Spring Security
   * modules and {@link SessionTypeAllowList}'s type validator (REQ-SEC-067, ADR-0206).
   *
   * @param meterRegistry provider for the registry the session drop and refusal counters bind to
   * @return the configured {@link RedisSerializer}
   */
  @NotNull
  @Bean
  public RedisSerializer<Object> springSessionDefaultRedisSerializer(
      ObjectProvider<MeterRegistry> meterRegistry) {
    SessionTypeAllowList.Mode mode = SessionTypeAllowList.Mode.parse(typeAllowListValue);
    log.info("Session type allow-list mode: {}", mode);
    return new FaultTolerantSessionSerializer(
        new GenericJacksonJsonRedisSerializer(
            buildSessionJsonMapper(
                getClass().getClassLoader(),
                SessionTypeAllowList.validatorBuilder(
                    mode, new SessionTypeAllowList.MeteredRefusalListener(meterRegistry)))),
        meterRegistry);
  }

  /**
   * Builds the session {@link JsonMapper} with the allow-list enforced and refusals not reported.
   *
   * @param loader the class loader the Spring Security modules and container types resolve against
   * @return the configured mapper
   */
  static JsonMapper buildSessionJsonMapper(ClassLoader loader) {
    return buildSessionJsonMapper(
        loader,
        SessionTypeAllowList.validatorBuilder(
            SessionTypeAllowList.Mode.ENFORCE, SessionTypeAllowList.RefusalListener.NONE));
  }

  /**
   * Builds the session {@link JsonMapper}: the given type validator, the Spring Security modules,
   * and the {@link BindingResultMixin} that stops a flashed {@code BindingResult} from recursing.
   *
   * @param loader the class loader the Spring Security modules and container types resolve against
   * @param typeValidator the polymorphic type validator builder, completed by {@code
   *     SecurityJacksonModules}
   * @return the configured mapper
   */
  static JsonMapper buildSessionJsonMapper(
      ClassLoader loader, BasicPolymorphicTypeValidator.@NotNull Builder typeValidator) {
    JsonMapper.Builder builder =
        JsonMapper.builder()
            .addModules(SecurityJacksonModules.getModules(loader, typeValidator))
            .addMixIn(Errors.class, BindingResultMixin.class)
            .addMixIn(AbstractBindingResult.class, BindingResultMixin.class);
    List<String> forced = new ArrayList<>();
    List<String> absent = new ArrayList<>();
    for (String className : CONTAINER_WRITTEN_FINAL_SESSION_TYPES) {
      Class<?> type = resolveIfPresent(className, loader);
      if (type == null) {
        absent.add(className);
      } else {
        builder.addMixIn(type, ForcedTypeIdMixin.class);
        forced.add(className);
      }
    }
    log.info("Session serializer forces an @class type id for: {}", forced);
    if (!absent.isEmpty()) {
      log.warn(
          "Session types {} are not on the classpath, so no forced @class type id is registered for"
              + " them. If the servlet container still writes such a value it will be unreadable on"
              + " the next request and counted in basetool_session_value_dropped_total.",
          absent);
    }
    return builder.build();
  }

  /**
   * Resolves a class by name without initialising it.
   *
   * @param className the fully-qualified class name
   * @param loader the class loader; {@code null} means the bootstrap loader
   * @return the class, or {@code null} when it is not on the classpath
   */
  @Nullable
  private static Class<?> resolveIfPresent(@NotNull String className, ClassLoader loader) {
    try {
      return Class.forName(className, false, loader);
    } catch (ClassNotFoundException | LinkageError ex) {
      log.debug(
          "Session type {} is not on the classpath; its forced type-id mix-in is not registered.",
          className);
      return null;
    }
  }

  /**
   * Jackson mix-in that forces an {@code @class} type id onto a final class the default typing
   * would write without one, so it reads back from Redis.
   *
   * <p>Applied only to {@link #CONTAINER_WRITTEN_FINAL_SESSION_TYPES}.
   */
  @JsonTypeInfo(
      use = JsonTypeInfo.Id.CLASS,
      include = JsonTypeInfo.As.PROPERTY,
      property = "@class")
  abstract static class ForcedTypeIdMixin {}

  /**
   * Jackson mix-in that hides {@link BeanPropertyBindingResult} internals from the session
   * serializer.
   *
   * <p>Ignores {@code model} (self-recursive), {@code propertyAccessor} (non-serialisable JDK
   * internals) and other implementation details; keeps everything {@code th:errors} and {@code
   * th:field} need.
   */
  @JsonIgnoreProperties({
    "model",
    "propertyAccessor",
    "messageCodesResolver",
    "propertyEditorRegistry",
    "suppressedFields",
    "rawFieldValue"
  })
  abstract static class BindingResultMixin {}

  /**
   * Applies the anonymous timeout, namespace and flush mode to the {@link
   * RedisIndexedSessionRepository} and installs {@link SessionAttributeDiagnosticMapper}.
   *
   * <p>The mapper names unreadable session values and returns {@code null} for a hash missing a
   * required field instead of failing the request (REQ-SEC-063, ADR-0186).
   *
   * @param meterRegistry provider for the registry {@code basetool_session_unmappable_total} binds
   *     to
   * @return a customizer setting timeout, namespace, flush mode and the diagnostic mapper
   */
  @Bean
  public SessionRepositoryCustomizer<RedisIndexedSessionRepository> sessionRepositoryCustomizer(
      ObjectProvider<MeterRegistry> meterRegistry) {
    return repository -> {
      repository.setDefaultMaxInactiveInterval(anonymousSessionTimeout);
      repository.setRedisKeyNamespace(redisNamespace);
      repository.setFlushMode(resolveFlushMode());
      repository.setRedisSessionMapper(new SessionAttributeDiagnosticMapper(meterRegistry));
    };
  }

  /**
   * Parses {@link #flushModeValue} into a {@link FlushMode}, ignoring case and treating {@code -}
   * as {@code _}; an unrecognised value logs a warning and yields {@link FlushMode#IMMEDIATE}.
   *
   * @return the configured flush mode, or {@link FlushMode#IMMEDIATE} if the value is unrecognised
   */
  private FlushMode resolveFlushMode() {
    String normalised = flushModeValue.strip().toUpperCase(Locale.ROOT).replace('-', '_');
    try {
      return FlushMode.valueOf(normalised);
    } catch (IllegalArgumentException ex) {
      log.warn(
          "Unrecognised spring.session.redis.flush-mode '{}'; falling back to IMMEDIATE. "
              + "Valid values: IMMEDIATE, ON_SAVE.",
          flushModeValue);
      return FlushMode.IMMEDIATE;
    }
  }

  /**
   * Provides the startup keyspace-notification step that fits the configured Redis user
   * (REQ-SEC-068, ADR-0207); see {@link #selectConfigureRedisAction(boolean, String)}.
   *
   * @return the action for {@link #configureKeyspaceNotifications} and {@link #redisUsername}
   */
  @NotNull
  @Bean
  public ConfigureRedisAction configureRedisAction() {
    return selectConfigureRedisAction(configureKeyspaceNotifications, redisUsername);
  }

  /**
   * Selects Spring Session's startup step from configuration.
   *
   * <ul>
   *   <li>Disabled: {@link ConfigureRedisAction#NO_OP}, opening no Redis connection (ADR-0209).
   *   <li>The {@code default} user (no username or exactly {@code default}): {@link
   *       TolerantKeyspaceNotificationsAction}.
   *   <li>A per-service ACL user: {@link ServerConfiguredKeyspaceNotificationsAction}, which sends
   *       only a {@code PING}.
   * </ul>
   *
   * @param enabled {@code app.session.configure-keyspace-notifications}
   * @param username {@code spring.data.redis.username}; {@code null} or blank means none
   * @return the action Spring Session runs once at startup
   */
  @NotNull
  static ConfigureRedisAction selectConfigureRedisAction(
      boolean enabled, @Nullable String username) {
    if (!enabled) {
      return ConfigureRedisAction.NO_OP;
    }
    String user = username == null ? "" : username.strip();
    if (user.isEmpty() || REDIS_DEFAULT_USER.equals(user)) {
      return new TolerantKeyspaceNotificationsAction();
    }
    return new ServerConfiguredKeyspaceNotificationsAction(user);
  }

  /**
   * Stores {@code OAuth2AuthorizedClient}s in the Redis-backed HTTP session so tokens survive
   * frontend restarts.
   *
   * <p>Wrapped in {@link CurrentRegistrationAuthorizedClientRepository}, so the stored client never
   * holds the client secret and always refreshes with the current registration (REQ-SEC-069).
   *
   * @param clientRegistrationRepository the current client registrations
   * @return the session-backed {@link OAuth2AuthorizedClientRepository}
   */
  @NotNull
  @Bean
  public OAuth2AuthorizedClientRepository authorizedClientRepository(
      ClientRegistrationRepository clientRegistrationRepository) {
    return new CurrentRegistrationAuthorizedClientRepository(
        new HttpSessionOAuth2AuthorizedClientRepository(), clientRegistrationRepository);
  }

  /**
   * Backs Spring Security's {@code maximumSessions} control with the Redis principal-name index, so
   * the session cap is enforced across restarts.
   *
   * @param sessionRepository the Redis-indexed session repository
   * @param <S> the session type managed by the repository
   * @return a session registry backed by the Redis session store
   */
  @NotNull
  @Bean
  public <S extends Session> SpringSessionBackedSessionRegistry<S> sessionRegistry(
      FindByIndexNameSessionRepository<S> sessionRepository) {
    return new SpringSessionBackedSessionRegistry<>(sessionRepository);
  }
}
