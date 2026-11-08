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

package de.greluc.krt.profit.basetool.frontend.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.model.AssignableRole;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserRoleChangeRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserRoleSetDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The role-write proxy relays toggles and picker assignments to the backend (REQ-SEC-088). */
class UserRoleProxyControllerTest {

  private static final String ROLE_URI = "/api/v1/users/{id}/roles/{role}";

  private final UUID userId = UUID.randomUUID();
  private BackendApiClient backendApiClient;
  private UserRoleProxyController controller;

  @BeforeEach
  void setUp() {
    backendApiClient = mock(BackendApiClient.class);
    controller = new UserRoleProxyController(new IdentityBackendClient(backendApiClient));
  }

  @Test
  void aToggleIsRelayedWithTheCounterThePageShows() {
    UserRoleChangeRequest request = new UserRoleChangeRequest(false, 4L);
    UserRoleSetDto answer =
        new UserRoleSetDto(userId, List.of(), List.of(AssignableRole.BANK_EMPLOYEE), 5L);
    when(backendApiClient.put(
            eq(ROLE_URI), eq(request), eq(UserRoleSetDto.class), eq(userId), eq("BANK_EMPLOYEE")))
        .thenReturn(answer);

    assertThat(controller.changeRole(userId, AssignableRole.BANK_EMPLOYEE, request))
        .isEqualTo(answer);
  }

  @Test
  void aPickedUserIsAssignedAgainstTheirCurrentCounter() {
    when(backendApiClient.get(eq("/api/v1/users/{id}/roles"), eq(UserRoleSetDto.class), eq(userId)))
        .thenReturn(
            new UserRoleSetDto(userId, List.of(), List.of(AssignableRole.BANK_EMPLOYEE), 7L));

    controller.assignRole(userId, AssignableRole.BANK_EMPLOYEE);

    verify(backendApiClient)
        .put(
            eq(ROLE_URI),
            eq(new UserRoleChangeRequest(true, 7L)),
            eq(UserRoleSetDto.class),
            eq(userId),
            eq("BANK_EMPLOYEE"));
  }
}
