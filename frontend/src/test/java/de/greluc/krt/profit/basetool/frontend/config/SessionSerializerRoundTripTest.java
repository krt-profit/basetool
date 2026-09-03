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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.tomcat.websocket.server.WsHttpSessionBindingListener;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * The session serializer must be able to read back what it just wrote.
 *
 * <p><strong>The production incident this pins (2026-09-02).</strong> Every request carrying a
 * session answered HTTP 500 with {@code SerializationException: … missing type id property
 * '@class'}, followed by Spring Session's {@code IllegalStateException: creationTime key must not
 * be null}. The blast radius was everything behind a login, and the symptom was a blank page rather
 * than an error page, because the error view itself reads the CSRF token out of the same unreadable
 * session.
 *
 * <p><strong>These cases pass, and that is the point.</strong> An early theory during the incident
 * was that the required keys were the broken ones: {@code creationTime} is a {@code Long}, the
 * default typing {@code SecurityJacksonModules} activates is {@code NON_FINAL}, and a final class
 * therefore gets no {@code @class} property. Measured against the real jars, that theory is
 * <em>wrong</em> — a bare JSON scalar is read back cleanly, because the reader does not demand a
 * type id it never had. Only {@code sessionAttr:*} values that are JSON <em>objects</em> can fail,
 * which is why {@link FaultTolerantSessionSerializerTest} carries the failing shape and this class
 * carries the ones that must never start failing.
 *
 * <p>Kept rather than deleted: a test that pins a disproved theory's subject is what stops the
 * theory being re-invented, and a regression here would mean a member cannot be signed in at all
 * rather than merely signed out.
 *
 * <p>Each case below is a value Spring Session actually stores in the session hash, so a failure
 * here is a failure of the real thing rather than of a contrived one.
 */
class SessionSerializerRoundTripTest {

  private final RedisSerializer<Object> serializer =
      new GenericJacksonJsonRedisSerializer(
          RedisSessionConfig.buildSessionJsonMapper(
              SessionSerializerRoundTripTest.class.getClassLoader()));

  /**
   * Round-trips one value through the configured serializer.
   *
   * @param value the value Spring Session would store.
   * @return whatever came back out.
   */
  private Object roundTrip(Object value) {
    return serializer.deserialize(serializer.serialize(value));
  }

  @Test
  void creationTime_isReadableAgain() {
    // The exact field named by the production stack trace. Spring Session's RedisSessionMapper
    // rejects the whole session when this one comes back null, which is how a serialization
    // asymmetry turns into "you are logged out" for everybody at once.
    assertEquals(1_788_334_209_954L, roundTrip(1_788_334_209_954L));
  }

  @Test
  void maxInactiveInterval_isReadableAgain() {
    // Stored as an Integer (seconds) rather than a Duration — a second final type on the same path.
    assertEquals(1800, roundTrip(1800));
  }

  @Test
  void aStringAttribute_isReadableAgain() {
    // The active-org-unit pin is a plain String under `iridium.activeOrgUnitId`, and String is
    // final too.
    assertEquals(
        "00000000-0000-0000-0000-000000000001", roundTrip("00000000-0000-0000-0000-000000000001"));
  }

  @Test
  void aBooleanAttribute_isReadableAgain() {
    assertEquals(Boolean.TRUE, roundTrip(Boolean.TRUE));
  }

  /** A record, i.e. an implicitly final type whose JSON form is an object. */
  private record ProbeRecord(String name, int amount) {}

