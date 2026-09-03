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
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.FlushMode;
import org.springframework.session.Session;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.validation.AbstractBindingResult;
import org.springframework.validation.Errors;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * Configures Spring Session with a Jackson 3 based JSON serializer for Redis.
 *
 * <p>By default, Spring Session uses Java serialization for storing session objects in Redis. This
 * fails silently for OAuth2/OIDC types ({@code OidcUser}, {@code OAuth2AuthorizedClient}, etc.) in
 * Spring Security 7.x / Spring Boot 4.x, resulting in an empty Redis store despite a successful
 * login — the session is never actually persisted.
 *
 * <p>This configuration switches to Jackson 3 JSON serialization using {@link
 * GenericJacksonJsonRedisSerializer} with all required Spring Security and OAuth2 modules
 * registered via {@link SecurityJacksonModules}. This ensures that the full authentication context
 * (including {@code OidcUser}, {@code OAuth2AuthorizedClient}, and tokens) is correctly serialized
 * to and deserialized from Redis across frontend restarts.
 *
 * <p>Uses {@code @EnableRedisIndexedHttpSession} to enable Redis-backed HTTP sessions with index
 * support for session lookup by principal name and session ID.
 *
 * <p><b>Two-tier session timeout (REQ-SEC-025, ADR-0088):</b>
 * {@code @EnableRedisIndexedHttpSession} disables Spring Boot's auto-configuration bridge that
 * would normally apply {@code server.servlet.session.timeout} to the {@link
 * RedisIndexedSessionRepository}; without it the repository default of 1800 s (30 min) applies. A
 * {@link SessionRepositoryCustomizer} bean instead applies the <em>anonymous</em> window ({@code
 * app.session.anonymous-timeout}, default 30m) as the repository default, so throwaway sessions
 * minted for un-authenticated traffic (CSRF-token / pre-login OAuth2 state) expire in minutes. A
 * successful login promotes its session to the long <em>authenticated</em> window ({@code
 * app.session.authenticated-timeout}, default 720h) in {@code
 * SessionLifetimeUpgradeSuccessHandler}. The split stopped the {@code basetool_active_sessions}
 * runaway (&gt;16 000 orphan CSRF-only sessions against ~30 real principals) without shortening the
 * 30-day "stay logged in" window members rely on.
 *
 * <p>This configuration is excluded from the {@code test} profile to prevent Redis connection
 * attempts during unit and integration tests.
 */
@Configuration
@EnableRedisIndexedHttpSession
@Profile("!test")
@Slf4j
public class RedisSessionConfig {

  /**
   * Fully-qualified names of <strong>final</strong> classes that the servlet container itself
   * writes into the HTTP session, and which therefore need a forced {@code @class} type id to
   * survive a round trip through Redis.
   *
   * <p>Held as names rather than as types on purpose — see {@link #resolveIfPresent} — and
   * deliberately short. This is not a place to work around this application's own code: a value
   * <em>we</em> write is fixed by not writing a final type in the first place (wrap it, exactly as
   * {@code BackendRoleSyncFilter}'s {@code new ArrayList<>(…)} does). Only a value written by a
   * dependency, where there is no such seam, belongs here.
   *
   * <ul>
   *   <li>{@code org.apache.tomcat.websocket.server.WsHttpSessionBindingListener} — a {@code
   *       record} added in Tomcat 11.0.25, written on every authenticated WebSocket handshake. See
   *       {@link ForcedTypeIdMixin} for the whole failure and why this is the fix.
   * </ul>
   */
  private static final List<String> CONTAINER_WRITTEN_FINAL_SESSION_TYPES =
      List.of("org.apache.tomcat.websocket.server.WsHttpSessionBindingListener");

