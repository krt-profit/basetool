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

    // The delegate must never see the marker: it is a diagnostic carrier, and every layer above
    // this one understands only `null` for "this attribute is not set".
    assertNull(seenByDelegate.get().get(ATTRIBUTE_PREFIX + "SPRING_SECURITY_CONTEXT"));
    assertNotNull(session);
    // A signed-out member with intact timestamps — which a login fixes — and NOT the
    // `IllegalStateException: creationTime key must not be null` that a broken required key gives.
    assertTrue(session.getAttributeNames().isEmpty());
  }

  @Test
  void theOriginalMapIsNotMutated() {
    // The repository hands over a map it may still hold a reference to; corrupting it would turn a
    // read-side diagnostic into a write-side surprise.
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
    // The reason the marker exists at all. BackendRoleSyncFilter and TermsAcceptanceGateFilter both
    // removeAttribute on every re-check, so `null` values are routine housekeeping — reporting them
    // would be a false alarm on nearly every request.
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

    // Same map instance, not a defensive copy: nothing failed, so nothing is done.
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
    // RedisSessionMapper.ATTRIBUTE_PREFIX is package-private upstream, so this class hardcodes the
    // literal. This pins the literal against the real mapper's behaviour rather than against a copy
    // of the constant, so an upstream change surfaces here instead of silently mis-naming every
    // attribute in a WARN line.
    Map<String, Object> hash = requiredFields();
    hash.put(ATTRIBUTE_PREFIX + "probe", "value");

    MapSession session = new RedisSessionMapper().apply("session-id", hash);

    assertEquals("value", session.getAttribute("probe"));
    assertFalse(session.getAttributeNames().contains(ATTRIBUTE_PREFIX + "probe"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"creationTime", "lastAccessedTime", "maxInactiveInterval"})
  void aHashMissingARequiredKeyReadsAsNoSessionAndIsCounted(String missingKey) {
    // REQ-SEC-063. Each of the three keys is exercised because `getRequired` reads them in order
    // and only the first failure is ever seen — a guard that caught one of them and let the other
    // two through would look correct in a single-case test and still 500 in production.
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
    // The three names are hardcoded here and in the mapper because upstream's constants are
    // package-private, and they reach a metric tag and a WARN. Pinned against the real mapper, so a
    // rename upstream fails here instead of silently reporting every failure as `other`.
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
    // The metric tag must stay bounded even when the mapper gives up for a reason this class does
    // not model — a fourth required key upstream, or a delegate of our own that refuses. `other`
    // is the bucket that keeps the label closed (REQ-OBS-006).
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
    // The two failure modes must stay separate. A poisoned ATTRIBUTE still yields a signed-out
    // member with intact timestamps; only a missing REQUIRED key gives up on the session entirely.
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