  @Test
  void aRecordAsAnAttributeValue_cannotBeReadBack() {
    // The trap, measured rather than argued. A record is implicitly FINAL, so the NON_FINAL default
    // typing writes it with no `@class` — while still writing a JSON OBJECT, which the reader then
    // demands a type id for. It writes without complaint and is unreadable on the very next
    // request, taking the whole session with it before FaultTolerantSessionSerializer existed.
    //
    // This is a CONTRACT, not a wish: nothing about a record makes it unusable in a session, only
    // this serializer configuration does. If a future change makes the round trip succeed, this
    // test is the one to delete — deliberately, not by accident.
    //
    // RedisSessionConfig's forced-type-id mix-in does NOT make it succeed, and that is the point of
    // keeping this case beside `tomcatsWebSocketSessionBindingListener_isReadableAgain`: the mix-in
    // is an allow-list of individually named container classes, not a change of policy for final
    // types. If this ever starts passing, the allow-list has grown into a blanket rule and the
    // required Long/Integer session keys are the next thing to check.
    assertThrows(Exception.class, () -> roundTrip(new ProbeRecord("probe", 3)));
  }

  @Test
  void anImmutableJdkCollectionAsAnAttributeValue_cannotBeReadBack() {
    // The same trap wearing different clothes, and the easier one to walk into: List.of(...) and
    // Map.of(...) are final JDK classes, so they too are written without a type id. BackendRoleSync
    // Filter's `new ArrayList<>(backend.asserted())` is not a stylistic wrapper — it is what makes
    // that attribute readable.
    assertThrows(Exception.class, () -> roundTrip(List.of("a", "b")));
    assertThrows(Exception.class, () -> roundTrip(Map.of("a", "b")));
  }

  @Test
  void aMutableCollectionCarryingTheSameRecord_isReadableAgain() {
    // And the workaround, pinned so the rule is not over-read into "records may never touch a
    // session". A non-final container gets its `@class`, and its contents are then written through
    // Object-typed slots, which type-id everything inside — including a record. This is why flash
    // attributes (a java.util.ArrayList of FlashMap) survive while a bare record does not.
    List<Object> wrapped = new ArrayList<>(List.of(new ProbeRecord("probe", 3)));

    assertEquals(wrapped, roundTrip(wrapped));
    assertEquals(
        new LinkedHashMap<>(Map.of("k", "v")), roundTrip(new LinkedHashMap<>(Map.of("k", "v"))));
  }

  @Test
  void tomcatsWebSocketSessionBindingListener_isReadableAgain() {
    // The 2026-09-03 alert, pinned against the real class rather than a stand-in. Tomcat 11.0.25
    // added this record and `WsServerContainer#registerAuthenticatedSession` writes it into the
    // HttpSession on every authenticated WebSocket handshake — which for this application is every
    // logged-in page, because live sync opens /ws/sync. Being a record it is implicitly final, so
    // before RedisSessionConfig's forced-type-id mix-in it was written with no `@class` and dropped
    // on the very next request. The rate never decayed, because a dropped value leaves the
    // attribute unset and the next handshake writes it again: SessionValueDropsSustained fired at
    // ~70 drops per 15 min against a healthy application.
    //
    // Referenced by type here, deliberately, although the production registration resolves it by
    // name: this test is the alarm that should ring if the class is ever gone, because that is
    // exactly when the entry in CONTAINER_WRITTEN_FINAL_SESSION_TYPES stops earning its place.
    WsHttpSessionBindingListener listener = new WsHttpSessionBindingListener("a-session-id");

    assertEquals(listener, roundTrip(listener));
  }

  @Test
  void theForcedTypeIdUsesTheSameAtClassPropertyAsEverythingElseInTheHash() {
    // The mix-in must not invent a second dialect. `@class` is what the NON_FINAL default typing
    // writes for every non-final value in the same session hash, so writing the forced id under any
    // other property name would read back only by accident — and would diverge silently the moment
    // Spring Session's own typing configuration moved.
    byte[] written = serializer.serialize(new WsHttpSessionBindingListener("a-session-id"));

    assertNotNull(written);
    assertTrue(
        new String(written, StandardCharsets.UTF_8)
            .contains(
                "\"@class\":\"org.apache.tomcat.websocket.server.WsHttpSessionBindingListener\""),
        "the forced type id must be written as the @class property");
  }
}
