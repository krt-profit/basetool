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

package de.greluc.krt.profit.basetool.frontend.orgunit.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.orgunit.client.OrgUnitBackendClient;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SpecialCommandDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/**
 * Unit tests for {@link SpecialCommandMembersPageController#detail}: the admin / non-admin model
 * split ({@code canToggleLead}, {@code backUrl}), the roster sort, the backend-403 → {@link
 * AccessDeniedException} mapping and the redirect for an empty SK read.
 */
class SpecialCommandMembersPageControllerTest {

  private final UUID skId = UUID.randomUUID();
  private BackendApiClient client;
  private FrontendAuthHelperService authHelper;
  private SpecialCommandMembersPageController controller;

  @BeforeEach
  void setUp() {
    client = mock(BackendApiClient.class);
    authHelper = mock(FrontendAuthHelperService.class);
    controller =
        new SpecialCommandMembersPageController(new OrgUnitBackendClient(client), authHelper);
  }

  /** Stubs a minimal SK read plus a two-member roster delivered out of name order. */
  private void stubSkWithTwoMembers() {
    when(client.get(eq("/api/v1/special-commands/{id}"), eq(SpecialCommandDto.class), eq(skId)))
        .thenReturn(new SpecialCommandDto(skId, "Alpha SK", "ASK", null, null, null, null));
    when(client.get(eq("/api/v1/special-commands/{id}/members"), anyTypeRef(), eq(skId)))
        .thenReturn(List.of(member("zulu"), member("Alpha")));
  }

  @Test
  void detail_admin_setsLeadToggleAndOverviewBackUrl() {
    when(authHelper.isAdmin()).thenReturn(true);
    stubSkWithTwoMembers();
    Model model = new ConcurrentModel();

    String view = controller.detail(skId, null, model);

    assertEquals("organisation/special-command-detail", view);
    assertEquals(Boolean.TRUE, model.getAttribute("canToggleLead"));
    assertEquals("/admin/special-commands", model.getAttribute("backUrl"));
  }

  @Test
  void detail_nonAdmin_hidesLeadToggleAndSortsRosterByName() {
    when(authHelper.isAdmin()).thenReturn(false);
    stubSkWithTwoMembers();
    Model model = new ConcurrentModel();

    String view = controller.detail(skId, "members", model);

    assertEquals("organisation/special-command-detail :: membersResults", view);
    assertEquals(Boolean.FALSE, model.getAttribute("canToggleLead"));
    assertEquals("/organisation/leitung", model.getAttribute("backUrl"));
    List<?> members = assertInstanceOf(List.class, model.getAttribute("members"));
    assertEquals(2, members.size());
    assertEquals(
        "Alpha", assertInstanceOf(OrgUnitMembershipDto.class, members.get(0)).userDisplayName());
    OrgUnitMembershipDto last = assertInstanceOf(OrgUnitMembershipDto.class, members.get(1));
    assertEquals("zulu", last.userDisplayName());
    assertEquals(OrgUnitKind.SPECIAL_COMMAND, last.kind());
    assertEquals(Boolean.FALSE, last.isLogistician());
    assertEquals(0L, last.version());
  }

  @Test
  void detail_backendForbidden_throwsAccessDenied() {
    when(authHelper.isAdmin()).thenReturn(false);
    when(client.get(eq("/api/v1/special-commands/{id}"), eq(SpecialCommandDto.class), eq(skId)))
        .thenThrow(new BackendServiceException("forbidden", null, 403));

    assertThrows(
        AccessDeniedException.class, () -> controller.detail(skId, null, new ConcurrentModel()));
  }

  @Test
  void detail_emptySkRead_redirectsToBackUrl() {
    when(authHelper.isAdmin()).thenReturn(true);
    when(client.get(eq("/api/v1/special-commands/{id}"), eq(SpecialCommandDto.class), eq(skId)))
        .thenReturn(null);

    String view = controller.detail(skId, null, new ConcurrentModel());

    assertEquals("redirect:/admin/special-commands?error=SpecialCommandNotFound", view);
  }

  private static OrgUnitMembershipDto member(String displayName) {
    return new OrgUnitMembershipDto(null, displayName, null, null, null, null, null, null, null);
  }
}
