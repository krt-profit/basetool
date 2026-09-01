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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.util.UUID;

/**
 * One ITEM order's outstanding need for a single game item — the item sibling of {@link
 * JobOrderMaterialNeedDto} (REQ-INV-039, #1742), labelling the item-mode allocation pickers so a
 * member booking finished items in can see what each order still wants without opening it.
 *
 * <p><b>It is a different calculation, not the material one with a different unit.</b> An item line
 * carries three counts rather than a single remaining quantity (`JobOrderItemDto`), and the two
 * obvious subtractions are both wrong: {@code amount − deliveredAmount} ignores units already
 * earmarked and would invite promising the same pieces twice, while {@code amount −
 * manufacturedAmount} measures production rather than the gap a member with purchased items can
 * close.
 *
 * <p>The figure is therefore {@code amount − delivered − allocated}. It is stable across a handover
 * because {@code JobOrderItemHandoverService} consumes the order's earmarked stock as it delivers:
 * the units move from {@code allocatedAmount} into {@code deliveredAmount} and the outstanding
 * figure does not twitch. {@code manufacturedAmount} deliberately plays no part — production books
 * its output into the Lager earmarked to the order, so those units are already counted in {@code
 * allocatedAmount}; and a producer who books the output in <em>without</em> the earmark
 * (`allocateToOrder = false`) has created stock that is committed to nobody, which the material
 * side does not count either.
 *
 * <p>Whole units throughout (REQ-INV-029): item stock is counted in pieces and carries no quality
 * dimension, which is why this record has no quality floor where its material sibling does.
 *
 * @param gameItemId the game item this order still wants — the id the item-mode picker filters on
 *     ({@code requiredGameItemIds}, REQ-INV-031)
 * @param orderedAmount whole units requested, summed over the order's lines naming this game item
 * @param deliveredAmount whole units already handed over on those lines (REQ-ORDERS-025)
 * @param allocatedAmount whole units of item stock currently earmarked to this order for the game
 *     item, summed over the entries' own slices (Variante C, REQ-INV-027) — never the whole row
 * @param outstandingAmount {@code orderedAmount − deliveredAmount − allocatedAmount}, floored at 0:
 *     what still has to be procured or built. The floor is not cosmetic, but the mechanism is
 *     <b>over-earmarking</b>, not a handover: {@code deliveredAmount} can never exceed {@code
 *     orderedAmount} (the line's own invariant, and {@code JobOrderItemHandoverService} refuses
 *     over-delivery), while nothing caps an earmark against what the order still wants — {@code
 *     InventoryItemService.assertGameItemRequiredByJobOrder} checks only that the order requests
 *     the game item at all. A member may therefore earmark more units to an order than it has left
 *     to receive, and the raw difference goes negative
 */
public record JobOrderGameItemNeedDto(
    UUID gameItemId,
    Integer orderedAmount,
    Integer deliveredAmount,
    Integer allocatedAmount,
    Integer outstandingAmount) {}
