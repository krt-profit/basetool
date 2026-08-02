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

/**
 * Frontend mirror of the backend {@code BulkRebookResultDto}: the outcome of a Massen-Umbuchen
 * (REQ-INV-036). The page reports both numbers so a selection that was largely a no-op does not
 * read as a full success.
 *
 * @param rebooked the number of rows that were moved
 * @param skipped the number of rows that already sat in the requested target state
 */
public record BulkRebookResultDto(int rebooked, int skipped) {}
