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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.FrequencyTypeRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/** Normalization shared by the mission-wide frequencies of templates and new missions. */
final class MissionFrequencyRows {

  private static final int VALUE_SCALE = 2;

  private MissionFrequencyRows() {}

  /**
   * Trims the custom labels, scales the values to two decimals and checks each row's identity.
   *
   * @param rows the submitted channels in order
   * @param frequencyTypes the catalogue the typed channels must exist in
   * @return the normalized channels in submitted order
   * @throws BadRequestException when a row is both typed and named, neither, or repeats a type
   * @throws NotFoundException when a typed row names an unknown frequency type
   */
  static @Unmodifiable List<MissionTemplatePlan.Frequency> normalize(
      @NotNull List<MissionTemplatePlan.Frequency> rows,
      @NotNull FrequencyTypeRepository frequencyTypes) {
    Set<UUID> typeIds = new HashSet<>();
    List<MissionTemplatePlan.Frequency> normalized =
        rows.stream().map(row -> normalize(row, typeIds)).toList();
    if (!typeIds.isEmpty() && frequencyTypes.findAllById(typeIds).size() != typeIds.size()) {
      throw new NotFoundException("FrequencyType not found");
    }
    return normalized;
  }

  private static MissionTemplatePlan.Frequency normalize(
      MissionTemplatePlan.Frequency row, Set<UUID> typeIds) {
    String name = StringNormalization.trimToNull(row.name());
    UUID typeId = row.frequencyTypeId();
    if ((typeId == null) == (name == null)) {
      throw new BadRequestException("A frequency needs either a frequency type or a name");
    }
    if (typeId != null && !typeIds.add(typeId)) {
      throw new BadRequestException("A frequency type may appear only once");
    }
    return new MissionTemplatePlan.Frequency(
        typeId, name, row.value().setScale(VALUE_SCALE, RoundingMode.HALF_UP));
  }
}
