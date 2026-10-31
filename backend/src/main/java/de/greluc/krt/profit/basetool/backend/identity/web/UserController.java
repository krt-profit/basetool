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

package de.greluc.krt.profit.basetool.backend.identity.web;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceSearchService;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.identity.api.UserSyncResultDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserSyncService;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.platform.api.TaskMetrics;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The user endpoints that need nothing beyond the identity module: the on-demand Keycloak sync, the
 * reference lookup and search, and the RSI handle (own, and the admin read).
 *
 * <p>{@code /me} endpoints derive the user from the JWT, never from the URL; {@code /sync} and the
 * RSI-handle read by id are admin-scoped.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Transactional
public class UserController {

  private final UserService userService;
  private final UserReferenceSearchService userReferenceSearchService;
  private final UserSyncService userSyncService;

  private final TaskMetrics taskMetrics;

  /**
   * Runs the Keycloak user sync on demand for the member-management "Sync now" button, publishing
   * the same {@code user_sync} meters as {@link
   * de.greluc.krt.profit.basetool.backend.identity.internal.UserSyncTask} via {@link
   * TaskMetrics#recordCountingRethrow}.
   *
   * <p>Runs {@code NOT_SUPPORTED} so each user reconciliation opens its own transaction.
   *
   * @return the number of users reconciled this run
   */
  @NotNull
  @PostMapping("/users/sync")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public UserSyncResultDto syncUsersNow() {
    int syncedCount =
        taskMetrics.recordCountingRethrow(
            ScheduledJob.USER_SYNC, userSyncService::syncFromKeycloak);
    return new UserSyncResultDto(syncedCount);
  }

  /**
   * Lightweight typeahead projection (id, username, displayName) of all users, also available to
   * {@code BANK_MANAGEMENT} and {@code BANK_EMPLOYEE} without an org role (REQ-BANK-009,
   * REQ-BANK-044).
   *
   * @return all users as reference DTOs
   */
  @GetMapping("/users/lookup")
  @PreAuthorize(
      "hasAnyRole('"
          + Roles.ADMIN
          + "', '"
          + Roles.KRT_MEMBER
          + "', '"
          + Roles.BANK_EMPLOYEE
          + "')")
  @Transactional(readOnly = true)
  public List<UserReferenceDto> lookupUsers() {
    return userService.findAllReference();
  }

  /**
   * Slim paged picker search with the same query, scope and role gate as {@code GET /users/search},
   * returning SQL-projected {@link
   * de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceDto} rows for the {@code
   * remote-users} comboboxes.
   *
   * <p>No peer redaction applies; the projection already is the peer field set.
   *
   * @param query free-text username/displayName filter, or {@code null}/blank to match all
   * @param page requested page index, or {@code null} for the first page
   * @param size requested page size, or {@code null} for the default
   * @param sort requested sort expression ({@code username}, {@code rank} or {@code id}), or {@code
   *     null} for the username default
   * @return one page of matching user references
   */
  @GetMapping("/users/search/references")
  @PreAuthorize(
      "hasAnyRole('" + Roles.ADMIN + "', '" + Roles.KRT_MEMBER + "')")
  @Transactional(readOnly = true)
  public PageResponse<UserReferenceDto> searchUserReferences(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return userReferenceSearchService.search(query, page, size, sort);
  }

  /**
   * Returns the calling member's own RSI handle and the user-row version, backing the profile
   * page's RSI-handle card (REQ-SEC-072).
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @return the stored handle, {@code null} when none is stored, plus the user-row version.
   */
  @NotNull
  @GetMapping("/users/me/rsi-handle")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public MyRsiHandleResponse getMyRsiHandle(@AuthenticationPrincipal Jwt jwt) {
    User me = userService.findById(userService.getUserIdFromJwt(jwt));
    return new MyRsiHandleResponse(me.getRsiHandle(), me.getVersion());
  }

  /**
   * Sets or clears the calling member's own RSI handle (REQ-SEC-072), guarded by the
   * optimistic-lock version; a blank handle clears it.
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @param request the new handle plus the expected version.
   * @return the persisted handle and the new version.
   */
  @NotNull
  @PutMapping("/users/me/rsi-handle")
  @PreAuthorize("isAuthenticated()")
  public MyRsiHandleResponse updateMyRsiHandle(
      @AuthenticationPrincipal Jwt jwt, @NotNull @RequestBody @Valid MyRsiHandleRequest request) {
    User me =
        userService.updateUserRsiHandle(
            userService.getUserIdFromJwt(jwt), request.rsiHandle(), request.version());
    return new MyRsiHandleResponse(me.getRsiHandle(), me.getVersion());
  }

  /**
   * Returns a member's RSI handle for the admin member page, read-only (REQ-SEC-072).
   *
   * @param id the member
   * @return the stored handle, {@code null} when none is stored
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the member is
   *     unknown
   */
  @NotNull
  @GetMapping("/users/{id}/rsi-handle")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Transactional(readOnly = true)
  public UserRsiHandleResponse getUserRsiHandle(@PathVariable @NotNull UUID id) {
    return new UserRsiHandleResponse(userService.findById(id).getRsiHandle());
  }

  /**
   * Response for {@link #getMyRsiHandle} / {@link #updateMyRsiHandle}: the member's own RSI handle
   * and the user-row optimistic-lock version the card echoes back on save.
   *
   * @param rsiHandle the stored handle, or {@code null} when none is stored.
   * @param version the user row's current {@code @Version}.
   */
  public record MyRsiHandleResponse(@Nullable String rsiHandle, Long version) {}

  /**
   * Body for {@link #updateMyRsiHandle}: the new handle and the expected optimistic-lock version.
   *
   * @param rsiHandle the new handle; {@code null} or blank clears it, otherwise 3 to 60 letters,
   *     digits, underscores or hyphens.
   * @param version the {@code @Version} of the user row the caller last read; never {@code null}.
   */
  public record MyRsiHandleRequest(
      @Nullable @jakarta.validation.constraints.Size(max = 60) String rsiHandle,
      @jakarta.validation.constraints.NotNull Long version) {}

  /**
   * Response for {@link #getUserRsiHandle}: a member's RSI handle as the admin member page shows
   * it.
   *
   * @param rsiHandle the stored handle, or {@code null} when none is stored.
   */
  public record UserRsiHandleResponse(@Nullable String rsiHandle) {}
}
