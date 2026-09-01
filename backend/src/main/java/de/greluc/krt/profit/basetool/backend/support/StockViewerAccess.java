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

package de.greluc.krt.profit.basetool.backend.support;

import java.util.UUID;

/**
 * Caller-aware access seam the stock mappers use to fill the viewer-dependent {@code canEdit} field
 * of an inventory-item or job-order DTO.
 *
 * <p>The same dependency inversion as {@link MissionViewerAccess} and for the same reason
 * (ADR-0047, cycle cleanup): the {@code service} layer already depends on {@code mapper}, so a
 * {@code mapper} &rarr; {@code service} edge would close a package cycle. Mappers reach neither
 * {@code SecurityContextHolder} (ArchUnit {@code mapperLayerShouldNotReachIntoSecurityContext}) nor
 * the {@code service} layer; they depend on this leaf interface, whose implementation lives in
 * {@code service} and is the only thing that touches the gates.
 *
 * <p><strong>Why the DTO carries this at all.</strong> A client cannot re-derive it. The rules are
 * per-row and hierarchy-aware — an admin holds no Staffel membership yet may edit every row, and a
 * Logistician may edit their own Staffel's rows and not another's. Every attempt to reproduce that
 * in a client reproduces the role hierarchy badly and gets it wrong for exactly the people most
 * entitled to act, which is what {@code MissionDto.canEdit} already exists to prevent.
 *
 * <p>Both methods take an id rather than an entity on purpose. The implementations open with a
 * {@code findById}, which for a row the caller is already mapping is served from Hibernate's
 * first-level cache rather than a second query — so an id-taking seam keeps the {@code support}
 * package free of {@code model} types without costing a round trip per row.
 */
public interface StockViewerAccess {

  /**
   * Reports whether the current caller may write to one Lager row.
   *
   * <p>The rule the write endpoints enforce: the caller owns the row, or holds edit rights on the
   * row's org unit. There is no additional role gate — {@code InventoryItemController} guards its
   * writes with {@code isAuthenticated() and @ownerScopeService.canEditInventoryItem(#id)} — so a
   * member editing their own stock passes without any grant.
   *
   * @param inventoryItemId the Lager row to test.
   * @return {@code true} iff the current caller may write to it.
   */
  boolean canEditInventoryItem(UUID inventoryItemId);

  /**
   * Reports whether the current caller may edit one job order.
   *
   * <p><strong>Both halves of the endpoint's rule.</strong> {@code JobOrderController} guards its
   * writes with {@code hasRole('LOGISTICIAN') and @ownerScopeService.canEditJobOrder(#id)}. The
   * scope half alone would admit a plain member whose own Staffel owns the order, which the
   * endpoint does not permit; the role half alone would admit a Logistician of an unrelated
   * Staffel. A DTO flag built on this therefore agrees with the endpoint rather than approximating
   * it.
   *
   * @param jobOrderId the order to test.
   * @return {@code true} iff the current caller may edit it.
   */
  boolean mayEditJobOrder(UUID jobOrderId);
}
