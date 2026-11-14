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

import jakarta.persistence.PostLoad;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Feeds the relocation lock's guard from the Lager rows' lifecycle (REQ-PATCH-023): a loaded row
 * arms the check of its writing transaction, an updated or deleted row is noted for it. Hibernate
 * takes the listener from the Spring context while it builds the persistence unit, so the guard,
 * which needs the persistence unit, is resolved on first use.
 */
@Component
@RequiredArgsConstructor
class InventoryRelocationListener {

  private final ObjectProvider<InventoryRelocationGuard> guard;

  /**
   * Arms the check of the transaction that loaded the row.
   *
   * @param row the loaded row
   */
  @PostLoad
  void loaded(InventoryItem row) {
    guard.getObject().watch();
  }

  /**
   * Notes a row the transaction updates or deletes.
   *
   * @param row the row about to change
   */
  @PreUpdate
  @PreRemove
  void changing(@NotNull InventoryItem row) {
    guard.getObject().changed(row.getUser().getId(), row.getCreatedAt());
  }
}
