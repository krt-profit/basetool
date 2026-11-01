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

import de.greluc.krt.profit.basetool.backend.inventory.api.BookInPolicy;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryScope;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.scope.api.UserAccessPolicy;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The inventory module's access policy: the read and edit gates of a Lager row and the on-behalf
 * book-in pre-check, evaluated on the scope kernel (plan §5.4, ADR-0236). It also answers the other
 * modules' book-in checks ({@link BookInPolicy}).
 *
 * <p>The list-side half of the rule is {@link InventoryScope#TRIPLE}; both widen together.
 *
 * <p>Invoked from SpEL as {@code @inventoryAccessPolicy}. Unknown ids are refused. Read-only
 * transactional.
 */
@Service(InventoryAccessPolicy.BEAN_NAME)
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryAccessPolicy implements BookInPolicy {

  /** The bean name the {@code @PreAuthorize} expressions reference. */
  public static final String BEAN_NAME = "inventoryAccessPolicy";

  private final OwnerScopeService ownerScopeService;
  private final InventoryItemRepository inventoryItemRepository;
  private final UserAccessPolicy userAccessPolicy;

  /**
   * Checks whether the caller may read Lager row {@code itemId}, applying the owner escape
   * (REQ-ORG-011), then the ownerless rule, then the org-unit read scope.
   *
   * @param itemId the row to inspect; never {@code null}
   * @return {@code true} iff the caller may read the row
   */
  public boolean canSeeInventoryItem(@NotNull UUID itemId) {
    return inventoryItemRepository
        .findById(itemId)
        .map(i -> ownerScopeService.permitsOwnedRow(i.getUser(), i.getOwningOrgUnit(), false))
        .orElse(false);
  }

  /**
   * Checks whether the caller may edit Lager row {@code itemId}, applying the owner escape
   * (REQ-ORG-011), then the ownerless rule, then the org-unit edit scope.
   *
   * @param itemId the row to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the row
   */
  public boolean canEditInventoryItem(@NotNull UUID itemId) {
    return inventoryItemRepository
        .findById(itemId)
        .map(i -> ownerScopeService.permitsOwnedRow(i.getUser(), i.getOwningOrgUnit(), true))
        .orElse(false);
  }

  /**
   * Checks whether the caller handles Lager row {@code itemId} as Logistician: a Logistician grant
   * in the row's owning unit inside the active scope, or an admin. A personal row without a unit is
   * reserved to admins (REQ-SEC-005).
   *
   * @param itemId the row to inspect; never {@code null}
   * @return {@code true} iff the caller may act on the row as Logistician; {@code false} for an
   *     unknown row
   */
  public boolean isLogisticianOfItem(@NotNull UUID itemId) {
    return inventoryItemRepository
        .findById(itemId)
        .map(i -> ownerScopeService.canActAsLogisticianInOwner(owningUnitId(i)))
        .orElse(false);
  }

  /**
   * Returns the id of the unit that owns {@code item}, or {@code null} for an ownerless row.
   *
   * @param item the row
   * @return the owning unit id, or {@code null}
   */
  @Nullable
  private static UUID owningUnitId(@NotNull InventoryItem item) {
    return item.getOwningOrgUnit() == null ? null : item.getOwningOrgUnit().getId();
  }

  /**
   * Coarse pre-check for creating Lager rows in another member's name: admin, self, or a shared
   * editable org unit (REQ-SEC-005). The per-row bound is the stamp validation.
   *
   * @param targetUserId the member whose Lager would receive the row; never {@code null}
   * @return {@code true} iff the caller may create Lager rows in that member's name
   */
  public boolean canManageUserInventory(@NotNull UUID targetUserId) {
    return userAccessPolicy.canActOnTargetUser(targetUserId, true);
  }

  @Override
  public boolean mayBookInFor(@NotNull UUID ownerUserId) {
    return canManageUserInventory(ownerUserId);
  }
}
