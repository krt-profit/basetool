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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Data transfer record carrying Manufacturer payload.
 *
 * @param id the manufacturer's id
 * @param name its name
 * @param abbreviation its short display code
 * @param nickname its UEX nickname
 * @param wiki its UEX wiki link
 * @param description its description
 * @param hidden whether an admin hid it from the pickers
 * @param nameDe its German name, or {@code null} (REQ-P4K-023)
 * @param p4kUuid its P4K GUID, the key of its Fan Kit logo (REQ-P4K-022), or {@code null}
 */
public record ManufacturerDto(
    UUID id,
    String name,
    String abbreviation,
    String nickname,
    String wiki,
    String description,
    boolean hidden,
    @Nullable String nameDe,
    @Nullable UUID p4kUuid) {

  /**
   * Creates a manufacturer without a German name and a P4K GUID.
   *
   * @param id the manufacturer's id
   * @param name its name
   * @param abbreviation its short display code
   * @param nickname its UEX nickname
   * @param wiki its UEX wiki link
   * @param description its description
   * @param hidden whether an admin hid it from the pickers
   */
  public ManufacturerDto(
      UUID id,
      String name,
      String abbreviation,
      String nickname,
      String wiki,
      String description,
      boolean hidden) {
    this(id, name, abbreviation, nickname, wiki, description, hidden, null, null);
  }
}
