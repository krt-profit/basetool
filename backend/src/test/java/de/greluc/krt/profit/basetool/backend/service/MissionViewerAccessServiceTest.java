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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

/**
 * Unit tests for {@link MissionViewerAccessService}, verifying its delegation to {@link
 * AuthHelperService} and {@link MissionSecurityService}, in particular that {@code
 * canManageManagers} calls {@link MissionSecurityService#canManageManagers}.
 */
@ExtendWith(MockitoExtension.class)
class MissionViewerAccessServiceTest {

  @Mock private AuthHelperService authHelperService;

  @Mock private MissionSecurityService missionSecurityService;

  @InjectMocks private MissionViewerAccessService missionViewerAccessService;

  @Test
  void isAuthenticated_delegatesToAuthHelper() {
    when(authHelperService.isAuthenticated()).thenReturn(true);

    boolean result = missionViewerAccessService.isAuthenticated();

    assertTrue(result, "isAuthenticated must return the AuthHelperService result verbatim");
    verify(authHelperService).isAuthenticated();
  }

  /**
   * REQ-SEC-041: the mission {@code description} redaction hangs off this delegation, and it must
   * be membership rather than bare authentication — a role-less GUEST is authenticated yet is a
   * mission outsider, and the detail endpoint has always hidden the description from that tier.
   */
  @Test
  void isMemberOrAbove_delegatesToAuthHelper() {
    when(authHelperService.isMemberOrAbove()).thenReturn(false);

    boolean result = missionViewerAccessService.isMemberOrAbove();

    assertFalse(result, "isMemberOrAbove must return the AuthHelperService result verbatim");
    verify(authHelperService).isMemberOrAbove();
    verify(authHelperService, never()).isAuthenticated();
  }

  @Test
  void canManageMission_delegatesWithRawAuthentication() {
    UUID missionId = UUID.randomUUID();
    Authentication authentication = mock(Authentication.class);
    when(authHelperService.rawAuthentication()).thenReturn(authentication);
    when(missionSecurityService.canManageMission(missionId, authentication)).thenReturn(true);

    boolean result = missionViewerAccessService.canManageMission(missionId);

    assertTrue(result, "canManageMission must return the MissionSecurityService result verbatim");
    verify(missionSecurityService).canManageMission(missionId, authentication);
    verify(missionSecurityService, never()).canManageManagers(missionId, authentication);
  }

  @Test
  void canManageManagers_delegatesToSecurityServiceCanManageManagers() {
    UUID missionId = UUID.randomUUID();
    Authentication authentication = mock(Authentication.class);
    when(authHelperService.rawAuthentication()).thenReturn(authentication);
    when(missionSecurityService.canManageManagers(missionId, authentication)).thenReturn(true);

    boolean result = missionViewerAccessService.canManageManagers(missionId);

    assertTrue(result, "canManageManagers must return the MissionSecurityService result verbatim");
    verify(missionSecurityService).canManageManagers(missionId, authentication);
    verify(missionSecurityService, never()).canManageMission(missionId, authentication);
  }

  @Test
  void canManageMission_returnsFalseWhenSecurityServiceDenies() {
    UUID missionId = UUID.randomUUID();
    Authentication authentication = mock(Authentication.class);
    when(authHelperService.rawAuthentication()).thenReturn(authentication);
    when(missionSecurityService.canManageMission(missionId, authentication)).thenReturn(false);

    boolean result = missionViewerAccessService.canManageMission(missionId);

    assertFalse(result, "canManageMission must propagate a deny decision unchanged");
  }
}
