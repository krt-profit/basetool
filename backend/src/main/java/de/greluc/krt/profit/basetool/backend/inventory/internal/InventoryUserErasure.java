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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes a departing member's Lager rows for the account deletion; the inventory module's
 * participant, which moves with the module (plan §7.6, P4-1).
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryUserErasure implements UserErasureParticipant {

  static final int POSITION = 20;

  private final InventoryItemRepository inventoryItemRepository;

  @Override
  public @NotNull Phase phase() {
    return Phase.DELETE;
  }

  @Override
  public int position() {
    return POSITION;
  }

  /**
   * Deletes every Lager row of the member through one bulk delete, after the Materialbörse has
   * audited the offers standing on them.
   *
   * @param userId the departing member's {@code app_user.id}
   * @param fallbackAdminId unused
   * @return the number of rows removed, reported as {@link Erased#INVENTORY_ROWS}
   */
  @Override
  public @NotNull Map<Erased, Integer> erase(@NotNull UUID userId, @NotNull UUID fallbackAdminId) {
    return Map.of(Erased.INVENTORY_ROWS, inventoryItemRepository.deleteByUserId(userId));
  }
}
