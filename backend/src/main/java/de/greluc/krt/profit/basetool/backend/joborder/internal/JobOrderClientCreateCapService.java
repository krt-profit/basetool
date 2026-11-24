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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientCreateCapException;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientCreateCaps;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts a member's client-created aUEC orders against the caps of {@link
 * JobOrderExchangeProperties} (REQ-OXCH-009). Only orders that store their creating client and
 * member count; orders created in the Basetool never do.
 */
@Service
@RequiredArgsConstructor
class JobOrderClientCreateCapService implements JobOrderClientCreateCaps {

  /** The statuses that hold a slot of the open-order cap: every active status. */
  private static final @Unmodifiable List<JobOrderStatus> OPEN_STATUSES =
      Arrays.stream(JobOrderStatus.values()).filter(JobOrderStatus::isActive).toList();

  private final JobOrderClientCreateRepository clientCreates;
  private final JobOrderExchangeProperties properties;

  /** Tells the UTC day the daily create cap counts in. */
  private final Clock clock = Clock.systemUTC();

  @Override
  @Transactional(readOnly = true)
  public @NotNull Limits limits(@NotNull UUID member) {
    return new Limits(
        properties.openOrderCap(),
        openOrders(member),
        properties.dailyCreateCap(),
        createdToday(member));
  }

  /**
   * Refuses a client create that would pass a cap, the open-order cap first. The caller holds the
   * member's client-create lock, so two creates of one member never both pass a cap.
   *
   * @param member the creating member's user id
   * @throws JobOrderClientCreateCapException with {@link
   *     JobOrderClientCreateCapException.Cap#OPEN_ORDERS} when the member holds the open-order cap
   *     of open or in-progress client-created orders, else with {@link
   *     JobOrderClientCreateCapException.Cap#DAILY_CREATES} when they created the daily create cap
   *     of orders through clients since the start of the UTC day
   */
  public void requireRoom(@NotNull UUID member) {
    if (openOrders(member) >= properties.openOrderCap()) {
      throw new JobOrderClientCreateCapException(JobOrderClientCreateCapException.Cap.OPEN_ORDERS);
    }
    if (createdToday(member) >= properties.dailyCreateCap()) {
      throw new JobOrderClientCreateCapException(
          JobOrderClientCreateCapException.Cap.DAILY_CREATES);
    }
  }

  /**
   * Counts the member's open or in-progress orders created through any client.
   *
   * @param member the creating member's user id
   * @return the orders holding a slot of the open-order cap
   */
  private long openOrders(@NotNull UUID member) {
    return clientCreates.countCreatedInStatus(member, OPEN_STATUSES);
  }

  /**
   * Counts the member's client creates since the start of the current UTC day.
   *
   * @param member the creating member's user id
   * @return the creates at or after today's UTC midnight, whatever their status since
   */
  private long createdToday(@NotNull UUID member) {
    Instant midnight = LocalDate.now(clock).atStartOfDay(ZoneOffset.UTC).toInstant();
    return clientCreates.countCreatedSince(member, midnight);
  }
}
