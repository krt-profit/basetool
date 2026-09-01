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
 * Flat, internal projection of one job-order-linked <em>game-item</em> stock slice — the item
 * sibling of {@link JobOrderMaterialStockRow}, used to batch the per-(order, game item) earmark
 * sums behind the item-mode allocation pickers (REQ-INV-039, #1742) into a single query
 * (REQ-DATA-003).
 *
 * <p>It carries the <b>allocation</b> amount rather than the row amount: since Variante C
 * (REQ-INV-027) a stock row may be split across several orders, and an order is credited only its
 * own slice. There is no quality dimension — item rows carry none (REQ-INV-029) — which is why this
 * projection has one field fewer than its material sibling rather than a nullable grade.
 *
 * @param jobOrderId the id of the job order the slice is earmarked to.
 * @param gameItemId the id of the row's game item.
 * @param amount the whole units this order has earmarked on the row; never {@code null} for a
 *     persisted allocation.
 */
public record JobOrderGameItemStockRow(UUID jobOrderId, UUID gameItemId, Double amount) {}
