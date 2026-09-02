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
}
