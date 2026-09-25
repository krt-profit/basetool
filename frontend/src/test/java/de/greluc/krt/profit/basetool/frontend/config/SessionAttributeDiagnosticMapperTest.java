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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.session.MapSession;
import org.springframework.session.data.redis.RedisSessionMapper;

/**
 * The mapper must name the unreadable attribute and then get out of the way.
 *
 * <p>The name is the field the 2026-09-02 incident did not have: 496 WARN lines over three hours,
 * none of which said <em>which</em> session attribute could not be read. Everything else about the
 * session-building behaviour must stay byte-for-byte what {@link RedisSessionMapper} already does,
 * because that behaviour is what makes an unreadable attribute a signed-out member rather than an
 * unusable application.
 */
class SessionAttributeDiagnosticMapperTest {

  /** Hash-field prefix Spring Session puts in front of every session attribute. */
  private static final String ATTRIBUTE_PREFIX = "sessionAttr:";

  /** Registry the unmappable-hash counter binds to; asserted on directly in the metric cases. */
  private final MeterRegistry registry = new SimpleMeterRegistry();

  /**
   * Builds the provider the production wiring hands the mapper.
   *
   * <p>Taken from a real bean factory rather than hand-rolled, so the lazy resolution the mapper
   * relies on is the one under test.
   *
   * @return a provider backed by a bean factory holding {@link #registry}.
   */
  private ObjectProvider<MeterRegistry> registryProvider() {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerSingleton("meterRegistry", registry);
    return beanFactory.getBeanProvider(MeterRegistry.class);
  }

  /**
   * Reads the unmappable-hash counter for one missing key.
   *
   * @param missingKey the {@code missing_key} tag value to read.
   * @return the count so far, or {@code 0} while that series is unregistered.
   */
  private double unmappable(String missingKey) {
    var counter =
        registry.find("basetool.session.unmappable").tag("missing_key", missingKey).counter();
    return counter == null ? 0d : counter.count();
  }

  /**
   * Builds the three required hash fields every session carries.
   *
   * @return a mutable map with valid timestamps and no attributes.
   */
  private static Map<String, Object> requiredFields() {
    Map<String, Object> hash = new LinkedHashMap<>();
    hash.put("creationTime", Instant.now().toEpochMilli());
    hash.put("lastAccessedTime", Instant.now().toEpochMilli());
    hash.put("maxInactiveInterval", 1800);
    return hash;
  }

  @Test
  void anUnreadableAttributeIsStrippedBeforeTheDelegateSeesIt() {
    Map<String, Object> hash = requiredFields();
    hash.put(
        ATTRIBUTE_PREFIX + "SPRING_SECURITY_CONTEXT",
        new UnreadableSessionValue(
            "InvalidTypeIdException", UnreadableSessionValue.TYPE_ID_ABSENT, "java.lang.Object"));
    AtomicReference<Map<String, Object>> seenByDelegate = new AtomicReference<>();

    MapSession session =
        new SessionAttributeDiagnosticMapper(
                (id, entries) -> {
                  seenByDelegate.set(entries);
                  return new RedisSessionMapper().apply(id, entries);
                },
                registryProvider())
            .apply("session-id", hash);

    assertNull(seenByDelegate.get().get(ATTRIBUTE_PREFIX + "SPRING_SECURITY_CONTEXT"));
    assertNotNull(session);
    assertTrue(session.getAttributeNames().isEmpty());
  }

  @Test
  void theOriginalMapIsNotMutated() {
    Map<String, Object> hash = requiredFields();
    UnreadableSessionValue marker =
        new UnreadableSessionValue(
            "InvalidTypeIdException", "de.greluc.krt.Vanished", "java.lang.Object");
    hash.put(ATTRIBUTE_PREFIX + "poisoned", marker);

    new SessionAttributeDiagnosticMapper(registryProvider()).apply("session-id", hash);

    assertEquals(marker, hash.get(ATTRIBUTE_PREFIX + "poisoned"));
  }

