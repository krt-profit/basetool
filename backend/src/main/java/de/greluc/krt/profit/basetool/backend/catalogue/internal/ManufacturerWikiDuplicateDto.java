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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.List;
import java.util.UUID;

/**
 * A local manufacturer the SC Wiki lists more than once, with every Wiki entry it resolves to.
 *
 * @param manufacturerId the local manufacturer id
 * @param name the manufacturer's displayed name
 * @param abbreviation the manufacturer's displayed abbreviation
 * @param version the manufacturer's {@code @Version}, echoed by a primary choice
 * @param identities the Wiki entries, exactly one of them primary
 */
public record ManufacturerWikiDuplicateDto(
    UUID manufacturerId,
    String name,
    String abbreviation,
    long version,
    List<ManufacturerWikiIdentityDto> identities) {

  /** Copies the Wiki entries. */
  public ManufacturerWikiDuplicateDto {
    identities = List.copyOf(identities);
  }
}
