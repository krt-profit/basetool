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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

/**
 * A material field the P4K leads that an admin may override and hand back to the P4K import; the
 * constant names are the backend's path values (REQ-P4K-024, REQ-P4K-025).
 */
public enum MaterialP4kOverride {

  /** The resource group (the material's category). */
  RESOURCE_GROUP,

  /** The refined-from link (the material's refined material). */
  REFINED_MATERIAL
}
