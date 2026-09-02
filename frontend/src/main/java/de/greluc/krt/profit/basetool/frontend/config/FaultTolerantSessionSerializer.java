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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

/**
 * Wraps the session serializer so that a value which cannot be read is treated as <em>absent</em>
 * rather than as a fatal error.
 *
 * <p><strong>The outage this exists to prevent (2026-09-02).</strong> Session attribute values in
 * Redis became unreadable ({@code SerializationException: … missing type id property '@class'}),
 * and there is <em>no</em> {@code try}/{@code catch} anywhere on the read path — not in {@code
 * RedisIndexedSessionRepository#findById}, not in {@code AbstractOperations#deserializeHashMap},
 * not in {@code SessionRepositoryFilter}. The exception therefore left the session filter and
 * became an HTTP 500 on <em>every</em> request carrying a session cookie: the whole application,
 * behind the login, for everybody at once. It presented as a blank page rather than an error page,
 * because the error view reads the CSRF token out of the very same session (see {@code
 * SafeCsrfAdvice}).
 *
 * <p><strong>Why absent is the right answer, and why it is enough.</strong> Spring Session stores
 * {@code creationTime}, {@code lastAccessedTime} and {@code maxInactiveInterval} as {@code Long} /
 * {@code Integer}. Those are <em>final</em> classes, and the default typing that {@code
 * SecurityJacksonModules} activates is {@code NON_FINAL} — so they are written as bare JSON scalars
 * carrying no type id at all, and they read back cleanly no matter which serializer wrote them.
 * Only the {@code sessionAttr:*} values that are JSON objects can fail. Nulling exactly those makes
 * {@code RedisSessionMapper} build a session with valid timestamps whose attributes are stripped
 * ({@code MapSession#setAttribute} treats a null value as a removal), which is a member who is
 * simply signed out — the session repository's required-key check never fires.
 *
 * <p>Measured against the real jars before this was written: nulling every attribute yields {@code
 * attrNames=[]} and a null security context; nulling the three required keys instead is what throws
 * {@code IllegalStateException: creationTime key must not be null}. This wrapper can only produce
 * the first case, because the required keys cannot fail in the first place.
 *
 * <p><strong>Writing is deliberately not tolerant.</strong> A failure to serialise means the
 * session cannot be persisted, and swallowing that would hand the member a session that silently
 * forgets everything — a far more confusing defect than an error. Only {@link #deserialize} gives
 * ground.
 *
 * <p>This is a safety net, not a licence: a value that cannot be read is still a defect, so every
 * occurrence is logged at WARN with the exception type. The log carries no session id, no attribute
 * value and no principal — a session payload holds OAuth2 tokens, and none of it belongs in a log
 * line.
 *
 * @param delegate the serializer that does the actual work.
 */
@Slf4j
@RequiredArgsConstructor
public class FaultTolerantSessionSerializer implements RedisSerializer<Object> {

  /** The serializer this one guards; every write and every successful read goes straight to it. */
  private final RedisSerializer<Object> delegate;

  /**
   * Serialises a session value, propagating any failure.
   *
   * @param value the value to write; may be {@code null}.
   * @return the bytes to store.
   * @throws SerializationException if the value cannot be written — deliberately not swallowed.
   */
  @Override
  public byte @Nullable [] serialize(@Nullable Object value) throws SerializationException {
    return delegate.serialize(value);
  }

  /**
   * Reads a session value, answering {@code null} when it cannot be read.
   *
   * <p>{@code null} is what Spring Session already uses for "this attribute is not set", so an
   * unreadable attribute degrades to a signed-out member instead of an unusable application.
   *
   * @param bytes the stored bytes; may be {@code null} or empty, which the delegate maps to {@code
   *     null} on its own.
   * @return the value, or {@code null} when the delegate could not read it.
   */
  @Override
  public @Nullable Object deserialize(byte @Nullable [] bytes) throws SerializationException {
    try {
      return delegate.deserialize(bytes);
    } catch (SerializationException ex) {
      // Nothing identifying: the payload holds OAuth2 tokens and the principal's own data.
      log.warn(
          "Dropped an unreadable session value ({}); the affected session degrades to signed out",
          rootCauseType(ex));
      return null;
    }
  }

  /**
   * Names the deepest cause's type, which is what distinguishes one read failure from another.
   *
   * <p>The message itself is deliberately not logged: Jackson includes the offending JSON fragment
   * in some of its messages, and that fragment is session payload.
   *
   * @param ex the failure.
   * @return the simple class name of the root cause, never {@code null}.
   */
  private static @NotNull String rootCauseType(@NotNull Throwable ex) {
    Throwable cursor = ex;
    while (cursor.getCause() != null && cursor.getCause() != cursor) {
      cursor = cursor.getCause();
    }
    return cursor.getClass().getSimpleName();
  }
}
