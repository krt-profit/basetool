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
 * One job-order slice of an inventory entry: the earmarked order and the amount allocated to it
 * (REQ-INV-027).
 *
 * @param jobOrderId the earmarked job order's id
 * @param jobOrderDisplayId the order's human display id
 * @param amount the quantity allocated to this order (SCU, 3-decimal)
 */
public record JobOrderAllocationDto(UUID jobOrderId, Integer jobOrderDisplayId, Double amount) {}
