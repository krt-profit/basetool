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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant.Erased;
import de.greluc.krt.profit.basetool.backend.platform.api.UserErasureParticipant.Phase;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The refinery module's reassignment part of the account deletion. */
@ExtendWith(MockitoExtension.class)
class RefineryOwnerReassignmentTest {

  @Mock private UserRepository userRepository;
  @Mock private RefineryOrderRepository refineryOrderRepository;

  @InjectMocks private RefineryOwnerReassignment participant;

  private final UUID userId = UUID.randomUUID();
  private final UUID adminId = UUID.randomUUID();

  @Test
  void runsInTheReassignPhaseAtItsPosition() {
    assertThat(participant.phase()).isEqualTo(Phase.REASSIGN);
    assertThat(participant.position()).isEqualTo(20);
  }

  @Test
  void handsTheOrdersToTheAdminAndReportsTheCount() {
    User departing = new User();
    User admin = new User();
    when(userRepository.getReferenceById(userId)).thenReturn(departing);
    when(userRepository.getReferenceById(adminId)).thenReturn(admin);
    when(refineryOrderRepository.updateOwner(departing, admin)).thenReturn(2);

    assertThat(participant.erase(userId, adminId))
        .isEqualTo(Map.of(Erased.REFINERY_ORDERS_REASSIGNED, 2));
    verify(refineryOrderRepository).updateOwner(departing, admin);
    verifyNoMoreInteractions(refineryOrderRepository);
  }
}
