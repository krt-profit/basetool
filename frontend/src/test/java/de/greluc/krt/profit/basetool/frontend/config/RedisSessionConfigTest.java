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
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.FlushMode;
import org.springframework.session.Session;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
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
 * <p>Also pins {@link RedisSessionConfig#sessionRepositoryCustomizer()}: the configurable {@code
 * spring.session.redis.flush-mode} must bind leniently (case- and {@code -}/{@code _}-insensitive)
 * and fall back to the durable {@code IMMEDIATE} default on an unrecognised value rather than
 * crashing startup, while the session timeout and key namespace stay applied.
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

    // Mirror the exact shape FlashMap puts on the wire: an attribute under the
    // "<BindingResult-prefix><formName>" key plus the form attribute itself.
    Map<String, Object> flashAttributes = new HashMap<>();
    flashAttributes.put(BindingResult.MODEL_KEY_PREFIX + "sampleForm", bindingResult);
    flashAttributes.put("sampleForm", form);

    // Without the mix-in this would throw with
    //   `Document nesting depth (501) exceeds the maximum allowed (500, …)`.
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
   * Instantiates {@link RedisSessionConfig} with the given flush-mode value (plus fixed timeout and
   * namespace), runs its {@link RedisSessionConfig#sessionRepositoryCustomizer()} against a mock
   * repository, and returns that mock for verification.
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
        config.sessionRepositoryCustomizer();
    RedisIndexedSessionRepository repository = mock(RedisIndexedSessionRepository.class);
    customizer.customize(repository);
    return repository;
  }

  /**
   * Simple form bean with a single property. We intentionally avoid one of the real frontend form
   * classes here so the test does not depend on their evolving validation annotations — the cycle
   * this test reproduces lives in Spring's {@code BeanPropertyBindingResult}, not in our forms.
   */
  @SuppressWarnings("unused")
  public static class SampleForm {
    private String name;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }
}
