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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant.Phase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The mission module's unlink part of the account deletion. */
@ExtendWith(MockitoExtension.class)
class MissionMemberUnlinkTest {

  @Mock private MissionRepository missionRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;

  @InjectMocks private MissionMemberUnlink participant;

  private final UUID userId = UUID.randomUUID();
  private final UUID adminId = UUID.randomUUID();

  @Test
  void runsInTheUnlinkPhaseAtItsPosition() {
    assertThat(participant.phase()).isEqualTo(Phase.UNLINK);
    assertThat(participant.position()).isEqualTo(10);
  }

  @Test
  void removesTheManagerSeatsThenClearsTheParticipantLinks() {
    assertThat(participant.erase(userId, adminId)).isEmpty();

    InOrder order = inOrder(missionRepository, missionParticipantRepository);
    order.verify(missionRepository).removeManager(userId);
    order.verify(missionParticipantRepository).unlinkUser(userId);
    verifyNoMoreInteractions(missionRepository, missionParticipantRepository);
  }
}