  /**
   * <em>Anonymous</em> session idle timeout read from {@code app.session.anonymous-timeout}
   * (default 30m), applied as the repository's default {@code maxInactiveInterval} (REQ-SEC-025,
   * ADR-0088).
   *
   * <p>Deliberately short: every NEW session starts with this window, including the throwaway
   * sessions Spring Security mints for un-authenticated traffic — the CSRF-token session created
   * when an anonymous client renders a form-bearing permit-all page, and pre-login OAuth2 {@code
   * authorizationRequest} state. Those orphans must expire in minutes; leaving them at the 30-day
   * "stay logged in" window let anonymous probe/crawler traffic accrete &gt;16 000 orphan CSRF-only
   * sessions in Redis against ~30 real principals (the {@code basetool_active_sessions} runaway),
   * on a collision course with the {@code maxmemory noeviction} ceiling. A real login promotes its
   * session to the long {@code app.session.authenticated-timeout} window in {@code
   * SessionLifetimeUpgradeSuccessHandler}, so members keep the 30-day window unchanged.
   *
   * <p>{@code @EnableRedisIndexedHttpSession} disables Spring Boot's auto-configuration bridge, so
   * this value is NOT applied automatically; without it the repository default of 1800 s (30 min)
   * would apply. Injected here and applied via {@link #sessionRepositoryCustomizer()}.
   */
  @Value("${app.session.anonymous-timeout:30m}")
  private Duration anonymousSessionTimeout;

  /**
   * Redis key namespace read from {@code spring.session.redis.namespace} (default: {@code
   * basetool:session}).
   *
   * <p>{@code @EnableRedisIndexedHttpSession} bypasses Spring Boot's auto-configuration, so {@code
   * spring.session.redis.namespace} from {@code application.yml} is NOT automatically applied to
   * the {@link RedisIndexedSessionRepository}. Without explicit configuration the default namespace
   * {@code spring:session} is used — sessions are stored under {@code spring:session:*} keys, not
   * under the configured {@code basetool:session:*} keys. This field is injected here and applied
   * via {@link #sessionRepositoryCustomizer()}.
   */
  @Value("${spring.session.redis.namespace:basetool:session}")
  private String redisNamespace;

  /**
   * Raw {@code spring.session.redis.flush-mode} string (default {@code IMMEDIATE}), turned into a
   * {@link FlushMode} by {@link #resolveFlushMode()} and applied via {@link
   * #sessionRepositoryCustomizer()}.
   *
   * <p>{@code @EnableRedisIndexedHttpSession} bypasses Spring Boot's auto-configuration, so this
   * property is NOT applied to the {@link RedisIndexedSessionRepository} automatically. The default
   * stays {@code IMMEDIATE} — every session mutation is written to Redis the moment it is applied,
   * so a frontend crash mid-request never loses an already-applied change (the conservative
   * durability the OAuth2 login / token-refresh flow relies on). Operators who would rather trade
   * that for fewer Redis round-trips on session-heavy requests can set {@code ON_SAVE}, which
   * defers the write to request completion; the writes that matter (rotated tokens, flash
   * attributes) still land before the response commits.
   *
   * <p>Bound as a {@link String} rather than a {@link FlushMode} on purpose. {@code @Value} enum
   * conversion is case-sensitive ({@code Enum.valueOf}; Spring's relaxed binding applies only to
   * {@code @ConfigurationProperties}), and {@code application-prod.yml} already ships {@code
   * flush-mode: immediate} in lowercase — a direct {@code FlushMode} binding would reject that
   * value and crash prod startup. {@link #resolveFlushMode()} parses the value leniently (case- and
   * {@code -}/{@code _}-insensitive) and falls back to the durable {@code IMMEDIATE} default (with
   * a warning) on an unrecognised value.
   */
  @Value("${spring.session.redis.flush-mode:IMMEDIATE}")
  private String flushModeValue;

