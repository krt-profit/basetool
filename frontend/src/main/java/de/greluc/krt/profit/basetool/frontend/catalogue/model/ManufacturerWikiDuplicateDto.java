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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code ManufacturerWikiDuplicateDto}, a manufacturer the SC Wiki
 * lists more than once; must match it field for field.
 *
 * @param manufacturerId the local manufacturer id
 * @param name the manufacturer's displayed name
 * @param abbreviation the manufacturer's displayed abbreviation
 * @param version the manufacturer's {@code @Version}, echoed by a primary choice
 * @param identities the Wiki entries, exactly one of them primary
 */
@DtoMirror
public record ManufacturerWikiDuplicateDto(
    UUID manufacturerId,
    String name,
    String abbreviation,
    long version,
    List<ManufacturerWikiIdentityDto> identities) {

  /** Copies the Wiki entries; an absent list reads as empty. */
  public ManufacturerWikiDuplicateDto {
    identities = identities == null ? List.of() : List.copyOf(identities);
  }
}
