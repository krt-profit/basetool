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

package de.greluc.krt.profit.basetool.backend.personalinventory.internal;

import de.greluc.krt.profit.basetool.backend.personalinventory.api.PersonalInventoryErasure;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Deletes a departing owner's "Mein Inventar" rows for the GDPR user deletion. */
@Component
@RequiredArgsConstructor
public class PersonalInventoryErasureService implements PersonalInventoryErasure {

  private final PersonalInventoryItemRepository personalInventoryItemRepository;

  /**
   * Deletes every row of the owner through one bulk delete; requires the caller's transaction.
   *
   * @param ownerUserId the departing owner's {@code app_user.id}
   * @return the number of deleted rows
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int deleteAllItemsOf(@NotNull UUID ownerUserId) {
    return personalInventoryItemRepository.deleteByOwnerUserId(ownerUserId);
  }
}
