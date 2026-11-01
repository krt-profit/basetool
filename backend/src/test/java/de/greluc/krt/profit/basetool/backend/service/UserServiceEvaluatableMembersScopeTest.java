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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.SquadronVisibility;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The evaluatable-member list is scoped to the caller's officer reach, never to the member-list
 * scope (REQ-ROLE-007): a seat in Staffel A and plain membership of Staffel B lists A's members
 * only.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceEvaluatableMembersScopeTest {

  private static final UUID STAFFEL_A = UUID.randomUUID();
  private static final Pageable PAGE = PageRequest.of(0, 50);

  @Mock private UserRepository userRepository;
  @Mock private SquadronVisibility squadronVisibility;

  @InjectMocks private UserService userService;

  @Test
  void listsOnlyTheMembersOfTheReachUnits() {
    Page<User> members = new PageImpl<>(List.of(new User()));
    when(squadronVisibility.currentOfficerReadScope()).thenReturn(Set.of(STAFFEL_A));
    when(userRepository.findEvaluatableMembers(Set.of(STAFFEL_A), PAGE)).thenReturn(members);

    assertEquals(members, userService.findEvaluatableMembers(PAGE));
    verify(squadronVisibility, never()).currentUserListScopeSquadronIds();
  }

  @Test
  void aCallerWithoutReachGetsAnEmptyPageWithoutQuerying() {
    when(squadronVisibility.currentOfficerReadScope()).thenReturn(Set.of());

    assertTrue(userService.findEvaluatableMembers(PAGE).isEmpty());
    verify(userRepository, never()).findEvaluatableMembers(any(), any());
  }

  @Test
  void anUnrestrictedAdminScopeQueriesEverySquadron() {
    Page<User> members = new PageImpl<>(List.of(new User()));
    when(squadronVisibility.currentOfficerReadScope()).thenReturn(null);
    when(userRepository.findEvaluatableMembers(null, PAGE)).thenReturn(members);

    assertEquals(members, userService.findEvaluatableMembers(PAGE));
  }
}
