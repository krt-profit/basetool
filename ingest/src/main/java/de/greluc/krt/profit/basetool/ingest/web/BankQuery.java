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

package de.greluc.krt.profit.basetool.ingest.web;

import de.greluc.krt.profit.basetool.ingest.contract.ExchangeSchemas;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The checked parameters of a bank read on one account (REQ-BXCH-002): the account id and the
 * {@code from} / {@code to} bounds, normalised to UTC instants so the relayed query carries only
 * characters that need no encoding. A balance series needs both bounds and spans at most {@value
 * #MAX_SERIES_DAYS} days.
 *
 * @param accountId the account id in lower case, or {@code null} when it is malformed
 * @param bounds the bounds that were given and well-formed
 * @param violations what is wrong with the parameters; empty when they may be relayed
 */
record BankQuery(
    @Nullable String accountId,
    @NotNull Bounds bounds,
    @NotNull @Unmodifiable List<ExchangeSchemas.Violation> violations) {

  /** The query name of the lower bound. */
  static final String FROM = "from";

  /** The query name of the upper bound. */
  static final String TO = "to";

  /** The most days a balance series covers. */
  static final int MAX_SERIES_DAYS = 365;

  /** The longest period a balance series covers, {@value #MAX_SERIES_DAYS} days. */
  static final Duration MAX_SERIES_SPAN = Duration.ofDays(MAX_SERIES_DAYS);

  /**
   * Checks the parameters of a bank read.
   *
   * @param id the raw account id
   * @param from the raw lower bound, or {@code null}
   * @param to the raw upper bound, or {@code null}
   * @param series whether the read is a balance series, which needs both bounds within a year
   * @return the checked parameters
   */
  static @NotNull BankQuery of(
      @NotNull String id, @Nullable String from, @Nullable String to, boolean series) {
    List<ExchangeSchemas.Violation> violations = new ArrayList<>();
    String accountId = null;
    if (ExchangeController.UUID_SHAPE.matcher(id).matches()) {
      accountId = id.toLowerCase(Locale.ROOT);
    } else {
      violations.add(new ExchangeSchemas.Violation("/id", "must be an account id"));
    }
    Instant lower = bound(FROM, from, series, violations);
    Instant upper = bound(TO, to, series, violations);
    if (series && lower != null && upper != null) {
      if (lower.isAfter(upper)) {
        violations.add(new ExchangeSchemas.Violation("/" + FROM, "must not lie after to"));
      } else if (Duration.between(lower, upper).compareTo(MAX_SERIES_SPAN) > 0) {
        violations.add(
            new ExchangeSchemas.Violation(
                "/" + TO, "must lie within " + MAX_SERIES_DAYS + " days of from"));
      }
    }
    return new BankQuery(accountId, new Bounds(lower, upper), List.copyOf(violations));
  }

  /**
   * Reads one bound.
   *
   * @param name the query name
   * @param raw the raw value, or {@code null}
   * @param required whether the bound must be given
   * @param violations collects what is wrong
   * @return the instant, or {@code null} when absent or malformed
   */
  private static @Nullable Instant bound(
      @NotNull String name,
      @Nullable String raw,
      boolean required,
      @NotNull List<ExchangeSchemas.Violation> violations) {
    if (raw == null) {
      if (required) {
        violations.add(new ExchangeSchemas.Violation("/" + name, "is required"));
      }
      return null;
    }
    try {
      return OffsetDateTime.parse(raw).toInstant();
    } catch (DateTimeParseException _) {
      violations.add(new ExchangeSchemas.Violation("/" + name, "must be a date-time"));
      return null;
    }
  }

  /**
   * The {@code from} / {@code to} bounds of a bank read as UTC instants, each absent when it was
   * not given or malformed.
   *
   * @param from the inclusive lower bound, or {@code null}
   * @param to the inclusive upper bound, or {@code null}
   */
  record Bounds(@Nullable Instant from, @Nullable Instant to) {

    /**
     * Adds the given bounds to a relayed target as ISO-8601 instants, whose characters need no
     * encoding in a query.
     *
     * @param target the relayed target
     */
    void addTo(@NotNull UriComponentsBuilder target) {
      if (from != null) {
        target.queryParam(FROM, from);
      }
      if (to != null) {
        target.queryParam(TO, to);
      }
    }
  }
}
