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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
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
                })
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

    new SessionAttributeDiagnosticMapper().apply("session-id", hash);

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
            })
        .apply("session-id", hash);

    // Same map instance, not a defensive copy: nothing failed, so nothing is done.
    assertEquals(hash, seenByDelegate.get());
    assertTrue(seenByDelegate.get().containsKey(ATTRIBUTE_PREFIX + "krt.terms.accepted"));
  }

  @Test
  void aReadableSessionPassesThroughUnchanged() {
    Map<String, Object> hash = requiredFields();
    hash.put(ATTRIBUTE_PREFIX + "welcomeMessageShown", Boolean.TRUE);

    MapSession session = new SessionAttributeDiagnosticMapper().apply("session-id", hash);

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
}
