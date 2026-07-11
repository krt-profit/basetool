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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipQueryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Thin delegation tests for {@link OrgUnitController}. Each endpoint is a single-line passthrough
 * to {@link OrgUnitMembershipQueryService} — the resolver / sort behaviour is pinned by {@link
 * de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipQueryServiceTest}, so this class
 * only verifies the wiring (handlers exist, response shape preserved, no surprise filtering). The
 * declarative {@code @PreAuthorize} gates (public {@code /active} vs authenticated {@code
 * /active-all-kinds}) are enforced by Spring Security, not asserted here.
 */
@ExtendWith(MockitoExtension.class)
class OrgUnitControllerTest {

  @Mock private OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  @InjectMocks private OrgUnitController controller;

  @Test
  void listActiveOrgUnits_delegatesToService() {
    OrgUnitMembershipOptionDto option =
        new OrgUnitMembershipOptionDto(
            UUID.randomUUID(), "IRIDIUM", "IRI", OrgUnitKind.SQUADRON, true);
    when(orgUnitMembershipQueryService.listAllActiveOptions()).thenReturn(List.of(option));

    List<OrgUnitMembershipOptionDto> result = controller.listActiveOrgUnits();

    assertEquals(1, result.size());
    assertSame(option, result.getFirst());
    verify(orgUnitMembershipQueryService).listAllActiveOptions();
  }

  @Test
  void listActiveOrgUnits_emptyService_returnsEmptyList() {
    when(orgUnitMembershipQueryService.listAllActiveOptions()).thenReturn(List.of());

    assertTrue(controller.listActiveOrgUnits().isEmpty());
  }

  @Test
  void listActiveOrgUnitsAllKinds_delegatesToService() {
    // Epic #692 Phase 6 (REQ-ORG-019): the all-kinds picker (bank account-create form) surfaces the
    // Bereich/OL tiers the public /active list omits, so the handler must wire to the all-kinds
    // service method, not listAllActiveOptions.
    OrgUnitMembershipOptionDto bereich =
        new OrgUnitMembershipOptionDto(
            UUID.randomUUID(), "Profit", "PRF", OrgUnitKind.BEREICH, false);
    when(orgUnitMembershipQueryService.listAllActiveOrgUnitOptionsAllKinds())
        .thenReturn(List.of(bereich));

    List<OrgUnitMembershipOptionDto> result = controller.listActiveOrgUnitsAllKinds();

    assertEquals(1, result.size());
    assertSame(bereich, result.getFirst());
    verify(orgUnitMembershipQueryService).listAllActiveOrgUnitOptionsAllKinds();
  }
}
