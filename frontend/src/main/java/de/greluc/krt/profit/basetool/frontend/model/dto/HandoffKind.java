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
 * Kind of draft a one-click ingest handoff carries, mirroring the ingest gateway's {@code
 * HandoffKind}; tells the frontend which review surface to pre-fill.
 */
public enum HandoffKind {
  /** A refinery-order draft; pre-fills the refinery create form. */
  REFINERY,

  /** A personal-blueprint import preview; pre-fills the blueprint import preview. */
  BLUEPRINT
}
