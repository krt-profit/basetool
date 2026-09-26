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

package de.greluc.krt.profit.basetool.backend.service;

import java.util.List;

/**
 * The blueprint products every Star Citizen account starts with unlocked (REQ-INV-016), used only
 * as the one-time seed of the admin-managed {@code default_blueprint} table (REQ-INV-017).
 *
 * <p>Names are the display spellings of the external blueprint manager (scmbd.net).
 */
public final class DefaultBlueprintCatalog {

  /**
   * Display names of the default starter blueprints (scmbd.net spelling). Insertion order is
   * irrelevant — identity is the normalized product key.
   */
  public static final List<String> STARTER_PRODUCT_NAMES =
      List.of(
          "S-38 Magazine (20 cap)",
          "P4-AR Magazine (40 cap)",
          "Field Recon Suit Arms",
          "Field Recon Suit Core",
          "Field Recon Suit Helmet",
          "Field Recon Suit Legs",
          "S-38 Pistol",
          "P4-AR Rifle");

  private DefaultBlueprintCatalog() {}
}
