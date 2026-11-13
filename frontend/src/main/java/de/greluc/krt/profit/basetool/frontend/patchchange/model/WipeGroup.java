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

package de.greluc.krt.profit.basetool.frontend.patchchange.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;

/**
 * Mirror of the backend's fixed wipe groups a patch change can select (REQ-PATCH-002), in the order
 * the admin page lists them.
 *
 * <p>Must carry every backend constant under the same name, or deserialising a response that
 * contains it fails.
 */
@DtoMirror
public enum WipeGroup {
  /** Food, drinks, medpens and other FPS consumables, magazines and ammunition. */
  CONSUMABLES,

  /** Armour, weapons, clothing and the other weapon attachments. */
  FPS_EQUIPMENT,

  /** Ship components and vehicle weapons. */
  SHIP_COMPONENTS,

  /** Raw materials. */
  MATERIALS_RAW,

  /** Refined materials. */
  MATERIALS_REFINED,

  /** Materials that cannot be refined. */
  MATERIALS_NO_REFINE,

  /** Every item no other group claims. */
  OTHER_ITEMS,

  /** Ships whose origin is in-game. */
  IN_GAME_SHIPS
}
