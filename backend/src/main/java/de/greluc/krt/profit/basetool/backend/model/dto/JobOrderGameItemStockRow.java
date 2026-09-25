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
 * Internal projection of one game-item allocation slice to a job order, used to batch the
 * per-(order, game item) earmark sums (REQ-DATA-003).
 *
 * @param jobOrderId the id of the job order the slice is earmarked to.
 * @param gameItemId the id of the row's game item.
 * @param amount the whole units earmarked on the row; never {@code null}.
 */
public record JobOrderGameItemStockRow(UUID jobOrderId, UUID gameItemId, Double amount) {}
