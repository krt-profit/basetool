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
 * Frontend mirror of the backend {@code BulkRebookMode}: which move a bulk rebooking
 * (Massen-Umbuchen, REQ-INV-036) performs. The two personal directions are explicit modes rather
 * than inferred from the source rows, because a bulk selection can mix personal and shared stock.
 */
public enum BulkRebookMode {

  /** Move the marked rows to a target user / location / owning org-unit pool. */
  LOCATION,

  /** Mark the marked rows as the owner's personal stock. */
  PERSONALIZE,

  /** Move the marked rows into the shared squadron pool. */
  DEPERSONALIZE
}
