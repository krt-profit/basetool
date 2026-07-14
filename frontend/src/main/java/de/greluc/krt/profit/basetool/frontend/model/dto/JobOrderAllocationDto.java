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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.UUID;

/**
 * Frontend mirror of the backend job-order quantity slice of an inventory entry (Variante C,
 * REQ-INV-027): the amount of an entry's stock earmarked to one job order, rendered as a chip with
 * its amount.
 *
 * @param jobOrderId the earmarked job order's id.
 * @param jobOrderDisplayId the earmarked job order's human-facing display id (chip label).
 * @param amount the SCU/piece amount of the entry earmarked to this job order.
 */
public record JobOrderAllocationDto(UUID jobOrderId, Integer jobOrderDisplayId, Double amount) {}
