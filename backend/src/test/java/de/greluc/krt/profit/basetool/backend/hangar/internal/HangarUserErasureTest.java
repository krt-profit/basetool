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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant.Erased;
import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant.Phase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The hangar's part of the account deletion. */
@ExtendWith(MockitoExtension.class)
class HangarUserErasureTest {

  @Mock private ShipRepository shipRepository;
  @Mock private HangarUnitLiveSync unitLiveSync;

  @InjectMocks private HangarUserErasure participant;

  private final UUID userId = UUID.randomUUID();
  private final UUID adminId = UUID.randomUUID();

  @Test
  void runsInTheDeletePhaseAtItsPosition() {
    assertThat(participant.phase()).isEqualTo(Phase.DELETE);
    assertThat(participant.position()).isEqualTo(30);
  }

  @Test
  void deletesTheShipsOfTheMemberReportsTheCountAndTellsTheirUnits() {
    UUID unit = UUID.randomUUID();
    when(shipRepository.findOrgUnitIdsByOwnerId(userId)).thenReturn(List.of(unit));
    when(shipRepository.deleteByOwnerId(userId)).thenReturn(7);

    assertThat(participant.erase(userId, adminId)).isEqualTo(Map.of(Erased.SHIPS, 7));
    verify(shipRepository).deleteByOwnerId(userId);
    verify(unitLiveSync).unitsChanged(List.of(unit));
  }

  @Test
  void announcesNothingWhenTheMemberHadNoShips() {
    when(shipRepository.deleteByOwnerId(userId)).thenReturn(0);

    assertThat(participant.erase(userId, adminId)).isEqualTo(Map.of(Erased.SHIPS, 0));
    verifyNoInteractions(unitLiveSync);
  }
}
