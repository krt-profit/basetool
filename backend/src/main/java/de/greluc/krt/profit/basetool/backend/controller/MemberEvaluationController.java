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

import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.model.dto.MemberEvaluationResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.MemberEvaluationUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.service.MemberEvaluationService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.CurrentUserSub;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for {@code MemberEvaluation}.
 *
 * <p>Personal view ({@code /my}) is filtered by JWT sub (data isolation). Admin view ({@code /all}
 * and upsert) requires ADMIN or OFFICER. Officer access is squadron-scoped — an Officer of squadron
 * X may only manage evaluations whose category belongs to a topic owned by squadron X (gate
 * enforced by {@code MemberEvaluationService}).
 */
@RestController
@RequestMapping("/api/v1/promotion/evaluations")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Member Evaluations", description = "Manage member promotion evaluations.")
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class MemberEvaluationController {

  private final MemberEvaluationService service;
  private final UserService userService;
  private final UserMapper userMapper;

  /**
   * Returns every {@link MemberEvaluationResponse} owned by the calling member, filtered by the JWT
   * {@code sub} claim so callers see only their own promotion evaluations.
   *
   * @param ownerSub the caller's JWT {@code sub} claim
   * @return the calling member's evaluations
   */
  @GetMapping("/my")
  @Operation(summary = "List own evaluations (JWT-sub filtered).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Own evaluations."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public List<MemberEvaluationResponse> listMy(@CurrentUserSub String ownerSub) {
    return service.listForUser(ownerSub);
  }

  /**
   * Returns a paginated slice of the caller's own {@link MemberEvaluationResponse} entries with
   * sort fields restricted to {@link MemberEvaluationService#SORTABLE_FIELDS} to prevent
   * information disclosure via unstable user-supplied sorts.
   *
   * @param page zero-based page index, or {@code null} for the default
   * @param size page size, or {@code null} for the default
   * @param sort comma-separated sort spec ({@code field,direction}), or {@code null} for the
   *     default
   * @param ownerSub the caller's JWT {@code sub} claim
   * @return a {@link PageResponse} of the caller's evaluations
   */
  @GetMapping("/my/paged")
  @Operation(summary = "List own evaluations paginated (JWT-sub filtered).")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Paginated own evaluations.")})
  public PageResponse<MemberEvaluationResponse> listMyPaged(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @CurrentUserSub String ownerSub) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            MemberEvaluationService.SORTABLE_FIELDS,
            MemberEvaluationService.DEFAULT_SORT_FIELD);
    return PageResponse.of(service.listForUserPaged(ownerSub, pageable));
  }

  /**
   * Returns a paginated slice of every {@link MemberEvaluationResponse} in the system for promotion
   * reviewers. Authorization is enforced in the service layer and limited to ADMIN callers.
   *
   * @param page zero-based page index, or {@code null} for the default
   * @param size page size, or {@code null} for the default
   * @param sort comma-separated sort spec ({@code field,direction}), or {@code null} for the
   *     default
   * @return a {@link PageResponse} covering all evaluations
   */
  @GetMapping("/all")
  @Operation(summary = "List all member evaluations (ADMIN/OFFICER, squadron-scoped for OFFICER).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "All evaluations."),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
  })
  public PageResponse<MemberEvaluationResponse> listAll(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            MemberEvaluationService.SORTABLE_FIELDS,
            MemberEvaluationService.DEFAULT_SORT_FIELD);
    return PageResponse.of(service.listAll(pageable));
  }

  /**
   * Returns the paged list of squadron members that the caller may evaluate — the simple members of
   * the squadron, with both admins and officers excluded (issue #817). Squadron-scoped for Officer
   * (own squadron only) and for Admin with the sidebar switcher focused on a squadron; Admin in
   * "all squadrons" mode sees every squadron's ordinary members. Admins and officers themselves
   * never appear — admins are squadron-less by design, and officers run the Bewertungsverwaltung
   * rather than being its subject.
   *
   * @param page zero-based page index, or {@code null} for the default
   * @param size page size, or {@code null} for the default
   * @param sort comma-separated sort spec ({@code field,direction}), or {@code null} for the
   *     default
   * @return a {@link PageResponse} of evaluatable members
   */
  @GetMapping("/members")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  @Operation(
      summary =
          "List squadron members eligible for evaluation (ADMIN/OFFICER, squadron-scoped, admins"
              + " and officers excluded).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Evaluatable members."),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
  })
  public PageResponse<UserDto> listEvaluatableMembers(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            java.util.Set.of("id", "username", "displayName", "userRank"),
            "username");
    var result = userService.findEvaluatableMembers(pageable);
    return PageResponse.of(result.map(userMapper::toDto));
  }

  /**
   * Creates or updates the evaluation record for the given user and promotion category. The request
   * body carries the new score and notes; the optimistic-locking {@code version} echoed back by the
   * client guards against concurrent edits and surfaces as HTTP 409 on conflict.
   *
   * @param userId Keycloak {@code sub} of the member being evaluated
   * @param categoryId identifier of the {@link
   *     de.greluc.krt.profit.basetool.backend.model.PromotionCategory}
   * @param request validated payload carrying the new score, notes, and {@code version}
   * @return the persisted evaluation in its response form
   */
  @PutMapping("/user/{userId}/category/{categoryId}")
  @Operation(
      summary =
          "Upsert evaluation for a user/category. ADMIN or OFFICER of the category's owning"
              + " squadron.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Evaluation upserted."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions."),
    @ApiResponse(responseCode = "404", description = "Category not found."),
    @ApiResponse(responseCode = "409", description = "Optimistic lock conflict.")
  })
  public MemberEvaluationResponse upsert(
      @PathVariable String userId,
      @PathVariable UUID categoryId,
      @Valid @RequestBody MemberEvaluationUpdateRequest request) {
    return service.upsert(userId, categoryId, request);
  }

  /**
   * Permanently removes the evaluation identified by {@code id}, dropping the row used to track a
   * member's standing in one promotion category. ADMIN or OFFICER of the category's owning
   * squadron.
   *
   * @param id identifier of the {@link
   *     de.greluc.krt.profit.basetool.backend.model.MemberEvaluation} to delete
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Delete a member evaluation. ADMIN or OFFICER of the category's owning squadron.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Deleted."),
    @ApiResponse(responseCode = "404", description = "Not found.")
  })
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }
}
