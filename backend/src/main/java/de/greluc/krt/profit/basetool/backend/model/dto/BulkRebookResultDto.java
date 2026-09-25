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

/**
 * Outcome of a bulk rebooking (REQ-INV-036): how many marked rows moved and how many were skipped
 * because they already sat in the target state.
 *
 * <p>The two counts always sum to the number of requested ids; any other failure aborts the whole
 * transaction.
 *
 * @param rebooked the number of rows that were moved
 * @param skipped the number of rows that already sat in the requested target state
 */
public record BulkRebookResultDto(int rebooked, int skipped) {}
