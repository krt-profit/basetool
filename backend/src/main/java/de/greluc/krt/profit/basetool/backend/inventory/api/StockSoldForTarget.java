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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import de.greluc.krt.profit.basetool.backend.annotation.ObserverSpi;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Books the income of Lager stock sold out of a mission's earmark, inside the book-out's
 * transaction (plan §5.3, write family 7, REQ-INV-027); implemented by the mission module. Every
 * implementation joins the caller's transaction ({@code MANDATORY}).
 */
@ObserverSpi
public interface StockSoldForTarget {

  /**
   * One mission's share of a sale.
   *
   * @param missionId the mission the sold stock was earmarked to
   * @param credit the mission's share of the proceeds, positive
   */
  record MissionShare(@NotNull UUID missionId, @NotNull BigDecimal credit) {}

  /**
   * Books each share as squadron income of its mission, credited to the seller's participation; a
   * mission the seller does not take part in books nothing.
   *
   * @param sellerUserId the selling member
   * @param shares the missions' shares, in the order of the reductions
   * @param note the entries' note
   * @return the ids of the booked entries
   */
  @NotNull
  List<UUID> soldForMissions(
      @NotNull UUID sellerUserId, @NotNull List<MissionShare> shares, @NotNull String note);
}
