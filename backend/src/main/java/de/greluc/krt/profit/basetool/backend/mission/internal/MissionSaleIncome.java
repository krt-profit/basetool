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

import de.greluc.krt.profit.basetool.backend.inventory.api.StockSoldForTarget;
import de.greluc.krt.profit.basetool.backend.mission.api.FinanceType;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Books the mission income of a Lager sale out of a mission's earmark (REQ-INV-027). */
@Component
@RequiredArgsConstructor
public class MissionSaleIncome implements StockSoldForTarget {

  private final MissionParticipantRepository missionParticipantRepository;
  private final MissionFinanceEntryRepository missionFinanceEntryRepository;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull List<UUID> soldForMissions(
      @NotNull UUID sellerUserId, @NotNull List<MissionShare> shares, @NotNull String note) {
    List<MissionFinanceEntry> entries = new ArrayList<>();
    for (MissionShare share : shares) {
      MissionParticipant participant =
          missionParticipantRepository
              .findByMissionIdAndUserId(share.missionId(), sellerUserId)
              .orElse(null);
      if (participant == null) {
        continue;
      }
      MissionFinanceEntry entry = new MissionFinanceEntry();
      entry.setMission(participant.getMission());
      entry.setParticipant(participant);
      entry.setType(FinanceType.INCOME);
      entry.setAmount(share.credit());
      entry.setNote(note);
      entries.add(entry);
    }

    List<UUID> financeEntryIds = new ArrayList<>();
    for (MissionFinanceEntry entry : entries) {
      missionFinanceEntryRepository.save(entry);
      financeEntryIds.add(entry.getId());
    }
    return financeEntryIds;
  }
}
