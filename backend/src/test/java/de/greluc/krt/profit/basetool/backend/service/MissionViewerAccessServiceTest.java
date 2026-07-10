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
 * Unit tests for {@link MissionViewerAccessService}, the {@code MissionViewerAccess} adapter the
 * {@code MissionMapper} consults for guest field redaction and manager-management-button
 * visibility.
 *
 * <p>Because this class is a pure delegation seam, the tests assert the wiring rather than any
 * behaviour: {@link MissionViewerAccessService#isAuthenticated()} must route to {@link
 * AuthHelperService}, and both mission checks must fold the {@code (missionId)} call shape into the
 * {@code (missionId, Authentication)} shape of {@link MissionSecurityService} using the raw
 * authentication from {@link AuthHelperService}. In particular {@code canManageManagers} must hit
 * {@link MissionSecurityService#canManageManagers} and never {@code canManageMission} — a mis-wire
 * there would silently over- or under-expose mission-management controls with no other guard.
 */
@ExtendWith(MockitoExtension.class)
class MissionViewerAccessServiceTest {

  @Mock private AuthHelperService authHelperService;

  @Mock private MissionSecurityService missionSecurityService;

  @InjectMocks private MissionViewerAccessService missionViewerAccessService;

  @Test
  void isAuthenticated_delegatesToAuthHelper() {
    // Given
    when(authHelperService.isAuthenticated()).thenReturn(true);

    // When
    boolean result = missionViewerAccessService.isAuthenticated();

    // Then
    assertTrue(result, "isAuthenticated must return the AuthHelperService result verbatim");
    verify(authHelperService).isAuthenticated();
  }

  @Test
  void canManageMission_delegatesWithRawAuthentication() {
    // Given
    UUID missionId = UUID.randomUUID();
    Authentication authentication = mock(Authentication.class);
    when(authHelperService.rawAuthentication()).thenReturn(authentication);
    when(missionSecurityService.canManageMission(missionId, authentication)).thenReturn(true);

    // When
    boolean result = missionViewerAccessService.canManageMission(missionId);

    // Then
    assertTrue(result, "canManageMission must return the MissionSecurityService result verbatim");
    verify(missionSecurityService).canManageMission(missionId, authentication);
    verify(missionSecurityService, never()).canManageManagers(missionId, authentication);
  }

  @Test
  void canManageManagers_delegatesToSecurityServiceCanManageManagers() {
    // Given
    UUID missionId = UUID.randomUUID();
    Authentication authentication = mock(Authentication.class);
    when(authHelperService.rawAuthentication()).thenReturn(authentication);
    when(missionSecurityService.canManageManagers(missionId, authentication)).thenReturn(true);

    // When
    boolean result = missionViewerAccessService.canManageManagers(missionId);

    // Then
    assertTrue(result, "canManageManagers must return the MissionSecurityService result verbatim");
    verify(missionSecurityService).canManageManagers(missionId, authentication);
    // Guards the wire-up defect: canManageManagers must NOT route to canManageMission.
    verify(missionSecurityService, never()).canManageMission(missionId, authentication);
  }

  @Test
  void canManageMission_returnsFalseWhenSecurityServiceDenies() {
    // Given
    UUID missionId = UUID.randomUUID();
    Authentication authentication = mock(Authentication.class);
    when(authHelperService.rawAuthentication()).thenReturn(authentication);
    when(missionSecurityService.canManageMission(missionId, authentication)).thenReturn(false);

    // When
    boolean result = missionViewerAccessService.canManageMission(missionId);

    // Then
    assertFalse(result, "canManageMission must propagate a deny decision unchanged");
  }
}
