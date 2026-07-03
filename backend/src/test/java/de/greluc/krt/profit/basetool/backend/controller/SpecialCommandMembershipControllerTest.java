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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipFlagsPatchRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipLeadToggleRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-method unit tests for {@link SpecialCommandMembershipController}. The Spring-MVC binding
 * ({@code @PreAuthorize} SpEL on the security service, JSON marshalling) is covered by integration
 * tests; here we pin the controller's delegation to the service. Since L4 (#923) the entity-&gt;DTO
 * mapping moved into {@link OrgUnitMembershipService} (the {@code *Dto} projections), so the
 * controller no longer wires the mapper and simply forwards the DTO the service returns.
 */
@ExtendWith(MockitoExtension.class)
class SpecialCommandMembershipControllerTest {

  @Mock private OrgUnitMembershipService service;

  @InjectMocks private SpecialCommandMembershipController controller;

  private static OrgUnitMembershipDto sampleDto(UUID userId, UUID scId) {
    return new OrgUnitMembershipDto(
        userId, "Alice", scId, OrgUnitKind.SPECIAL_COMMAND, false, false, false, Instant.now(), 0L);
  }

  @Test
  void listMembers_forwardsServiceDtoProjection() {
    UUID scId = UUID.randomUUID();
    OrgUnitMembershipDto dto = sampleDto(UUID.randomUUID(), scId);
    when(service.listMemberDtos(scId)).thenReturn(List.of(dto));

    List<OrgUnitMembershipDto> result = controller.listMembers(scId);

    assertEquals(1, result.size());
    assertSame(dto, result.get(0));
  }

  @Test
  void addMember_forwardsServiceDto() {
    UUID scId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    OrgUnitMembershipDto dto = sampleDto(userId, scId);
    when(service.addMemberDto(scId, userId)).thenReturn(dto);

    OrgUnitMembershipDto result = controller.addMember(scId, userId);

    assertSame(dto, result);
    verify(service).addMemberDto(scId, userId);
  }

  @Test
  void removeMember_delegatesIdsToService() {
    UUID scId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    controller.removeMember(scId, userId);

    verify(service).removeMember(scId, userId);
    verifyNoMoreInteractions(service);
  }

  @Test
  void patchFlags_forwardsServiceDto() {
    UUID scId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    MembershipFlagsPatchRequest request = new MembershipFlagsPatchRequest(true, null, 4L);
    OrgUnitMembershipDto dto = sampleDto(userId, scId);
    when(service.patchFlagsDto(scId, userId, request)).thenReturn(dto);

    OrgUnitMembershipDto result = controller.patchFlags(scId, userId, request);

    assertSame(dto, result);
    verify(service).patchFlagsDto(eq(scId), eq(userId), any(MembershipFlagsPatchRequest.class));
  }

  @Test
  void toggleLead_forwardsServiceDto() {
    UUID scId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    MembershipLeadToggleRequest request = new MembershipLeadToggleRequest(true, 0L);
    OrgUnitMembershipDto dto = sampleDto(userId, scId);
    when(service.toggleLeadDto(scId, userId, request)).thenReturn(dto);

    OrgUnitMembershipDto result = controller.toggleLead(scId, userId, request);

    assertSame(dto, result);
    verify(service).toggleLeadDto(eq(scId), eq(userId), any(MembershipLeadToggleRequest.class));
  }
}
