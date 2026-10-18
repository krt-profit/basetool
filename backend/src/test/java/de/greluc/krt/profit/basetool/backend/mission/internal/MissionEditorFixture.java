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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.Authentication;

/**
 * Lets a section-service unit test keep its {@code @InjectMocks} wiring while the {@link
 * MissionEditor} mock runs the real recipe behind a gate that admits every caller, so the test
 * asserts the mutation and the recipe test {@code MissionEditorTest} owns the gate.
 */
public final class MissionEditorFixture {

  private MissionEditorFixture() {}

  /**
   * Makes {@code editorMock} forward both entry points to a real editor over the test's own
   * repository and audit mocks.
   *
   * @param editorMock the {@code @Mock MissionEditor} injected into the service under test
   * @param missionRepository the test's mission repository mock
   * @param auditRecorder the test's audit mock
   */
  public static void delegate(
      @NotNull MissionEditor editorMock,
      @NotNull MissionRepository missionRepository,
      @NotNull AuditRecorder auditRecorder) {
    MissionAccessPolicy admitAll = mock(MissionAccessPolicy.class);
    lenient().when(admitAll.canEdit(any(), any(), any())).thenReturn(true);
    Authentication caller = mock(Authentication.class);
    lenient().when(caller.isAuthenticated()).thenReturn(true);
    AuthHelperService authHelper = mock(AuthHelperService.class);
    lenient().when(authHelper.rawAuthentication()).thenReturn(caller);
    MissionEditor real = new MissionEditor(missionRepository, admitAll, authHelper, auditRecorder);
    lenient()
        .when(editorMock.edit(any(), any(), any()))
        .thenAnswer(i -> real.edit(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
    lenient()
        .when(editorMock.editSection(any(), any(), any(), any(), any()))
        .thenAnswer(
            i ->
                real.editSection(
                    i.getArgument(0),
                    i.getArgument(1),
                    i.getArgument(2),
                    i.getArgument(3),
                    i.getArgument(4)));
  }
}
