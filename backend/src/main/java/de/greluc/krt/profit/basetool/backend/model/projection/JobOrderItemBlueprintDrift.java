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

package de.greluc.krt.profit.basetool.backend.model.projection;

import java.util.UUID;

/**
 * One ordered-item line whose chosen blueprint no longer produces the ordered game item
 * (REQ-ORDERS-033). Produced by the integrity sweep's constructor-expression query, which joins the
 * line to its game item and blueprint and keeps only the rows where the blueprint's output item is
 * absent or different.
 *
 * <p>Carries only catalogue-derived names — never the order's user-entered {@code handle} — so the
 * sweep can log a row verbatim without leaking user free text into the log stream (REQ-OBS rule "no
 * names, emails or tokens").
 *
 * @param itemId the drifted {@code job_order_item} row
 * @param orderId the owning order's primary key
 * @param orderDisplayId the owning order's human-facing sequential number, for the operator log
 * @param orderedItemName the name of the game item the line orders
 * @param blueprintOutputName the blueprint's current output name — what it produces *now*
 * @param blueprintKey the blueprint's SC Wiki key, the stable handle for upstream investigation
 */
public record JobOrderItemBlueprintDrift(
    UUID itemId,
    UUID orderId,
    Integer orderDisplayId,
    String orderedItemName,
    String blueprintOutputName,
    String blueprintKey) {}
