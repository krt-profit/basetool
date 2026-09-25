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

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import tools.jackson.databind.exc.InvalidTypeIdException;

/**
 * Session serializer wrapper that treats an unreadable value as absent instead of failing the
 * request, so a poisoned session attribute signs the member out rather than breaking every page.
 *
 * <p>Each drop increments {@code basetool_session_value_dropped_total{cause}} and yields an {@link
 * UnreadableSessionValue}, which {@link SessionAttributeDiagnosticMapper} logs with the attribute
 * name. Serialization is not tolerant. Logs never carry session ids, values or principals.
 */
@Slf4j
@RequiredArgsConstructor
public class FaultTolerantSessionSerializer implements RedisSerializer<Object> {

  /** Longest cause chain walked when naming a failure, guarding against cause cycles. */
  private static final int MAX_CAUSE_HOPS = 16;

  /** Longest type id rendered into a log line, so a malformed id cannot blow up the line. */
  private static final int MAX_TYPE_ID_LENGTH = 128;

  /**
   * A Java binary class name, with optional {@code $} nesting and trailing {@code []}; a type id
   * that does not match is not logged verbatim (see {@link
   * UnreadableSessionValue#TYPE_ID_NOT_A_CLASS_NAME}).
   */
  private static final Pattern CLASS_NAME =
      Pattern.compile("[\\p{Alnum}_$]+(?:\\.[\\p{Alnum}_$]+)*(?:\\[])*");

  /**
   * Exception simple names allowed as the {@code cause} tag value; any other is folded into {@link
   * #CAUSE_OTHER} to bound cardinality (REQ-OBS-006).
   */
  private static final Set<String> KNOWN_CAUSES =
      Set.of(
          "InvalidTypeIdException",
          "MismatchedInputException",
          "IllegalArgumentException",
          "InvalidDefinitionException",
          "StreamReadException",
          "ValueInstantiationException");

  /** Bucket for a failure whose root cause is not one of {@link #KNOWN_CAUSES}. */
  private static final String CAUSE_OTHER = "other";

  /** The serializer this one guards; every write and every successful read goes straight to it. */
  private final @NotNull RedisSerializer<Object> delegate;

  /**
   * Supplies the registry the drop counter binds to, lazily so session-repository creation does not
   * depend on Micrometer.
   */
  private final @NotNull ObjectProvider<MeterRegistry> meterRegistry;

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
   * Reads a session value, returning an {@link UnreadableSessionValue} when it cannot be read;
   * {@link SessionAttributeDiagnosticMapper} turns the marker into an absent attribute.
   *
   * @param bytes the stored bytes; may be {@code null} or empty
   * @return the value, or an {@link UnreadableSessionValue} when the delegate could not read it
   */
  @Override
  public @Nullable Object deserialize(byte @Nullable [] bytes) throws SerializationException {
    try {
      return delegate.deserialize(bytes);
    } catch (SerializationException ex) {
      UnreadableSessionValue marker = describe(ex);
      count(marker);
      log.debug(
          "Dropped an unreadable session value (cause={}, typeId={}, baseType={})",
          marker.cause(),
          marker.typeId(),
          marker.baseType());
      return marker;
    }
  }

  /**
   * Describes a read failure with class names and fixed tokens only.
   *
   * @param ex the failure thrown by the delegate.
   * @return the marker handed back in place of the value; never {@code null}.
   */
  private static @NotNull UnreadableSessionValue describe(@NotNull Throwable ex) {
    InvalidTypeIdException typeIdFailure = firstInvalidTypeId(ex);
    if (typeIdFailure == null) {
      return new UnreadableSessionValue(
          rootCauseType(ex),
          UnreadableSessionValue.NOT_APPLICABLE,
          UnreadableSessionValue.NOT_APPLICABLE);
    }
    return new UnreadableSessionValue(
        rootCauseType(ex), safeTypeId(typeIdFailure.getTypeId()), baseTypeOf(typeIdFailure));
  }

  /**
   * Finds the first {@link InvalidTypeIdException} in the cause chain, within the hop bound.
   *
   * @param ex the failure to walk
   * @return the exception carrying the unresolved type id, or {@code null} if the chain holds none
   */
  private static @Nullable InvalidTypeIdException firstInvalidTypeId(@NotNull Throwable ex) {
    Throwable cursor = ex;
    for (int hop = 0; cursor != null && hop < MAX_CAUSE_HOPS; hop++) {
      if (cursor instanceof InvalidTypeIdException invalidTypeId) {
        return invalidTypeId;
      }
      cursor = cursor.getCause() == cursor ? null : cursor.getCause();
    }
    return null;
  }

  /**
   * Names the deepest cause's type within the hop bound; the message is not used because it may
   * contain session payload.
   *
   * @param ex the failure
   * @return the simple class name of the deepest cause reached
   */
  private static @NotNull String rootCauseType(@NotNull Throwable ex) {
    Throwable cursor = ex;
    for (int hop = 0; hop < MAX_CAUSE_HOPS; hop++) {
      Throwable next = cursor.getCause();
      if (next == null || next == cursor) {
        break;
      }
      cursor = next;
    }
    return cursor.getClass().getSimpleName();
  }

  /**
   * Renders a Jackson type id in a form that cannot carry session payload into a log line.
   *
   * @param typeId the id Jackson could not resolve; {@code null} when the JSON object carried no
   *     {@code @class} at all.
   * @return the id when it looks like a Java class name, else one of the fixed tokens.
   */
  private static @NotNull String safeTypeId(@Nullable String typeId) {
    if (typeId == null || typeId.isBlank()) {
      return UnreadableSessionValue.TYPE_ID_ABSENT;
    }
    if (typeId.length() > MAX_TYPE_ID_LENGTH || !CLASS_NAME.matcher(typeId).matches()) {
      return UnreadableSessionValue.TYPE_ID_NOT_A_CLASS_NAME;
    }
    return typeId;
  }

  /**
   * Names the base type the id was resolved against, null-safe so nothing can escape {@link
   * #deserialize}.
   *
   * @param failure the type-id failure
   * @return the base type's class name, or {@link UnreadableSessionValue#NOT_APPLICABLE}
   */
  private static @NotNull String baseTypeOf(@NotNull InvalidTypeIdException failure) {
    if (failure.getBaseType() == null || failure.getBaseType().getRawClass() == null) {
      return UnreadableSessionValue.NOT_APPLICABLE;
    }
    return failure.getBaseType().getRawClass().getName();
  }

  /**
   * Counts one dropped value under a bounded {@code cause} tag.
   *
   * @param marker the described failure.
   */
  private void count(@NotNull UnreadableSessionValue marker) {
    MeterRegistry registry = meterRegistry.getIfAvailable();
    if (registry == null) {
      return;
    }
    String tag = KNOWN_CAUSES.contains(marker.cause()) ? marker.cause() : CAUSE_OTHER;
    registry.counter(MetricNames.SESSION_VALUE_DROPPED, MetricNames.TAG_CAUSE, tag).increment();
  }
}
