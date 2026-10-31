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

import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAssignee;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderAccessPolicy;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderAssigneeCandidateDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderAssigneeCandidateService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface over a job order's assignees ("Bearbeiter"), their notes and the assignee picker
 * (REQ-ORDERS-013, REQ-ORDERS-014, REQ-ORDERS-047).
 *
 * <p>Every endpoint requires a caller who may read the order; changing someone else's entry also
 * requires {@link JobOrderAccessPolicy#canManageAssignees}, decided at the HTTP boundary so the
 * service stays free of {@code SecurityContextHolder} reads.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Operations related to job orders")
public class JobOrderAssigneeController {
  private final JobOrderService jobOrderService;
  private final JobOrderAccessPolicy jobOrderAccessPolicy;
  private final JobOrderAssigneeCandidateService jobOrderAssigneeCandidateService;
  private final UserService userService;

  /**
   * Adds a user as assignee. Self-assignment works for everyone who may read the order; assigning
   * someone else requires a Logistician of the order.
   *
   * @param id job-order id
   * @param userId target user id
   * @param jwt caller's JWT
   * @return the persisted DTO
   */
  @PostMapping("/{id}/assignees/{userId}")
  @Operation(summary = "Add an assignee", description = "Adds a user to the job order.")
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#id)")
  public JobOrderDto addAssignee(
      @PathVariable UUID id, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
    verifyAssigneeAccess(jwt, id, userId);
    return withManageFlag(id, jobOrderService.addAssignee(id, userId));
  }

  /**
   * Searches the active accounts a Logistician of the order may assign: the processing unit's
   * direct members first, every other hit marked with its primary unit (REQ-ORDERS-047).
   *
   * @param id job-order id
   * @param query the handle fragment, or blank for the first accounts
   * @return the candidates, without further personal data
   */
  @GetMapping("/{id}/assignee-candidates")
  @Operation(
      summary = "Search assignee candidates",
      description =
          "Returns active accounts matching the handle fragment that may be assigned to the order:"
              + " direct members of the processing unit first, every other account marked with its"
              + " primary unit.")
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canManageAssignees(#id)")
  @Transactional(readOnly = true)
  public List<JobOrderAssigneeCandidateDto> searchAssigneeCandidates(
      @PathVariable UUID id, @RequestParam(required = false) String query) {
    return jobOrderAssigneeCandidateService.searchCandidates(id, query);
  }

  /**
   * Removes a user as assignee. Same self-or-logistician rule as {@link #addAssignee}.
   *
   * @param id job-order id
   * @param userId target user id
   * @param jwt caller's JWT
   * @return the persisted DTO
   */
  @DeleteMapping("/{id}/assignees/{userId}")
  @Operation(summary = "Remove an assignee", description = "Removes a user from the job order.")
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#id)")
  public JobOrderDto removeAssignee(
      @PathVariable UUID id, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
    verifyAssigneeAccess(jwt, id, userId);
    return withManageFlag(id, jobOrderService.removeAssignee(id, userId));
  }

  /**
   * Sets or replaces the note on an assignee entry. Same self-or-logistician rule as {@link
   * #addAssignee}; optimistic-locked on the assignee edge's own version.
   *
   * @param id job-order id
   * @param userId the assignee whose note is changed
   * @param body the new note + the edge version last seen by the client
   * @param jwt caller's JWT
   * @return the persisted DTO with the refreshed assignee list
   */
  @PutMapping("/{id}/assignees/{userId}/note")
  @Operation(
      summary = "Set an assignee note",
      description = "Creates or replaces the note on a user's assignee entry.")
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#id)")
  public JobOrderDto setAssigneeNote(
      @PathVariable UUID id,
      @PathVariable UUID userId,
      @RequestBody @Valid AssigneeNoteRequest body,
      @AuthenticationPrincipal Jwt jwt) {
    verifyAssigneeAccess(jwt, id, userId);
    return withManageFlag(
        id, jobOrderService.updateAssigneeNote(id, userId, body.note(), body.version()));
  }

  /**
   * Clears the note on an assignee entry. Same self-or-logistician rule and optimistic-locking
   * semantics as {@link #setAssigneeNote}.
   *
   * @param id job-order id
   * @param userId the assignee whose note is cleared
   * @param jwt caller's JWT
   * @param version the assignee edge version last seen by the client, or {@code null} to skip the
   *     check
   * @return the persisted DTO with the refreshed assignee list
   */
  @DeleteMapping("/{id}/assignees/{userId}/note")
  @Operation(
      summary = "Delete an assignee note",
      description = "Clears the note on a user's assignee entry.")
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#id)")
  public JobOrderDto deleteAssigneeNote(
      @PathVariable UUID id,
      @PathVariable UUID userId,
      @RequestParam(required = false) Long version,
      @AuthenticationPrincipal Jwt jwt) {
    verifyAssigneeAccess(jwt, id, userId);
    return withManageFlag(id, jobOrderService.deleteAssigneeNote(id, userId, version));
  }

  /**
   * Enforces the self-or-logistician rule for assignee mutations. Throws {@link
   * AccessDeniedException} when the caller modifies someone else's assignment without {@link
   * JobOrderAccessPolicy#canManageAssignees}, which the assignee grant alone never passes.
   *
   * @param jwt caller's JWT
   * @param jobOrderId the order whose assignee is changed
   * @param targetUserId user id being added/removed
   */
  private void verifyAssigneeAccess(Jwt jwt, UUID jobOrderId, UUID targetUserId) {
    UUID currentUserId = userService.getUserIdFromJwt(jwt);
    if (!currentUserId.equals(targetUserId)
        && !jobOrderAccessPolicy.canManageAssignees(jobOrderId)) {
      throw new AccessDeniedException("Not allowed to modify other users' assignments");
    }
  }

  /**
   * Stamps the caller's assignee-management flag on a mutated order, so the re-rendered assignee
   * section offers exactly the controls the caller may use.
   *
   * @param jobOrderId the order
   * @param dto the mutated order
   * @return the order with {@code canManageAssignees} resolved
   */
  private JobOrderDto withManageFlag(UUID jobOrderId, JobOrderDto dto) {
    return dto.withCanManageAssignees(jobOrderAccessPolicy.canManageAssignees(jobOrderId));
  }

  /**
   * Request body for the assignee-note PUT endpoint.
   *
   * @param note the new note text (at most {@value JobOrderAssignee#NOTE_MAX_LENGTH} characters;
   *     blank/{@code null} clears the note)
   * @param version the assignee edge version the client last saw, or {@code null} to skip the
   *     optimistic-lock check
   */
  public record AssigneeNoteRequest(
      @Size(max = JobOrderAssignee.NOTE_MAX_LENGTH) String note, Long version) {}
}
