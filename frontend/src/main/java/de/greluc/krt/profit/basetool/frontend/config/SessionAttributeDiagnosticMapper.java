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
 * <p><strong>Read-only itself, but no longer the end of the story (2026-09-03).</strong> This class
 * still writes nothing: repairing from here would mean a Redis write on the session <em>read</em>
 * path, the subsystem that took the whole application down twice inside two releases. What it now
 * does is hand the attribute name to {@link SessionAttributeRepairQueue}, so {@link
 * SessionAttributeRepairFilter} can remove it through the ordinary {@code
 * HttpSession#removeAttribute} API before the request ends (REQ-SEC-050, ADR-0157).
 *
 * <p>The earlier version of this note deferred that decision until the WARN named something, on the
 * grounds that "an attribute re-written unreadably on every request would be deleted and
 * re-poisoned forever at an unchanged rate". The evidence arrived and settled it: the name was
 * Tomcat's {@code WsHttpSessionBindingListener}, its writer was fixed by ADR-0154's forced type id,
 * and what kept the alert firing was purely that nothing ever cleared the values written before
 * that fix. Repairing costs one write per drop even in the re-poisoning case, and ends the drop
 * entirely in every other.
 *
 * <p>The repetition guard is the reason this can log at WARN at all. A poisoned session re-reads
 * its whole hash on every request for as long as it lives — up to the 720-hour authenticated window
 * — so one line per occurrence is what produced the storm in the first place. One line per distinct
 * {@code attribute + cause + typeId} says everything the storm said, once.
 *
 * <p><strong>The second job, added 2026-09-16: a hash that is missing a required key reads as no
 * session at all</strong> (REQ-SEC-063, ADR-0186). {@link RedisSessionMapper} throws {@code
 * IllegalStateException: creationTime key must not be null} when the hash it is handed is non-empty
 * but carries none of that key, and nothing on Spring Session's read path catches it — so it leaves
 * {@code SessionRepositoryFilter} and becomes an HTTP 500 for every request that browser makes.
 * This mapper catches it and answers {@code null}, which both upstream call sites already treat as
 * "no session": {@code RedisIndexedSessionRepository#getSession} returns {@code null} to the
 * filter, which then mints a fresh session, and {@code #onMessage} skips the {@code
 * SessionCreatedEvent}. The member is signed out and a login fixes it, instead of being locked out
 * of every page until they delete the cookie themselves.
 *
 * <p>This is not hypothetical and it is not the 2026-09-02 failure mode. Production answered 286
 * such 500s on 2026-09-14 and 18 more on 2026-09-16, with not one "dropped an unreadable session
 * value" line beside them — so nothing had nulled the key, it was never written. {@code
 * RedisSession#saveDelta} writes a plain {@code HSET} of the changed fields only, and {@code
 * creationTime} is in that delta solely {@code if (isNew)}; a request that commits after its hash
 * has gone therefore re-creates the key holding {@code lastAccessedTime} alone and puts the full
 * session TTL back on it. The whole account is in the knowledge base note "A half-written session
 * hash 500s every request that browser makes".
 *
 * <p><strong>Answering {@code null} must stay loud.</strong> If every session lost a required key
 * at once — a genuine wire-format break — a silent {@code null} would sign the whole organisation
 * out with no signal anywhere. That is what {@code basetool_session_unmappable_total} and its
 * {@code SessionUnmappableSustained} alert are for; the counter is the price of the graceful
 * degradation, not an optional extra.
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
   * The three hash fields {@link RedisSessionMapper} requires, in the order it reads them.
   *
   * <p>Hardcoded for the same reason as {@link #ATTRIBUTE_PREFIX}: the upstream constants are
   * package-private. They exist here only to name the missing one in the WARN and the metric tag —
   * the decision to give up is taken from the thrown {@link IllegalStateException}, never by
   * re-implementing upstream's presence check, so a fourth required key added upstream still
   * degrades correctly and merely reports {@link #MISSING_KEY_OTHER}. {@code
   * SessionAttributeDiagnosticMapperTest} pins each literal against the real mapper.
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
   * Supplies the registry {@code basetool_session_unmappable_total} binds to.
   *
   * <p>An {@link ObjectProvider} rather than the registry itself, for the same reason {@link
   * FaultTolerantSessionSerializer} uses one: this mapper is installed from the {@code
   * SessionRepositoryCustomizer} that {@code @EnableRedisIndexedHttpSession} consumes, and a hard
   * {@code MeterRegistry} dependency there drags Micrometer's auto-configuration into
   * session-repository creation.
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
   *     to; resolved lazily, once per unmappable hash.
   */
  public SessionAttributeDiagnosticMapper(@NotNull ObjectProvider<MeterRegistry> meterRegistry) {
    this(new RedisSessionMapper(), meterRegistry);
  }

  /**
   * Creates a mapper decorating an explicit delegate.
   *
   * @param delegate the mapper that builds the {@link MapSession}; the test uses this to prove the
   *     delegate is handed a map with no markers left in it, and to make it throw the way the real
   *     one does on a half-written hash.
   * @param meterRegistry provider for the registry {@code basetool_session_unmappable_total} binds
   *     to; resolved lazily, once per unmappable hash.
   */
  public SessionAttributeDiagnosticMapper(
      @NotNull BiFunction<String, Map<String, Object>, MapSession> delegate,
      @NotNull ObjectProvider<MeterRegistry> meterRegistry) {
    this.delegate = delegate;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Reports and strips unreadable values, then builds the session through the delegate — answering
   * {@code null} instead of propagating a hash the delegate cannot map at all.
   *
   * @param sessionId the session's id — used for nothing but the delegate; never logged.
   * @param entries the deserialized session hash, possibly holding {@link UnreadableSessionValue}
   *     markers.
   * @return whatever the delegate makes of the cleaned map, or {@code null} when the hash is
   *     missing a key the delegate requires — which every caller reads as "no session".
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
   * <p>Read off the map rather than parsed out of the exception message: the message is upstream
   * prose and would silently stop matching, while the map is the evidence itself.
   *
   * @param entries the hash the delegate refused.
   * @return the missing key's name, or {@link #MISSING_KEY_OTHER} when all three are present and
   *     the delegate therefore failed for some other reason.
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
   * Bumps {@code basetool_session_unmappable_total} for one refused hash.
   *
   * <p>The {@code missing_key} tag can only take the three {@link #REQUIRED_KEYS} literals and
   * {@link #MISSING_KEY_OTHER}, so it is bounded by construction (REQ-OBS-006). Nothing read out of
   * Redis ever reaches a tag.
   *
   * @param missingKey the required key that was absent, as {@link #missingRequiredKey} resolved it.
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
   * Writes one WARN per distinct missing key, and DEBUG for every repeat.
   *
   * <p>The session id is not logged, on purpose: it is the bearer token for that session, and this
   * line is written on a path that anyone holding the cookie can reach. The missing key's name is a
   * compile-time constant and carries nothing about the member.
   *
   * @param missingKey the required key that was absent.
   * @param cause the delegate's refusal, passed for the stack trace on the first occurrence only.
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
