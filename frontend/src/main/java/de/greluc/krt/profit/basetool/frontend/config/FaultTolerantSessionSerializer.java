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
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import tools.jackson.databind.exc.InvalidTypeIdException;

/**
 * Wraps the session serializer so that a value which cannot be read is treated as <em>absent</em>
 * rather than as a fatal error, and says enough about the failure to be diagnosable.
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
 * <p><strong>The follow-up this answers (2026-09-02, 09:37Z–12:34Z).</strong> Once the fault was
 * survivable it stopped being visible: 496 consecutive WARN lines, four every ninety seconds for
 * three hours, each saying only {@code (InvalidTypeIdException)} and nothing else. Every one was a
 * poisoned value being re-read — deserialisation reads the whole hash on every request, and a
 * dropped value is not written back, so an authenticated session carries its poison for up to the
 * 720-hour window. There was no attribute name, no type id and no metric: nothing to act on, and no
 * way to watch the residue drain. Three changes fix that:
 *
 * <ol>
 *   <li>every drop increments {@code basetool_session_value_dropped_total{cause}}, so the volume
 *       and its decay are visible without reading logs at all;
 *   <li>the failure's shape travels to {@link SessionAttributeDiagnosticMapper} in an {@link
 *       UnreadableSessionValue}, which owns the WARN because it is the only place that knows the
 *       <em>attribute name</em>;
 *   <li>this class logs at DEBUG, and only for the reads that never reach that mapper.
 * </ol>
 *
 * <p><strong>The triage key</strong>, once a line names a type id:
 *
 * <ul>
 *   <li>{@code typeId=absent} — the stored JSON object carried no {@code @class}. The value's
 *       runtime type was <em>final</em> (a record, a {@code List.of(…)}/{@code Map.of(…)}, any
 *       final class): {@code NON_FINAL} typing writes those without a type id and then will not
 *       read them back. {@code SessionSerializerRoundTripTest} pins the shapes.
 *   <li>a class name — the value was written with a {@code @class} that no longer resolves, i.e. a
 *       class renamed, moved or removed since the session was written. Sessions outlive several
 *       deploys, so this is the upgrade-shaped failure.
 *   <li>{@code cause=IllegalArgumentException} — a <em>top-level</em> unresolvable {@code @class},
 *       which {@code GenericJacksonJsonRedisSerializer} turns into an {@code
 *       IllegalArgumentException} before Jackson ever sees it. The {@code
 *       SPRING_SECURITY_LAST_EXCEPTION} value that PR #1755 stopped writing landed in this bucket.
 * </ul>
 *
 * <p>This is a safety net, not a licence: a value that cannot be read is still a defect. The log
 * carries no session id, no attribute value and no principal — a session payload holds OAuth2
 * tokens, and none of it belongs in a log line.
 */
@Slf4j
public class FaultTolerantSessionSerializer implements RedisSerializer<Object> {

  /**
   * Longest cause chain walked when naming a failure.
   *
   * <p>An unbounded walk guarded only against self-reference — which is what this class shipped
   * with — spins the request thread forever on a two-element cause cycle. Sixteen hops is far more
   * than any real Jackson-inside-Spring wrapping and mirrors the bound {@code
   * LoginFailureMetricsHandler} already uses.
   */
  private static final int MAX_CAUSE_HOPS = 16;

  /** Longest type id rendered into a log line, so a malformed id cannot blow up the line. */
  private static final int MAX_TYPE_ID_LENGTH = 128;

  /**
   * A Java binary class name: dot-separated identifiers, optionally with {@code $} for nested
   * classes and a trailing {@code []} for arrays. A type id that does not match is not logged
   * verbatim — see {@link UnreadableSessionValue#TYPE_ID_NOT_A_CLASS_NAME} for the measured reason.
   */
  private static final Pattern CLASS_NAME =
      Pattern.compile("[\\p{Alnum}_$]+(?:\\.[\\p{Alnum}_$]+)*(?:\\[])*");

  /**
   * Exception simple names allowed as the {@code cause} tag value. Everything else is folded into
   * {@link #CAUSE_OTHER}: a tag fed from an arbitrary class name is an unbounded label, and an
   * unbounded label is a cardinality incident waiting for a novel failure (REQ-OBS-006).
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
  private final RedisSerializer<Object> delegate;

  /**
   * Supplies the registry the drop counter binds to.
   *
   * <p>An {@link ObjectProvider} rather than the registry itself: this serializer is consumed by
   * the Spring Session configuration that {@code @EnableRedisIndexedHttpSession} imports, and a
   * hard {@code MeterRegistry} dependency there drags Micrometer's auto-configuration into
   * session-repository creation.
   */
  private final ObjectProvider<MeterRegistry> meterRegistry;

  /**
   * Creates a tolerant wrapper around {@code delegate}.
   *
   * @param delegate the serializer that does the actual work.
   * @param meterRegistry provider for the registry {@code basetool_session_value_dropped_total}
   *     binds to; resolved lazily, once per drop.
   */
  public FaultTolerantSessionSerializer(
      @NotNull RedisSerializer<Object> delegate,
      @NotNull ObjectProvider<MeterRegistry> meterRegistry) {
    this.delegate = delegate;
    this.meterRegistry = meterRegistry;
  }

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
   * Reads a session value, answering an {@link UnreadableSessionValue} when it cannot be read.
   *
   * <p>The marker is turned into the {@code null} that Spring Session understands as "this
   * attribute is not set" one layer up, in {@link SessionAttributeDiagnosticMapper}, which is also
   * where the WARN is written because that is the only layer that knows the attribute's name. An
   * unreadable attribute therefore still degrades to a signed-out member rather than an unusable
   * application — it just says which attribute it was on the way.
   *
   * @param bytes the stored bytes; may be {@code null} or empty, which the delegate maps to {@code
   *     null} on its own.
   * @return the value, or an {@link UnreadableSessionValue} when the delegate could not read it.
   */
  @Override
  public @Nullable Object deserialize(byte @Nullable [] bytes) throws SerializationException {
    try {
      return delegate.deserialize(bytes);
    } catch (SerializationException ex) {
      UnreadableSessionValue marker = describe(ex);
      count(marker);
      // No WARN here: SessionAttributeDiagnosticMapper writes it, with the attribute name that
      // makes it actionable. This line covers the reads that never reach that mapper — the
      // keyspace-notification payload in RedisIndexedSessionRepository#onMessage.
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
   * Finds the first {@link InvalidTypeIdException} in the cause chain.
   *
   * <p>The <em>first</em>, not the deepest: the type id lives on that exception, and a deeper cause
   * would have none. The walk is bounded, because a cause cycle would otherwise hang the request
   * thread inside a {@code catch} block.
   *
   * @param ex the failure to walk.
   * @return the exception carrying the unresolved type id, or {@code null} if the chain holds none.
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
   * Names the deepest cause's type, which is what distinguishes one read failure from another.
   *
   * <p>The message itself is deliberately not logged: Jackson includes the offending JSON fragment
   * in some of its messages, and that fragment is session payload.
   *
   * @param ex the failure.
   * @return the simple class name of the deepest cause reached within the hop bound.
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
   * Names the type the id was being resolved against, guarding both nulls on the way.
   *
   * <p>Both guards are load-bearing rather than ceremonial: a {@code NullPointerException} thrown
   * from inside this {@code catch} would escape {@link #deserialize}, leave Spring Session's
   * uncaught read path and re-create the very HTTP-500-on-every-request outage this class exists to
   * prevent.
   *
   * @param failure the type-id failure.
   * @return the base type's class name, or {@link UnreadableSessionValue#NOT_APPLICABLE}.
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
