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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The evidence that two materials stand for one commodity, which a material merge reads again under
 * the row locks (REQ-DATA-046).
 */
enum MaterialMergeRule {

  /**
   * A second UEX listing of a commodity: the merged material holds a UEX commodity id but no P4K
   * GUID, and its SC Wiki UUID is the {@code ResourceType} (REQ-P4K-070) of the live record whose
   * GUID the material that stays holds, beside a UEX commodity id of its own and no SC Wiki UUID.
   */
  UEX_WIKI_KEY;

  /**
   * Tells whether the evidence still holds for the two rows.
   *
   * @param retired the material to merge away
   * @param survivor the material that stays
   * @return whether the merge may proceed
   */
  boolean holds(@NotNull Material retired, @NotNull Material survivor) {
    return switch (this) {
      case UEX_WIKI_KEY -> isSecondUexListing(retired, survivor);
    };
  }

  /**
   * Checks the {@link #UEX_WIKI_KEY} evidence.
   *
   * @param retired the material to merge away
   * @param survivor the material that stays
   * @return whether the retired row is a second UEX listing of the survivor's commodity
   */
  private static boolean isSecondUexListing(@NotNull Material retired, @NotNull Material survivor) {
    UUID wikiKey = retired.getScwikiUuid();
    Integer twinListing = retired.getIdCommodity();
    Integer ownListing = survivor.getIdCommodity();
    return wikiKey != null
        && twinListing != null
        && ownListing != null
        && !ownListing.equals(twinListing)
        && retired.getP4kUuid() == null
        && survivor.getP4kUuid() != null
        && survivor.getP4kDeletedAt() == null
        && survivor.getScwikiUuid() == null
        && wikiKey.equals(survivor.getP4kSubtypeUuid());
  }
}
