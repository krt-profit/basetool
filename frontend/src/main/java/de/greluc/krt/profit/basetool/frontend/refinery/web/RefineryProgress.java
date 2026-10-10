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

package de.greluc.krt.profit.basetool.frontend.refinery.web;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Where a refinery order stands in its run and how far it has come, as the refinery list and detail
 * draw it (REQ-REFINERY-019).
 *
 * <p>An open order ({@code OPEN} or {@code IN_PROGRESS}) is ready for pickup once its end has
 * passed or when it carries no start and duration, so its yield can always be stored.
 */
public final class RefineryProgress {

  /** The order statuses of a run that has not been stored or canceled yet. */
  @Unmodifiable public static final List<String> ACTIVE_STATUSES = List.of("OPEN", "IN_PROGRESS");

  /** Every order status, in the order the status filter lists them. */
  @Unmodifiable
  public static final List<String> ALL_STATUSES =
      List.of("OPEN", "IN_PROGRESS", "COMPLETED", "CANCELED");

  /** Minutes in a day, the unit boundary of {@link #formatDuration(long)}. */
  private static final long MINUTES_PER_DAY = 1440;

  /** Minutes in an hour. */
  private static final long MINUTES_PER_HOUR = 60;

  /** Percent of a finished run. */
  private static final int FULL = 100;

  private RefineryProgress() {}

  /** The place of an order in its run: the list segments and the detail's status steps. */
  public enum State {
    /** Open, and its end lies in the future. */
    RUNNING,
    /** Open, and its end has passed or is unknown: the yield can be stored. */
    READY,
    /** The yield was stored; the run is closed. */
    COMPLETED,
    /** The order was canceled. */
    CANCELED
  }

  /**
   * The segment counters of the refinery list; a {@code null} counter could not be loaded.
   *
   * @param running open orders, ready ones included
   * @param ready open orders whose end has passed or is unknown
   * @param completed stored orders
   * @param all orders of every status
   */
  public record Counts(
      @Nullable Long running, @Nullable Long ready, @Nullable Long completed, @Nullable Long all) {}

  /**
   * The end of a run: its start plus its duration.
   *
   * @param startedAt the start of the run, or {@code null} when unknown
   * @param durationMinutes the duration in minutes, or {@code null} when unknown
   * @return the end, or {@code null} when either part is unknown
   */
  @Nullable
  @Contract(pure = true)
  public static Instant endsAt(@Nullable Instant startedAt, @Nullable Long durationMinutes) {
    if (startedAt == null || durationMinutes == null) {
      return null;
    }
    return startedAt.plus(durationMinutes, ChronoUnit.MINUTES);
  }

  /**
   * The state of an order at {@code now}.
   *
   * @param status the order status as the backend names it; an unknown or {@code null} status
   *     counts as open
   * @param endsAt the end of the run, or {@code null} when unknown
   * @param now the instant to judge against
   * @return the state of the order
   */
  @NotNull
  @Contract(pure = true)
  public static State state(
      @Nullable String status, @Nullable Instant endsAt, @NotNull Instant now) {
    if ("COMPLETED".equals(status)) {
      return State.COMPLETED;
    }
    if ("CANCELED".equals(status)) {
      return State.CANCELED;
    }
    return endsAt == null || !endsAt.isAfter(now) ? State.READY : State.RUNNING;
  }

  /**
   * How far a run has come at {@code now}, clamped to {@code 0..100}.
   *
   * @param startedAt the start of the run, or {@code null} when unknown
   * @param durationMinutes the duration in minutes, or {@code null} when unknown
   * @param now the instant to judge against
   * @return the percent done; {@code 100} when the start or duration is unknown or not positive
   */
  @Contract(pure = true)
  public static int progressPercent(
      @Nullable Instant startedAt, @Nullable Long durationMinutes, @NotNull Instant now) {
    if (startedAt == null || durationMinutes == null || durationMinutes <= 0) {
      return FULL;
    }
    long total = durationMinutes * 60_000L;
    long elapsed = Duration.between(startedAt, now).toMillis();
    long percent = elapsed * FULL / total;
    return (int) Math.max(0, Math.min(FULL, percent));
  }

  /**
   * Whole minutes until {@code endsAt}, rounded up so a running order never reads "0 min".
   *
   * @param endsAt the end of the run
   * @param now the instant to count from
   * @return the minutes left, at least {@code 0}
   */
  @Contract(pure = true)
  public static long minutesUntil(@NotNull Instant endsAt, @NotNull Instant now) {
    long seconds = Duration.between(now, endsAt).toSeconds();
    return seconds <= 0 ? 0 : (seconds + 59) / 60;
  }

  /**
   * Whole minutes since {@code endsAt}, rounded down.
   *
   * @param endsAt the end of the run
   * @param now the instant to count to
   * @return the minutes passed, at least {@code 0}
   */
  @Contract(pure = true)
  public static long minutesSince(@NotNull Instant endsAt, @NotNull Instant now) {
    return Math.max(0, Duration.between(endsAt, now).toMinutes());
  }

  /**
   * A duration as the list reads it: "45 min", "3 h 20 min", "2 h", "1 d 4 h".
   *
   * @param minutes the duration in minutes; a negative value counts as zero
   * @return the formatted duration
   */
  @NotNull
  @Contract(pure = true)
  public static String formatDuration(long minutes) {
    long total = Math.max(0, minutes);
    long days = total / MINUTES_PER_DAY;
    long hours = total % MINUTES_PER_DAY / MINUTES_PER_HOUR;
    long mins = total % MINUTES_PER_HOUR;
    if (days > 0) {
      return hours > 0 ? days + " d " + hours + " h" : days + " d";
    }
    if (hours > 0) {
      return mins > 0 ? hours + " h " + mins + " min" : hours + " h";
    }
    return mins + " min";
  }
}