  /**
   * Provides a Jackson 3 based {@link RedisSerializer} for Spring Session.
   *
   * <p>Uses {@link SecurityJacksonModules#getModules(ClassLoader)} which automatically registers
   * all Spring Security modules found on the classpath, including:
   *
   * <ul>
   *   <li>{@code CoreJacksonModule} — SecurityContext, Authentication, GrantedAuthority
   *   <li>{@code OAuth2ClientJacksonModule} — OidcUser, OAuth2AuthorizedClient, tokens
   *   <li>{@code WebJacksonModule}, {@code WebServletJacksonModule} — web types
   * </ul>
   *
   * <p>A custom {@link BasicPolymorphicTypeValidator} with {@code allowIfBaseType(Object.class)}
   * permits any class that is a subtype of {@code Object} (effectively all Java classes). This is
   * required because Spring Session serializes heterogeneous types — {@code Long}, {@code HashMap},
   * {@code Instant}, {@code OidcUser}, {@code OAuth2AuthorizedClient}, etc. In Jackson 3, {@code
   * allowIfSubType(String)} does NOT match by name prefix; it checks class hierarchy. Therefore,
   * package-prefix strings like {@code "java.lang."} do NOT match final classes such as {@code
   * Long}. The {@code allowIfBaseType(Object.class)} approach is safe because session data
   * originates only from our own application and Keycloak. The builder is passed to {@link
   * SecurityJacksonModules#getModules(ClassLoader, BasicPolymorphicTypeValidator.Builder)} which
   * extends it with all required Spring Security type allowances.
   *
   * @param meterRegistry provider for the registry {@code basetool_session_value_dropped_total}
   *     binds to. An {@link ObjectProvider} rather than the registry itself: this bean is consumed
   *     by the configuration {@code @EnableRedisIndexedHttpSession} imports onto this very class,
   *     and a hard dependency would drag Micrometer's auto-configuration into session-repository
   *     creation.
   * @return the configured {@link RedisSerializer} for session data
   */
  @Bean
  public RedisSerializer<Object> springSessionDefaultRedisSerializer(
      ObjectProvider<MeterRegistry> meterRegistry) {
    // Wrapped, because the read path has no error handling of its own: a value that cannot be
    // deserialized leaves RedisIndexedSessionRepository uncaught and becomes an HTTP 500 on every
    // request carrying a session cookie. That is the 2026-09-02 outage. The wrapper turns an
    // unreadable ATTRIBUTE into "not set", i.e. a signed-out member; it cannot mask a bad write,
    // and it cannot hide the required timestamps, which are final types and never fail. See
    // FaultTolerantSessionSerializer for why that is both safe and sufficient.
    return new FaultTolerantSessionSerializer(
        new GenericJacksonJsonRedisSerializer(buildSessionJsonMapper(getClass().getClassLoader())),
        meterRegistry);
  }

