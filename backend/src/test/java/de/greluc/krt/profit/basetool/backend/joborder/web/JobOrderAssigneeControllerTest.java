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

package de.greluc.krt.profit.basetool.backend.joborder.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderAccessPolicy;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderAssigneeCandidateDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderAssigneeCandidateService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Pure-Mockito unit tests for {@link JobOrderAssigneeController}: self assignment and the own note
 * always work for a reader of the order, someone else's entry needs {@code canManageAssignees}
 * (REQ-ORDERS-014, REQ-ORDERS-047).
 */
@ExtendWith(MockitoExtension.class)
class JobOrderAssigneeControllerTest {

  @Mock private JobOrderService jobOrderService;
  @Mock private JobOrderAccessPolicy jobOrderAccessPolicy;
  @Mock private JobOrderAssigneeCandidateService jobOrderAssigneeCandidateService;
  @Mock private UserService userService;

  @InjectMocks private JobOrderAssigneeController controller;

  private static Jwt jwt(String sub) {
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject(sub)
        .claim("sub", sub)
        .build();
  }

  private static JobOrderDto jobOrderDto(UUID id) {
    return new JobOrderDto(
        id,
        1,
        null,
        null,
        "alice",
        "deliver to ArcCorp",
        1,
        JobOrderStatus.OPEN,
        JobOrderType.MATERIAL,
        true,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Instant.parse("2026-01-01T00:00:00Z"),
        1L,
        null,
        false);
  }

  @Test
  void searchAssigneeCandidates_delegatesToTheCandidateService() {
    UUID jobOrderId = UUID.randomUUID();
    List<JobOrderAssigneeCandidateDto> candidates =
        List.of(new JobOrderAssigneeCandidateDto(UUID.randomUUID(), "Vega", false, "GB", "Bravo"));
    when(jobOrderAssigneeCandidateService.searchCandidates(jobOrderId, "ve"))
        .thenReturn(candidates);

    assertThat(controller.searchAssigneeCandidates(jobOrderId, "ve")).isSameAs(candidates);
  }

  @Test
  void addAssignee_self_doesNotConsultRoleHelper() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    JobOrderDto persisted = jobOrderDto(jobOrderId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(jobOrderService.addAssignee(jobOrderId, callerId)).thenReturn(persisted);

    JobOrderDto result = controller.addAssignee(jobOrderId, callerId, jwt);

    assertThat(result).isSameAs(persisted);
    verify(jobOrderAccessPolicy, never()).canManageAssignees(any());
  }

  @Test
  void addAssignee_otherUser_logistician_isAllowed() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    JobOrderDto persisted = jobOrderDto(jobOrderId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(jobOrderAccessPolicy.canManageAssignees(jobOrderId)).thenReturn(true);
    when(jobOrderService.addAssignee(jobOrderId, targetUserId)).thenReturn(persisted);

    JobOrderDto result = controller.addAssignee(jobOrderId, targetUserId, jwt);

    assertThat(result).isSameAs(persisted);
  }

  @Test
  void addAssignee_otherUser_nonLogistician_throws403() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(jobOrderAccessPolicy.canManageAssignees(jobOrderId)).thenReturn(false);

    assertThatThrownBy(() -> controller.addAssignee(jobOrderId, targetUserId, jwt))
        .isInstanceOf(AccessDeniedException.class);
    verify(jobOrderService, never()).addAssignee(any(), any());
  }

  @Test
  void removeAssignee_self_doesNotConsultRoleHelper() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    JobOrderDto persisted = jobOrderDto(jobOrderId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(jobOrderService.removeAssignee(jobOrderId, callerId)).thenReturn(persisted);

    JobOrderDto result = controller.removeAssignee(jobOrderId, callerId, jwt);

    assertThat(result).isSameAs(persisted);
    verify(jobOrderAccessPolicy, never()).canManageAssignees(any());
  }

  @Test
  void removeAssignee_otherUser_logistician_isAllowed() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    JobOrderDto persisted = jobOrderDto(jobOrderId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(jobOrderAccessPolicy.canManageAssignees(jobOrderId)).thenReturn(true);
    when(jobOrderService.removeAssignee(jobOrderId, targetUserId)).thenReturn(persisted);

    JobOrderDto result = controller.removeAssignee(jobOrderId, targetUserId, jwt);

    assertThat(result).isSameAs(persisted);
  }

  @Test
  void removeAssignee_otherUser_nonLogistician_throws403() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(jobOrderAccessPolicy.canManageAssignees(jobOrderId)).thenReturn(false);

    assertThatThrownBy(() -> controller.removeAssignee(jobOrderId, targetUserId, jwt))
        .isInstanceOf(AccessDeniedException.class);
    verify(jobOrderService, never()).removeAssignee(any(), any());
  }

  @Test
  void setAssigneeNote_self_doesNotConsultRoleHelper() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    JobOrderDto persisted = jobOrderDto(jobOrderId);
    JobOrderAssigneeController.AssigneeNoteRequest body =
        new JobOrderAssigneeController.AssigneeNoteRequest("works Friday", 2L);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(jobOrderService.updateAssigneeNote(jobOrderId, callerId, "works Friday", 2L))
        .thenReturn(persisted);

    JobOrderDto result = controller.setAssigneeNote(jobOrderId, callerId, body, jwt);

    assertThat(result).isSameAs(persisted);
    verify(jobOrderAccessPolicy, never()).canManageAssignees(any());
  }

  @Test
  void setAssigneeNote_otherUser_logistician_isAllowed() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    JobOrderDto persisted = jobOrderDto(jobOrderId);
    JobOrderAssigneeController.AssigneeNoteRequest body =
        new JobOrderAssigneeController.AssigneeNoteRequest("note", null);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(jobOrderAccessPolicy.canManageAssignees(jobOrderId)).thenReturn(true);
    when(jobOrderService.updateAssigneeNote(jobOrderId, targetUserId, "note", null))
        .thenReturn(persisted);

    JobOrderDto result = controller.setAssigneeNote(jobOrderId, targetUserId, body, jwt);

    assertThat(result).isSameAs(persisted);
  }

  @Test
  void setAssigneeNote_otherUser_nonLogistician_throws403() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    JobOrderAssigneeController.AssigneeNoteRequest body =
        new JobOrderAssigneeController.AssigneeNoteRequest("note", null);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(jobOrderAccessPolicy.canManageAssignees(jobOrderId)).thenReturn(false);

    assertThatThrownBy(() -> controller.setAssigneeNote(jobOrderId, targetUserId, body, jwt))
        .isInstanceOf(AccessDeniedException.class);
    verify(jobOrderService, never()).updateAssigneeNote(any(), any(), any(), any());
  }

  @Test
  void deleteAssigneeNote_otherUser_nonLogistician_throws403() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID targetUserId = UUID.randomUUID();
    UUID jobOrderId = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(jobOrderAccessPolicy.canManageAssignees(jobOrderId)).thenReturn(false);

    assertThatThrownBy(() -> controller.deleteAssigneeNote(jobOrderId, targetUserId, 1L, jwt))
        .isInstanceOf(AccessDeniedException.class);
    verify(jobOrderService, never()).deleteAssigneeNote(any(), any(), any());
  }
}
