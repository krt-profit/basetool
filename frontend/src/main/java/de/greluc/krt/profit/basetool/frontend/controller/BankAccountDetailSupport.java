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

package de.greluc.krt.profit.basetool.frontend.controller;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shared query-parameter maths for the two account-detail surfaces (bank-staff {@code
 * /bank/accounts/{id}} and org-unit {@code /org-unit-bank/accounts/{id}}) so both resolve the
 * booking-history period, the page-size options and the balance-chart range identically
 * (REQ-BANK-049/-051). Both controllers own their own model wiring and fragment view names; only
 * the time-window arithmetic lives here, mirroring how {@link BankSparkline} / {@link
 * BankBalanceChart} are shared pure helpers.
 */
public final class BankAccountDetailSupport {

  /** Booking-history page-size options offered to the user; the picker's default is {@code 50}. */
  public static final List<Integer> PAGE_SIZES = List.of(10, 50, 100);

  /** Default booking-history page size when the caller has not picked one (matches the backend). */
  public static final int DEFAULT_PAGE_SIZE = 50;

  /** The default booking-history look-back when the caller has not picked a period: 90 days. */
  public static final int DEFAULT_HISTORY_DAYS = 90;

  /** The chart range keys, in display order; each maps to a {@code bank.chart.range.*} label. */
  public static final List<String> CHART_RANGES = List.of("30d", "90d", "365d", "all");

  /** The default chart range: the last 90 days, matching the booking-history default. */
  public static final String DEFAULT_CHART_RANGE = "90d";

  /** Utility class — not instantiable. */
  private BankAccountDetailSupport() {}

  /**
   * A resolved booking-history period: the two calendar dates as picked (for the date inputs and
   * the pagination base URL) and their inclusive UTC instant bounds (for the backend query).
   *
   * @param fromDate the inclusive start date (UTC calendar day)
   * @param toDate the inclusive end date (UTC calendar day)
   * @param fromInstant the inclusive start instant ({@code fromDate} at 00:00 UTC)
   * @param toInstant the inclusive end instant ({@code toDate} at 23:59:59.999 UTC)
   */
  public record HistoryPeriod(
      LocalDate fromDate, LocalDate toDate, Instant fromInstant, Instant toInstant) {}

  /**
   * Resolves the booking-history period from the optional {@code from}/{@code to} date parameters,
   * defaulting to the last {@value #DEFAULT_HISTORY_DAYS} days (REQ-BANK-051). A blank or
   * unparseable value falls back to its default; an inverted range (from after to) is repaired to
   * the default look-back ending at {@code to} so the query never sees {@code from > to}.
   *
   * @param from the raw {@code from} date parameter ({@code yyyy-MM-dd}), or {@code null}
   * @param to the raw {@code to} date parameter ({@code yyyy-MM-dd}), or {@code null}
   * @return the resolved period
   */
  @NotNull
  public static HistoryPeriod resolveHistoryPeriod(@Nullable String from, @Nullable String to) {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate toDate = parseDateOrDefault(to, today);
    LocalDate fromDate = parseDateOrDefault(from, toDate.minusDays(DEFAULT_HISTORY_DAYS));
    if (fromDate.isAfter(toDate)) {
      fromDate = toDate.minusDays(DEFAULT_HISTORY_DAYS);
    }
    Instant fromInstant = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant toInstant = toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1);
    return new HistoryPeriod(fromDate, toDate, fromInstant, toInstant);
  }

  /**
   * Clamps a raw chart-range parameter to a known key, defaulting to {@link #DEFAULT_CHART_RANGE}.
   *
   * @param range the raw {@code chartRange} parameter, or {@code null}
   * @return a valid range key
   */
  @NotNull
  public static String normalizeChartRange(@Nullable String range) {
    return range != null && CHART_RANGES.contains(range) ? range : DEFAULT_CHART_RANGE;
  }

  /**
   * The inclusive start instant of a chart range: {@code now} minus the range's span, or the
   * account's creation instant for {@code "all"} (a five-year fallback when the creation instant is
   * unknown, e.g. the account could not be loaded).
   *
   * @param range a valid range key (see {@link #normalizeChartRange})
   * @param createdAt the account's creation instant, used for {@code "all"}; may be {@code null}
   * @param now the period end (the current instant)
   * @return the chart's inclusive start instant
   */
  @NotNull
  public static Instant chartFromInstant(
      @NotNull String range, @Nullable Instant createdAt, @NotNull Instant now) {
    return switch (range) {
      case "30d" -> now.minus(Duration.ofDays(30));
      case "365d" -> now.minus(Duration.ofDays(365));
      case "all" -> createdAt != null ? createdAt : now.minus(Duration.ofDays(365L * 5));
      default -> now.minus(Duration.ofDays(DEFAULT_HISTORY_DAYS));
    };
  }

  /**
   * Parses an ISO {@code yyyy-MM-dd} date, falling back to a default on null/blank/unparseable
   * input so a hand-edited query string never 500s the page.
   *
   * @param value the raw date string, or {@code null}
   * @param fallback the value returned when {@code value} is absent or invalid
   * @return the parsed date, or {@code fallback}
   */
  @NotNull
  private static LocalDate parseDateOrDefault(@Nullable String value, @NotNull LocalDate fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeParseException e) {
      return fallback;
    }
  }
}
