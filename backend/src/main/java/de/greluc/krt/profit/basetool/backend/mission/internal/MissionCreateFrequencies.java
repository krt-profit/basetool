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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.FrequencyTypeRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFrequency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Checks and stores the mission-wide frequencies submitted together with a new mission. */
@Component
@RequiredArgsConstructor
class MissionCreateFrequencies {

  private final MissionFrequencyRepository missionFrequencies;
  private final FrequencyTypeRepository frequencyTypes;
  private final AuditRecorder auditRecorder;

  /**
   * Normalizes the submitted channels before the mission is written.
   *
   * @param rows the submitted typed and custom channels in order
   * @return the normalized channels in submitted order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when a row is both
   *     typed and named, neither, or repeats a frequency type
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when a typed row names
   *     an unknown frequency type
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public @Unmodifiable List<MissionTemplatePlan.Frequency> normalize(
      @NotNull List<MissionTemplatePlan.Frequency> rows) {
    return MissionFrequencyRows.normalize(rows, frequencyTypes);
  }

  /**
   * Attaches normalized channels to the new mission and records each one in the audit trail.
   *
   * @param mission the managed, already-persisted mission
   * @param rows channels returned by {@link #normalize(List)}
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void attach(@NotNull Mission mission, @NotNull List<MissionTemplatePlan.Frequency> rows) {
    for (MissionTemplatePlan.Frequency row : rows) {
      UUID typeId = row.frequencyTypeId();
      MissionFrequency frequency = new MissionFrequency();
      frequency.setMission(mission);
      if (typeId == null) {
        frequency.setName(Objects.requireNonNull(row.name()));
      } else {
        frequency.setFrequencyType(frequencyTypes.getReferenceById(typeId));
      }
      frequency.setValue(row.value());
      mission.getFrequencies().add(frequency);
      MissionFrequency saved = missionFrequencies.save(frequency);
      auditRecorder.record(
          AuditEventType.MISSION_FREQUENCY_CHANGED,
          mission.getId(),
          mission.getName(),
          null,
          typeId == null
              ? AuditDetails.of("custom", saved.getId())
              : AuditDetails.of("frequencyType", typeId));
    }
  }
}
