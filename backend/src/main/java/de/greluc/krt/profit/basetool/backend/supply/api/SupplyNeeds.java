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

package de.greluc.krt.profit.basetool.backend.supply.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The needs as the purchasing module uses them (plan §5.3): a read of a need and the credit of an
 * accepted purchase delivery. Supply knows nothing of purchasing; the reverse direction is the
 * {@link NeedLifecycleObserver}.
 */
public interface SupplyNeeds {

  /**
   * Reads one need.
   *
   * @param needId the need
   * @return the need, or empty when it does not exist
   */
  @NotNull
  Optional<NeedRef> find(@NotNull UUID needId);

  /**
   * Reads several needs in one statement.
   *
   * @param needIds the needs
   * @return each existing need by id; an unknown id is absent
   */
  @NotNull
  Map<UUID, NeedRef> findAll(@NotNull Collection<UUID> needIds);

  /**
   * Adds an accepted purchase delivery to a need's progress, as covered and as purchased amount,
   * and fulfils the need once it is covered (REQ-SUP-005). A need that is no longer open or paused
   * is left alone. Joins the caller's transaction and leaves the need's version untouched
   * (REQ-SUP-019).
   *
   * @param needId the need
   * @param amount the accepted amount
   */
  void creditPurchase(@NotNull UUID needId, double amount);
}
