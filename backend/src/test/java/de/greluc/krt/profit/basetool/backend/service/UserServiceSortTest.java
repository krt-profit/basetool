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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.repository.*;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

/** Verifies that {@link UserService#findAll()} requests case-insensitive username sorting. */
@ExtendWith(MockitoExtension.class)
class UserServiceSortTest {

  @Mock private UserRepository userRepository;

  @Mock private RoleRepository roleRepository;

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private ShipRepository shipRepository;
  @Mock private RefineryOrderRepository refineryOrderRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private SquadronRepository squadronRepository;
  @Mock private AuthHelperService authHelperService;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private OrgUnitMembershipService orgUnitMembershipService;

  @InjectMocks private UserService userService;

  @BeforeEach
  void setUp() {}

  @Test
  void findAll_shouldRequestSortedUsers() {
    when(ownerScopeService.currentUserListScopeSquadronIds()).thenReturn(null);
    when(userRepository.findAllScopedList(
            org.mockito.ArgumentMatchers.<java.util.Collection<java.util.UUID>>any(),
            org.mockito.ArgumentMatchers.<Sort>any()))
        .thenReturn(Collections.emptyList());

    userService.findAll();

    ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);
    verify(userRepository)
        .findAllScopedList(org.mockito.ArgumentMatchers.isNull(), sortCaptor.capture());

    Sort capturedSort = sortCaptor.getValue();
    Sort.Order order = capturedSort.getOrderFor("username");

    assertThat(order).isNotNull();
    assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    assertThat(order.isIgnoreCase()).isTrue();
  }
}
