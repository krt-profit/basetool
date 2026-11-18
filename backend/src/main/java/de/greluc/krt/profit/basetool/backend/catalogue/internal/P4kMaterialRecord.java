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
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A material as the record of a report row or a review case that no catalogue record stands for:
 * its GUID is the name-based case GUID of its unique name (REQ-P4K-025).
 *
 * @param material the material
 */
record P4kMaterialRecord(@NotNull Material material) implements P4kRecord {

  /**
   * Returns the case GUID of the material.
   *
   * @return the GUID
   */
  @NotNull
  UUID caseGuid() {
    return P4kMaterialGroupReview.caseGuid(material.getName());
  }

  @Override
  public @NotNull String guid() {
    return caseGuid().toString();
  }

  @Override
  public @Nullable String className() {
    return null;
  }

  @Override
  public @Nullable String recordPath() {
    return null;
  }

  @Override
  public @NotNull P4kLocalisedText name() {
    return new P4kLocalisedText(null, material.getName(), null);
  }
}