  /**
   * Build the {@link JsonMapper} used by {@link #springSessionDefaultRedisSerializer()}. Extracted
   * into a package-private static factory so the configuration of polymorphic-type handling, the
   * Spring Security / OAuth2 modules and the {@link BindingResultMixin} stays unit-testable without
   * bootstrapping the full Spring application context.
   *
   * <p>Configuration notes:
   *
   * <ul>
   *   <li><b>Type validator:</b> Jackson 3's {@code BasicPolymorphicTypeValidator
   *       .allowIfSubType(String)} checks the class hierarchy for a class/interface with the given
   *       fully-qualified name; it does NOT match by package prefix the way the Jackson 2 default
   *       validator did. {@code "java.lang."} therefore would NOT match {@code java.lang.Long} (a
   *       final class with no parent of that name). Spring Session serialises many heterogeneous
   *       types — {@code Long}, {@code HashMap}, {@code Instant}, {@code OidcUser}, {@code
   *       OAuth2AuthorizedClient} — so we use {@code allowIfBaseType(Object.class)}: every class is
   *       a subtype of {@code Object}. Safe because session data originates from our own code plus
   *       Keycloak's OAuth2 flow, not from user-controlled input.
   *   <li><b>Spring Security modules:</b> {@code SecurityJacksonModules.getModules(loader, …)}
   *       registers {@code CoreJacksonModule} (SecurityContext / Authentication /
   *       GrantedAuthority), {@code OAuth2ClientJacksonModule} (OidcUser, tokens, authorized
   *       clients), {@code WebJacksonModule} / {@code WebServletJacksonModule} and any other Spring
   *       Security module found on the classpath. Without these the session writes silently lose
   *       the authentication context on the next read.
   *   <li><b>BindingResult mix-in:</b> page controllers across the frontend push form {@code
   *       BindingResult}s into {@code RedirectAttributes} flash attributes when validation fails
   *       (PRG pattern). {@code BeanPropertyBindingResult.getModel()} returns a {@code
   *       LinkedHashMap} that re-contains the BindingResult itself, so naive serialisation recurses
   *       {@code BindingResult -> model -> BindingResult -> ...} until Jackson trips its {@code
   *       Document nesting depth (500)} guard and the session commit fails with HTTP 500. The
   *       mix-in hides the synthesised {@code model} property from serialisation; field errors, the
   *       bound target object, the object name and the nested-path are all preserved so {@code
   *       th:errors} / {@code th:field} still work on the next render. Mix-in is registered on both
   *       the {@link Errors} interface and the {@link AbstractBindingResult} base class so every
   *       concrete BindingResult subtype inherits it regardless of Jackson's resolution order.
   * </ul>
   */
  static JsonMapper buildSessionJsonMapper(ClassLoader loader) {
    BasicPolymorphicTypeValidator.Builder typeValidatorBuilder =
        BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class);
    JsonMapper.Builder builder =
        JsonMapper.builder()
            .addModules(SecurityJacksonModules.getModules(loader, typeValidatorBuilder))
            .addMixIn(Errors.class, BindingResultMixin.class)
            .addMixIn(AbstractBindingResult.class, BindingResultMixin.class);
    for (String className : CONTAINER_WRITTEN_FINAL_SESSION_TYPES) {
      Class<?> type = resolveIfPresent(className, loader);
      if (type != null) {
        builder.addMixIn(type, ForcedTypeIdMixin.class);
      }
    }
    return builder.build();
  }

  /**
   * Resolves a class by name without initialising it, answering {@code null} when it is absent.
   *
   * <p>{@link #CONTAINER_WRITTEN_FINAL_SESSION_TYPES} names servlet-container internals, and an
   * internal is exactly the kind of class that appears in one patch release and moves in the next —
   * {@code WsHttpSessionBindingListener} did not exist before Tomcat 11.0.25. Referencing it by
   * type would tie compilation of the frontend to one container at one version, and would turn an
   * emergency Tomcat downgrade into a build failure at the worst possible moment. Resolving by name
   * costs one lookup at startup and degrades to "no mix-in", which is exactly the behaviour on a
   * container that never writes the attribute.
   *
   * @param className the fully-qualified class name to look up.
   * @param loader the class loader to resolve against; {@code null} means the bootstrap loader.
   * @return the class, or {@code null} when it is not on the classpath.
   */
  @Nullable
  private static Class<?> resolveIfPresent(@NotNull String className, ClassLoader loader) {
    try {
      // Not initialised: a mix-in registration needs the Class object, never the class's state.
      return Class.forName(className, false, loader);
    } catch (ClassNotFoundException | LinkageError ex) {
      log.debug(
          "Session type {} is not on the classpath; its forced type-id mix-in is not registered.",
          className);
      return null;
    }
  }

  /**
   * Jackson mix-in that forces an {@code @class} type id onto a class the default typing would
   * write without one.
   *
   * <p><strong>The production defect this closes (2026-09-03).</strong> Tomcat 11.0.25 added {@code
   * org.apache.tomcat.websocket.server.WsHttpSessionBindingListener} and {@code
   * WsServerContainer#registerAuthenticatedSession} puts it into the {@code HttpSession} on every
   * authenticated WebSocket handshake — which for this application is every logged-in page, because
   * live sync opens {@code /ws/sync}. It is a {@code record}, hence implicitly
   * <strong>final</strong>, and the {@code NON_FINAL} default typing {@code SecurityJacksonModules}
   * activates writes a final type as a JSON object with no {@code @class}. The reader then demands
   * the type id it was never given, so the value was unreadable on the very next request: {@code
   * InvalidTypeIdException}, {@code typeId=absent}. Tomcat re-wrote it on the next handshake, so
   * the rate never decayed — {@code SessionValueDropsSustained} fired at ~70 drops per 15 min.
   *
   * <p><strong>Why forcing the id, rather than suppressing the attribute.</strong> An explicit
   * {@code @JsonTypeInfo} beats the default typing at {@code createTypeSerializer} time, so the
   * value is written as {@code {"@class": "…", "key": "…"}} — byte-identical in shape to every
   * non-final session value — and reads back. Nothing has to be intercepted on the session write
   * path, which is the subsystem that took the whole application down twice inside two releases,
   * and a poisoned session heals itself: the drop leaves the attribute unset, so Tomcat's very next
   * handshake writes a readable one over it.
   *
   * <p><strong>This is a targeted allow-list, not a policy change.</strong> Only the classes named
   * in {@link #CONTAINER_WRITTEN_FINAL_SESSION_TYPES} get it. Widening the default typing to cover
   * every final type would put a type id on {@code creationTime}, {@code lastAccessedTime} and
   * {@code maxInactiveInterval} as well — the three keys Spring Session requires and the ones whose
   * loss throws {@code IllegalStateException: creationTime key must not be null} — and would do it
   * to every live session at once. {@code SessionSerializerRoundTripTest} pins both halves: this
   * class round-trips, and a plain record still does not.
   */
  @JsonTypeInfo(
      use = JsonTypeInfo.Id.CLASS,
      include = JsonTypeInfo.As.PROPERTY,
      property = "@class")
  abstract static class ForcedTypeIdMixin {}

  /**
   * Jackson mix-in that hides internal {@link BeanPropertyBindingResult} properties from the
   * session serialiser. Three classes of problem to suppress:
   *
   * <ol>
   *   <li>{@code model} — re-contains the BindingResult itself, causes the {@code Document nesting
   *       depth (500)} crash described in {@link #buildSessionJsonMapper(ClassLoader)}.
   *   <li>{@code propertyAccessor} — exposes a {@code BeanWrapperImpl} whose {@code
   *       propertyDescriptors} drag in {@code java.lang.reflect.Method} and the {@code
   *       sun.reflect.annotation.AnnotatedTypeFactory$*} non-public JDK internals, which Jackson
   *       cannot serialise under the JPMS access rules (fails with {@code class is not public:
   *       sun.reflect.annotation.…/invokeSpecial}).
   *   <li>The rest (message-codes resolver, property-editor registry, suppressed-fields, raw field
   *       value lookups) is implementation detail that the Thymeleaf re-render path does not need
   *       and that pulls more internals into the serialised graph.
   * </ol>
   *
   * <p>Whitelisted (i.e. NOT ignored): {@code objectName}, {@code nestedPath}, {@code target},
   * {@code fieldErrors}, {@code globalErrors}, {@code allErrors}, {@code errorCount} — everything
   * {@code th:errors} / {@code th:field} consume on the next render.
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
   * Applies the configured session timeout, namespace, and flush mode to the {@link
   * RedisIndexedSessionRepository}.
   *
   * <p>When using {@code @EnableRedisIndexedHttpSession}, Spring Boot's auto-configuration bridge
   * is bypassed and none of the following properties are applied automatically:
   *
   * <ul>
   *   <li>{@code app.session.anonymous-timeout} → default 1800s (30 min) instead of the configured
   *       anonymous window (REQ-SEC-025)
   *   <li>{@code spring.session.redis.namespace} → default {@code spring:session} instead of
   *       configured {@code basetool:session}; causes {@code keys "basetool:session:*"} to return
   *       empty even when sessions are correctly persisted
   *   <li>{@code spring.session.redis.flush-mode} → default {@code ON_SAVE} instead of the {@code
   *       IMMEDIATE} this application wants; see {@link #flushModeValue} for the durability
   *       rationale and the {@code ON_SAVE} opt-out
   * </ul>
   *
   * <p>This customizer explicitly re-applies all three settings; the flush mode is resolved from
   * {@link #flushModeValue} by {@link #resolveFlushMode()} (configurable, defaulting to {@code
   * IMMEDIATE}).
   *
   * <p>It also installs {@link SessionAttributeDiagnosticMapper} in place of the default {@code
   * RedisSessionMapper}. That mapper is the only layer that sees both the failure's shape and the
   * hash field it came from, so it is where an unreadable value is named — see its Javadoc for why
   * the 2026-09-02 storm could not be acted on without that name. Note the repository also invokes
   * the mapper from {@code onMessage} with a session-<em>created</em> payload rather than a stored
   * hash; that map is deserialized in a single serializer call, so it can never contain an {@link
   * UnreadableSessionValue}, and a brand-new session has nothing stale in it to be poisoned by.
   *
   * @return a customizer that sets timeout, namespace, flush mode and the diagnostic session mapper
   */
  @Bean
  public SessionRepositoryCustomizer<RedisIndexedSessionRepository> sessionRepositoryCustomizer() {
    return repository -> {
      repository.setDefaultMaxInactiveInterval(anonymousSessionTimeout);
      repository.setRedisKeyNamespace(redisNamespace);
      repository.setFlushMode(resolveFlushMode());
      repository.setRedisSessionMapper(new SessionAttributeDiagnosticMapper());
    };
  }

  /**
   * Parses {@link #flushModeValue} into a {@link FlushMode} leniently.
   *
   * <p>Accepts any case and treats {@code -} as {@code _}, so {@code on_save}, {@code ON-SAVE} and
   * {@code on-save} all resolve to {@link FlushMode#ON_SAVE}. An unrecognised value is logged at
   * {@code WARN} and resolved to the durable {@link FlushMode#IMMEDIATE} default rather than
   * failing application startup — a mistyped session-durability flag should degrade to the safe
   * behaviour, not crash the frontend.
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
   * Stores {@code OAuth2AuthorizedClient} (access token, refresh token, client registration) in the
   * HTTP session (backed by Redis) instead of the default in-memory store.
   *
   * <p>This ensures that OAuth2 tokens survive frontend restarts: the tokens are persisted in Redis
   * alongside the session and restored transparently on the next request, enabling automatic token
   * refresh without user interaction.
   *
   * <p>Placed here (not in {@link SecurityConfig}) to avoid a circular bean dependency: {@code
   * SecurityConfig} → {@code BackendRoleSyncFilter} → {@code BackendApiClient} → {@code WebClient}
   * → {@code authorizedClientManager} → {@code OAuth2AuthorizedClientRepository} → {@code
   * SecurityConfig}.
   *
   * @return the session-backed {@link OAuth2AuthorizedClientRepository}
   */
  @Bean
  public OAuth2AuthorizedClientRepository authorizedClientRepository() {
    return new HttpSessionOAuth2AuthorizedClientRepository();
  }

  /**
   * Backs Spring Security's concurrent-session control ({@code maximumSessions}) with the
   * Redis-indexed session store instead of the default in-memory {@code SessionRegistryImpl}.
   *
   * <p>With {@code @EnableRedisIndexedHttpSession} the HTTP session is owned by Spring Session, not
   * the servlet container, so the container never fires the {@code HttpSessionListener} events that
   * {@code HttpSessionEventPublisher} → {@code SessionRegistryImpl} depend on — the in-memory
   * registry stays empty and {@code maximumSessions} silently no-ops (security audit gap-fill: the
   * earlier M-14 wiring never actually functioned with Redis-backed sessions). A {@link
   * SpringSessionBackedSessionRegistry} instead resolves a principal's active sessions directly
   * from the Redis principal-name index, so the cap is enforced for real and survives a frontend
   * restart (the sessions live in Redis, not local heap). Consumed by {@code
   * SecurityConfig.filterChain(...).sessionManagement().maximumSessions(10).sessionRegistry(...)}.
   *
   * <p>Only present outside the {@code test} profile (this whole config is
   * {@code @Profile("!test")}); in tests {@code maximumSessions} falls back to the default
   * registry, which is harmless because the suite does not exercise concurrent-session eviction.
   *
   * @param sessionRepository the Redis-indexed session repository, which is a {@link
   *     FindByIndexNameSessionRepository} thanks to {@code @EnableRedisIndexedHttpSession}.
   * @param <S> the concrete {@link Session} type managed by the repository.
   * @return a session registry backed by the Redis session store.
   */
  @Bean
  public <S extends Session> SpringSessionBackedSessionRegistry<S> sessionRegistry(
      FindByIndexNameSessionRepository<S> sessionRepository) {
    return new SpringSessionBackedSessionRegistry<>(sessionRepository);
  }
}
