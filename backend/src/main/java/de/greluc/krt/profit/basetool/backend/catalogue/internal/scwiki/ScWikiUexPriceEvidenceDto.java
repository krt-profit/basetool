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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ItemCatalogueMetadata;
import java.util.List;

/**
 * Unwraps the Wiki's imported UEX purchase reports.
 *
 * @param purchase derived UEX terminal evidence
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiUexPriceEvidenceDto(List<ItemCatalogueMetadata.UexPriceEvidence> purchase) {

  /** Copies the optional purchase evidence collection. */
  public ScWikiUexPriceEvidenceDto {
    purchase = purchase == null ? List.of() : List.copyOf(purchase);
  }
}
