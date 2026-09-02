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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.session.MapSession;
import org.springframework.session.data.redis.RedisSessionMapper;

/**
 * Turns {@link UnreadableSessionValue} markers back into the {@code null} Spring Session expects,
 * and — the whole point — names the attribute each one came from before it does.
 *
 * <p><strong>The field the 2026-09-02 incident did not have.</strong> That day's export carries 496
 * WARN lines, four every ninety seconds for three hours, each reading "Dropped an unreadable
 * session value (InvalidTypeIdException)" and nothing more. There was no way to tell whether the
 * poison was the security context, a flash map, one of this application's own session flags, or
 * something Spring Security parks on its own — and therefore no way to decide what to fix. This
 * mapper closes that gap: it sees the whole session hash, so it can pair the failure's shape with
 * the hash field it sat in.
 *
 * <p>Attribute names are safe to log and nothing else here is. They are compile-time constants —
 * {@code SPRING_SECURITY_CONTEXT}, {@code SessionFlashMapManager.FLASH_MAPS}, {@code
 * krt.terms.accepted} — not member data, not a session id, not a value. The value itself never
 * reaches a log line; only the class names {@link UnreadableSessionValue} carries do.
 *
 * <p><strong>Read-only.</strong> The poisoned field is left in Redis. Repairing it means a write on
 * the session <em>read</em> path — the subsystem that took the whole application down twice inside
 * two releases — and the right cure depends on what these lines turn out to name: an attribute
 * re-written unreadably on every request would be deleted and re-poisoned forever at an unchanged
 * rate. That decision waits for this evidence, and will carry its own ADR.
 *
 * <p>The repetition guard is the reason this can log at WARN at all. A poisoned session re-reads
 * its whole hash on every request for as long as it lives — up to the 720-hour authenticated window
 * — so one line per occurrence is what produced the storm in the first place. One line per distinct
 * {@code attribute + cause + typeId} says everything the storm said, once.
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
   * Distinct failures reported at WARN before the guard falls back to DEBUG only.
   *
   * <p>Bounded on purpose: the key contains an attribute name, and although every name in play is a
   * constant today, an unbounded map keyed on anything read out of Redis is a memory leak with a
   * patient trigger.
   */
  private static final int MAX_REPORTED_FAILURES = 64;

  /** The upstream mapper this one decorates; it does all the actual session building. */
  private final BiFunction<String, Map<String, Object>, MapSession> delegate;

  /** Failure keys already reported at WARN, capped at {@link #MAX_REPORTED_FAILURES}. */
  private final Set<String> reported = ConcurrentHashMap.newKeySet();

  /** Creates a mapper decorating a fresh {@link RedisSessionMapper}. */
  public SessionAttributeDiagnosticMapper() {
    this(new RedisSessionMapper());
  }

  /**
   * Creates a mapper decorating an explicit delegate.
   *
   * @param delegate the mapper that builds the {@link MapSession}; the test uses this to prove the
   *     delegate is handed a map with no markers left in it.
   */
  public SessionAttributeDiagnosticMapper(
      @NotNull BiFunction<String, Map<String, Object>, MapSession> delegate) {
    this.delegate = delegate;
  }

  /**
   * Reports and strips unreadable values, then builds the session through the delegate.
   *
   * @param sessionId the session's id — used for nothing but the delegate; never logged.
   * @param entries the deserialized session hash, possibly holding {@link UnreadableSessionValue}
   *     markers.
   * @return whatever the delegate makes of the cleaned map.
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
        report(entry.getKey(), marker);
      }
    }
    // The delegate sees exactly what it sees today whenever nothing failed, and a map whose bad
    // values are null when something did — which is the "attribute not set" it already handles.
    return delegate.apply(sessionId, cleaned != null ? cleaned : entries);
  }

  /**
   * Writes one WARN per distinct failure, and DEBUG for every repeat.
   *
   * @param hashField the session-hash field the marker sat in, e.g. {@code
   *     sessionAttr:SPRING_SECURITY_CONTEXT}.
   * @param marker the failure's shape, carrying class names and fixed tokens only.
   */
  private void report(@NotNull String hashField, @NotNull UnreadableSessionValue marker) {
    String attribute =
        hashField.startsWith(ATTRIBUTE_PREFIX)
            ? hashField.substring(ATTRIBUTE_PREFIX.length())
            : hashField;
    String key = attribute + '|' + marker.cause() + '|' + marker.typeId();
    if (reported.size() < MAX_REPORTED_FAILURES && reported.add(key)) {
      log.warn(
          "Dropped an unreadable session value: attribute='{}' cause={} typeId={} baseType={}."
              + " The affected session degrades to signed out; further occurrences of this exact"
              + " failure log at DEBUG and are counted in basetool_session_value_dropped_total.",
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