  @Test
  void aGenuineTombstoneIsLeftCompletelyAlone() {
    Map<String, Object> hash = requiredFields();
    hash.put(ATTRIBUTE_PREFIX + "krt.terms.accepted", null);
    AtomicReference<Map<String, Object>> seenByDelegate = new AtomicReference<>();

    new SessionAttributeDiagnosticMapper(
            (id, entries) -> {
              seenByDelegate.set(entries);
              return new RedisSessionMapper().apply(id, entries);
            },
            registryProvider())
        .apply("session-id", hash);

    assertEquals(hash, seenByDelegate.get());
    assertTrue(seenByDelegate.get().containsKey(ATTRIBUTE_PREFIX + "krt.terms.accepted"));
  }

  @Test
  void aReadableSessionPassesThroughUnchanged() {
    Map<String, Object> hash = requiredFields();
    hash.put(ATTRIBUTE_PREFIX + "welcomeMessageShown", Boolean.TRUE);

    MapSession session =
        new SessionAttributeDiagnosticMapper(registryProvider()).apply("session-id", hash);

    assertNotNull(session);
    assertEquals(Boolean.TRUE, session.getAttribute("welcomeMessageShown"));
  }

  @Test
  void theHardcodedAttributePrefixStillMatchesTheUpstreamMapper() {
    Map<String, Object> hash = requiredFields();
    hash.put(ATTRIBUTE_PREFIX + "probe", "value");

    MapSession session = new RedisSessionMapper().apply("session-id", hash);

    assertEquals("value", session.getAttribute("probe"));
    assertFalse(session.getAttributeNames().contains(ATTRIBUTE_PREFIX + "probe"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"creationTime", "lastAccessedTime", "maxInactiveInterval"})
  void aHashMissingARequiredKeyReadsAsNoSessionAndIsCounted(String missingKey) {
    Map<String, Object> hash = requiredFields();
    hash.remove(missingKey);

    MapSession session =
        new SessionAttributeDiagnosticMapper(registryProvider()).apply("session-id", hash);

    assertNull(session, "an unmappable hash is no session, not an exception");
    assertEquals(
        1d, unmappable(missingKey), "the give-up is counted under the key that was absent");
  }

  @Test
  void theRequiredKeyLiteralsStillMatchTheUpstreamMapper() {
    for (String key : new String[] {"creationTime", "lastAccessedTime", "maxInactiveInterval"}) {
      Map<String, Object> hash = requiredFields();
      hash.remove(key);

      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class,
              () -> new RedisSessionMapper().apply("session-id", hash));

      assertTrue(
          thrown.getMessage().startsWith(key + " key"),
          () -> "upstream no longer requires '" + key + "': " + thrown.getMessage());
    }
  }

  @Test
  void aDelegateFailureWithEveryRequiredKeyPresentIsCountedAsOther() {
    Map<String, Object> hash = requiredFields();

    MapSession session =
        new SessionAttributeDiagnosticMapper(
                (id, entries) -> {
                  throw new IllegalStateException("somethingElse key must not be null");
                },
                registryProvider())
            .apply("session-id", hash);

    assertNull(session);
    assertEquals(1d, unmappable("other"));
  }

  @Test
  void anUnreadableAttributeDoesNotTurnAHealthySessionIntoNoSession() {
    Map<String, Object> hash = requiredFields();
    hash.put(
        ATTRIBUTE_PREFIX + "SPRING_SECURITY_CONTEXT",
        new UnreadableSessionValue(
            "InvalidTypeIdException", UnreadableSessionValue.TYPE_ID_ABSENT, "java.lang.Object"));

    MapSession session =
        new SessionAttributeDiagnosticMapper(registryProvider()).apply("session-id", hash);

    assertNotNull(session);
    assertEquals(0d, unmappable("creationTime"), "nothing gave up on the session");
  }
}
