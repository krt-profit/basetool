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

package de.greluc.krt.profit.basetool.frontend.support;

import java.util.Collection;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Validates the request parameters the frontend relays verbatim into a backend URI (REQ-SEC-051).
 *
 * <p>The frontend is a proxy: a page or proxy controller binds a value, drops it into a {@code
 * UriComponentsBuilder} or a URI template and hands the result to {@code WebClient}. A relayed
 * value that carries URI syntax — {@code &}, {@code #}, {@code ?}, {@code /} — can reshape the
 * backend request even when it cannot redirect it to a different host, so every relayed value is
 * either bound to a type that cannot express URI syntax ({@link UUID}, {@link java.time.Instant})
 * or passed through one of the checks here before it reaches a builder.
 *
 * <p>The checks are deliberately narrowing rather than escaping. Escaping keeps a hostile value
 * alive one hop further; narrowing rejects it at the frontend, which is also where the clearer
 * error belongs — every parameter these methods guard has a known shape that the backend's own
 * controller signature already declares.
 */
public final class RelayParams {

  /**
   * A Spring {@code Pageable} sort specification: a property path, optionally followed by a
   * direction. Bounded at 64 characters because the longest sort property in either module's DTOs
   * is well under that, and an unbounded pattern would let a caller push an arbitrarily long value
   * into the relayed query string.
   */
  private static final Pattern SORT_SPEC =
      Pattern.compile("[A-Za-z0-9_.]{1,64}(?:,(?:asc|desc|ASC|DESC))?");

  /** Non-instantiable holder of static relay-parameter checks. */
  private RelayParams() {}

  /**
   * Parses a relayed identifier into a {@link UUID}, collapsing anything unparseable to {@code
   * null}.
   *
   * <p>Used where the page degrades gracefully on a bad identifier (an empty result rather than an
   * error page). A {@code UUID} cannot carry URI syntax, so the parsed value is safe to concatenate
   * into a path segment; the raw string never is.
   *
   * @param raw the raw parameter value, may be {@code null} or blank
   * @return the parsed identifier, or {@code null} when {@code raw} is absent, blank or not a UUID
   */
  @Contract(value = "null -> null", pure = true)
  public static @Nullable UUID uuidOrNull(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.strip());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Returns {@code raw} when it is a member of {@code allowed}, otherwise {@code null}.
   *
   * <p>The allowlist is the one the surrounding controller already renders into the form — the
   * event-type options, the client-id options, the sort keys — so a value the UI can produce always
   * passes and a hand-crafted one never reaches the relayed URI. An unknown value degrades to "no
   * filter" rather than an error, matching how the same controllers already treat an unknown tab.
   *
   * @param raw the raw parameter value, may be {@code null} or blank
   * @param allowed the permitted values; membership is exact and case-sensitive
   * @return {@code raw} when allowed, otherwise {@code null}
   */
  @Contract(value = "null, _ -> null", pure = true)
  public static @Nullable String oneOfOrNull(
      @Nullable String raw, @NotNull Collection<String> allowed) {
    if (raw == null || raw.isBlank() || !allowed.contains(raw)) {
      return null;
    }
    return raw;
  }

  /**
   * Returns {@code raw} when it is a well-formed Spring sort specification, otherwise {@code null}.
   *
   * <p>Accepts {@code property} and {@code property,asc} / {@code property,desc} (either case) over
   * the identifier characters a property path can contain. The backend whitelists the property
   * itself (REQ-API-005), so this only has to guarantee that what is relayed cannot open a second
   * query parameter or a fragment.
   *
   * @param raw the raw parameter value, may be {@code null} or blank
   * @return {@code raw} when it is a well-formed sort specification, otherwise {@code null}
   */
  @Contract(value = "null -> null", pure = true)
  public static @Nullable String sortSpecOrNull(@Nullable String raw) {
    if (raw == null || raw.isBlank() || !SORT_SPEC.matcher(raw).matches()) {
      return null;
    }
    return raw;
  }
}
