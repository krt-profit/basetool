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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import java.util.List;
import java.util.UUID;

/**
 * The donation statistics of a period (REQ-SUP-013): per receiving unit and month the number of
 * donations, the distinct donors and the amount per material; the per-donor list only for the
 * governing leadership.
 *
 * @param months the months the period covers, oldest first, as {@code YYYY-MM}
 * @param units the rows of the units the caller may see
 * @param donors the per-donor totals, empty unless the caller belongs to the governing leadership
 */
public record SupplyStatsDto(List<String> months, List<UnitMonth> units, List<DonorTotal> donors) {

  /**
   * One receiving unit's figures in one month.
   *
   * @param orgUnitId the receiving unit
   * @param orgUnitName the receiving unit's name
   * @param month the calendar month as {@code YYYY-MM}
   * @param donations the number of received donations
   * @param distinctDonors the number of distinct identified donors
   * @param amounts the received amount per material or item
   */
  public record UnitMonth(
      UUID orgUnitId,
      String orgUnitName,
      String month,
      long donations,
      long distinctDonors,
      List<EntryAmount> amounts) {}

  /**
   * An amount of one material or item.
   *
   * @param entry the material or item name
   * @param amount the received amount
   */
  public record EntryAmount(String entry, double amount) {}

  /**
   * One donor's received totals of one material or item.
   *
   * @param donorName the donor's display name
   * @param entry the material or item name
   * @param donations the number of received donations
   * @param amount the received amount
   */
  public record DonorTotal(String donorName, String entry, long donations, double amount) {}
}
