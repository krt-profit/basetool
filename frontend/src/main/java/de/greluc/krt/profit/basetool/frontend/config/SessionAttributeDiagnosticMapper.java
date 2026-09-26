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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.MapSession;
import org.springframework.session.data.redis.RedisSessionMapper;

/**
 * Session mapper that replaces {@link UnreadableSessionValue} markers with {@code null}, logging
 * the attribute name each came from and queueing it in {@link SessionAttributeRepairQueue}
 * (REQ-SEC-050, ADR-0157).
 *
 * <p>A hash missing a required key maps to {@code null}, read by callers as "no session", and bumps
 * {@code basetool_session_unmappable_total} (REQ-SEC-063, ADR-0186). Only attribute names and class
 * names are logged, deduplicated to one WARN per distinct cause; never values or session ids.
 */
@Slf4j
public class SessionAttributeDiagnosticMapper
    implements BiFunction<String, Map<String, Object>, MapSession> {

  /**
   * Hash-field prefix Spring Session puts in front of every session attribute.
   *
   * <p>Hardcoded because {@code RedisSessionMapper.ATTRIBUTE_PREFIX} is package-private upstream.
   * {@code SessionAttributeDiagnosticMapperTest} pins the value against the real mapper's behaviour
   * so a change upstream cannot pass silently.
   */
  private static final String ATTRIBUTE_PREFIX = "sessionAttr:";

  /**
   * The three hash fields {@link RedisSessionMapper} requires, in the order it reads them; used
   * only to name the missing one.
   */
  private static final List<String> REQUIRED_KEYS =
      List.of("creationTime", "lastAccessedTime", "maxInactiveInterval");

  /** Metric-tag and log value for a mapper failure that is not a missing {@link #REQUIRED_KEYS}. */
  private static final String MISSING_KEY_OTHER = "other";

  /**
   * Distinct failures reported at WARN before the guard falls back to DEBUG only.
   *
   * <p>Bounded on purpose: the key contains an attribute name, and although every name in play is a
   * constant today, an unbounded map keyed on anything read out of Redis is a memory leak with a
   * patient trigger.
   */
  private static final int MAX_REPORTED_FAILURES = 64;

  /** The upstream mapper this one decorates; it does all the actual session building. */
  private final BiFunction<String, Map<String, Object>, MapSession> delegate;

  /**
   * Supplies the registry {@code basetool_session_unmappable_total} binds to, lazily so session
   * repository creation does not depend on Micrometer.
   */
  private final ObjectProvider<MeterRegistry> meterRegistry;

  /** Failure keys already reported at WARN, capped at {@link #MAX_REPORTED_FAILURES}. */
  private final Set<String> reported = ConcurrentHashMap.newKeySet();

  /**
   * Missing-required-key values already reported at WARN; every repeat logs at DEBUG.
   *
   * <p>Separate from {@link #reported} rather than sharing its cap: this set can only ever hold the
   * four values of {@link #REQUIRED_KEYS} plus {@link #MISSING_KEY_OTHER}, and letting an
   * attribute-name storm exhaust the cap would silence the louder of the two faults.
   */
  private final Set<String> reportedMissingKeys = ConcurrentHashMap.newKeySet();

  /**
   * Creates a mapper decorating a fresh {@link RedisSessionMapper}.
   *
   * @param meterRegistry provider for the registry {@code basetool_session_unmappable_total} binds
   *     to; resolved lazily
   */
  public SessionAttributeDiagnosticMapper(@NotNull ObjectProvider<MeterRegistry> meterRegistry) {
    this(new RedisSessionMapper(), meterRegistry);
  }

  /**
   * Creates a mapper decorating an explicit delegate.
   *
   * @param delegate the mapper that builds the {@link MapSession}
   * @param meterRegistry provider for the registry {@code basetool_session_unmappable_total} binds
   *     to; resolved lazily
   */
  public SessionAttributeDiagnosticMapper(
      @NotNull BiFunction<String, Map<String, Object>, MapSession> delegate,
      @NotNull ObjectProvider<MeterRegistry> meterRegistry) {
    this.delegate = delegate;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Reports and strips unreadable values, then builds the session through the delegate, answering
   * {@code null} for a hash the delegate cannot map.
   *
   * @param sessionId the session's id, passed to the delegate; never logged
   * @param entries the deserialized session hash, possibly holding {@link UnreadableSessionValue}
   *     markers
   * @return the delegate's session, or {@code null} when the hash is missing a required key
   */
  @Override
  public @Nullable MapSession apply(String sessionId, Map<String, Object> entries) {
    if (entries == null || entries.isEmpty()) {
      return delegate.apply(sessionId, entries);
    }
    Map<String, Object> cleaned = null;
    for (Map.Entry<String, Object> entry : entries.entrySet()) {
      if (entry.getValue() instanceof UnreadableSessionValue marker) {
        if (cleaned == null) {
          cleaned = new LinkedHashMap<>(entries);
        }
        cleaned.put(entry.getKey(), null);
        String attribute = attributeName(entry.getKey());
        SessionAttributeRepairQueue.record(attribute);
        report(attribute, marker);
      }
    }
    Map<String, Object> forDelegate = cleaned != null ? cleaned : entries;
    try {
      return delegate.apply(sessionId, forDelegate);
    } catch (IllegalStateException ex) {
      String missingKey = missingRequiredKey(forDelegate);
      countUnmappable(missingKey);
      reportUnmappable(missingKey, ex);
      return null;
    }
  }

  /**
   * Names the first {@link #REQUIRED_KEYS} entry the hash does not carry.
   *
   * @param entries the hash the delegate refused
   * @return the missing key's name, or {@link #MISSING_KEY_OTHER} when all three are present
   */
  @NotNull
  private static String missingRequiredKey(@NotNull Map<String, Object> entries) {
    for (String key : REQUIRED_KEYS) {
      if (entries.get(key) == null) {
        return key;
      }
    }
    return MISSING_KEY_OTHER;
  }

  /**
   * Bumps {@code basetool_session_unmappable_total} for one refused hash, with a bounded {@code
   * missing_key} tag (REQ-OBS-006).
   *
   * @param missingKey the absent required key, as {@link #missingRequiredKey} resolved it
   */
  private void countUnmappable(@NotNull String missingKey) {
    MeterRegistry registry = meterRegistry.getIfAvailable();
    if (registry == null) {
      return;
    }
    registry
        .counter(MetricNames.SESSION_UNMAPPABLE, MetricNames.TAG_MISSING_KEY, missingKey)
        .increment();
  }

  /**
   * Writes one WARN per distinct missing key and DEBUG for every repeat; the session id is never
   * logged.
   *
   * @param missingKey the absent required key
   * @param cause the delegate's refusal, logged with its stack trace on the first occurrence only
   */
  private void reportUnmappable(@NotNull String missingKey, @NotNull IllegalStateException cause) {
    if (reportedMissingKeys.add(missingKey)) {
      log.warn(
          "Session hash is missing the required '{}' field and cannot be mapped; the request is"
              + " served as if it carried no session, so the member is signed out rather than"
              + " answered 500. A half-written hash is normally a delta write that re-created a"
              + " key which had already gone (see REQ-SEC-063); a rate that does not stay near"
              + " zero means something is losing session hashes. Further occurrences of this key"
              + " log at DEBUG and are counted in basetool_session_unmappable_total.",
          missingKey,
          cause);
      return;
    }
    log.debug("Session hash is missing the required '{}' field again", missingKey);
  }

  /**
   * Strips Spring Session's hash-field prefix, leaving the session attribute name.
   *
   * @param hashField the session-hash field, e.g. {@code sessionAttr:SPRING_SECURITY_CONTEXT}.
   * @return the attribute name, or the field itself when it carries no attribute prefix (the three
   *     required timestamp keys, which are final scalars and cannot fail in the first place).
   */
  @NotNull
  private static String attributeName(@NotNull String hashField) {
    return hashField.startsWith(ATTRIBUTE_PREFIX)
        ? hashField.substring(ATTRIBUTE_PREFIX.length())
        : hashField;
  }

  /**
   * Writes one WARN per distinct failure, and DEBUG for every repeat.
   *
   * @param attribute the session attribute the marker sat in, e.g. {@code SPRING_SECURITY_CONTEXT}.
   * @param marker the failure's shape, carrying class names and fixed tokens only.
   */
  private void report(@NotNull String attribute, @NotNull UnreadableSessionValue marker) {
    String key = attribute + '|' + marker.cause() + '|' + marker.typeId();
    if (reported.size() < MAX_REPORTED_FAILURES && reported.add(key)) {
      log.warn(
          "Dropped an unreadable session value: attribute='{}' cause={} typeId={} baseType={}."
              + " The attribute reads as not set and is removed from the session before this"
              + " request ends, so it cannot drop again; a rate that does NOT fall to zero means"
              + " something is still writing it. Further occurrences of this exact failure log at"
              + " DEBUG and are counted in basetool_session_value_dropped_total.",
          attribute,
          marker.cause(),
          marker.typeId(),
          marker.baseType());
      return;
    }
    log.debug(
        "Dropped an unreadable session value again: attribute='{}' cause={} typeId={}",
        attribute,
        marker.cause(),
        marker.typeId());
  }
}
