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
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant.Phase;
import de.greluc.krt.profit.basetool.backend.repository.MissionOwnershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The mission module's reassignment part of the account deletion. */
@ExtendWith(MockitoExtension.class)
class MissionOwnerReassignmentTest {

  @Mock private UserRepository userRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private MissionOwnershipRepository missionOwnershipRepository;

  @InjectMocks private MissionOwnerReassignment participant;

  private final UUID userId = UUID.randomUUID();
  private final UUID adminId = UUID.randomUUID();

  @Test
  void runsInTheReassignPhaseAtItsPosition() {
    assertThat(participant.phase()).isEqualTo(Phase.REASSIGN);
    assertThat(participant.position()).isEqualTo(10);
  }

  @Test
  void handsTheMissionsThenTheOwnershipCompanionsToTheAdmin() {
    User departing = new User();
    User admin = new User();
    when(userRepository.getReferenceById(userId)).thenReturn(departing);
    when(userRepository.getReferenceById(adminId)).thenReturn(admin);

    assertThat(participant.erase(userId, adminId)).isEmpty();

    InOrder order = inOrder(missionRepository, missionOwnershipRepository);
    order.verify(missionRepository).updateOwner(departing, admin);
    order.verify(missionOwnershipRepository).updateOwner(departing, admin);
    verifyNoMoreInteractions(missionRepository, missionOwnershipRepository);
  }
}
