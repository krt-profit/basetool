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

package de.greluc.krt.profit.basetool.backend.app.web;

import de.greluc.krt.profit.basetool.backend.blueprint.api.MemberBlueprintSharings;
import de.greluc.krt.profit.basetool.backend.identity.api.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceSearchService;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.identity.api.UserSyncResultDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserSyncService;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.mission.api.MemberPayoutSettings;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserMapper;
import de.greluc.krt.profit.basetool.backend.platform.api.CurrentUserId;
import de.greluc.krt.profit.basetool.backend.platform.api.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.platform.api.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.privacy.api.AccountConsolidationService;
import de.greluc.krt.profit.basetool.backend.privacy.api.ConsolidateAccountRequest;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserDeletionService;
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
 * REST surface for the local {@code app_user} mirror.
 *
 * <p>{@code /me} endpoints derive the user from the JWT, never from the URL; {@code /sync}, {@code
 * /attributes}, the membership write/detail endpoints and {@code DELETE} are admin-scoped. The
 * class-level {@link Transactional} keeps the session open for the {@link UserMapper} projection.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Transactional
public class UserController {

  private final UserService userService;
  private final UserDeletionService userDeletionService;
  private final UserMapper userMapper;
  private final UserReferenceSearchService userReferenceSearchService;
  private final UserSyncService userSyncService;
  private final MemberPayoutSettings memberPayoutSettings;
  private final MemberBlueprintSharings memberBlueprintSharings;

  /** The consolidate action's orchestrator (REQ-SEC-055): duplicate in, survivor out. */
  private final AccountConsolidationService accountConsolidationService;

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
          + Roles.OFFICER
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
      "hasAnyRole('" + Roles.ADMIN + "', '" + Roles.OFFICER + "', '" + Roles.KRT_MEMBER + "')")
  @Transactional(readOnly = true)
  public PageResponse<UserReferenceDto> searchUserReferences(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return userReferenceSearchService.search(query, page, size, sort);
  }

  /**
   * Returns the calling user's personal default payout preference and the current optimistic-lock
   * version, backing the profile page's payout-preference selector. Derived from the JWT subject —
   * a caller can only ever read their own. A {@code null} preference means the user has made no
   * explicit choice yet (mission sign-up then falls back to {@code PAYOUT}, and the selector
   * pre-selects {@code PAYOUT}).
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @return the current default payout preference (possibly {@code null}) plus the user-row
   *     version.
   */
  @NotNull
  @GetMapping("/missions/me/payout-preference")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public MyPayoutPreferenceResponse getMyPayoutPreference(@AuthenticationPrincipal Jwt jwt) {
    MemberPayoutSettings.Snapshot setting =
        memberPayoutSettings.snapshotOf(userService.getUserIdFromJwt(jwt));
    return new MyPayoutPreferenceResponse(setting.preference(), setting.version());
  }

  /**
   * Sets the calling user's personal default payout preference. The JWT identifies the row — no
   * impersonation possible. Carries the optimistic-lock version so a concurrent edit surfaces as a
   * 409 instead of a silent overwrite. The new value only pre-fills future mission sign-ups; it
   * does not rewrite existing participations.
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @param request the new preference plus the expected version.
   * @return the persisted preference and the new version.
   */
  @NotNull
  @PutMapping("/missions/me/payout-preference")
  @PreAuthorize("isAuthenticated()")
  public MyPayoutPreferenceResponse updateMyPayoutPreference(
      @AuthenticationPrincipal Jwt jwt,
      @NotNull @RequestBody @Valid MyPayoutPreferenceRequest request) {
    MemberPayoutSettings.Snapshot setting =
        memberPayoutSettings.update(
            userService.getUserIdFromJwt(jwt), request.preference(), request.version());
    return new MyPayoutPreferenceResponse(setting.preference(), setting.version());
  }

  /**
   * Returns whether the calling user has opted into global blueprint sharing, plus the current
   * optimistic-lock version, backing the profile page's blueprint-sharing toggle. Derived from the
   * JWT subject — a caller can only ever read their own.
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @return the current opt-in flag plus the user-row version.
   */
  @NotNull
  @GetMapping("/blueprints/me/sharing")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public MyBlueprintSharingResponse getMyBlueprintSharing(@AuthenticationPrincipal Jwt jwt) {
    MemberBlueprintSharings.Snapshot sharing =
        memberBlueprintSharings.snapshotOf(userService.getUserIdFromJwt(jwt));
    return new MyBlueprintSharingResponse(sharing.shareGlobally(), sharing.version());
  }

  /**
   * Sets whether the calling user opts into global blueprint sharing (REQ-INV-018), guarded by the
   * optimistic-lock version.
   *
   * <p>When enabled, the user's blueprints count toward every org unit's availability and coverage
   * views (read-only, name-only).
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @param request the new opt-in value plus the expected version.
   * @return the persisted flag and the new version.
   */
  @NotNull
  @PutMapping("/blueprints/me/sharing")
  @PreAuthorize("isAuthenticated()")
  public MyBlueprintSharingResponse updateMyBlueprintSharing(
      @AuthenticationPrincipal Jwt jwt,
      @NotNull @RequestBody @Valid MyBlueprintSharingRequest request) {
    MemberBlueprintSharings.Snapshot sharing =
        memberBlueprintSharings.update(
            userService.getUserIdFromJwt(jwt),
            request.shareBlueprintsGlobally(),
            request.version());
    return new MyBlueprintSharingResponse(sharing.shareGlobally(), sharing.version());
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
   * ADMIN-only: deletes a user account along with all owned data (ships, inventory, refinery
   * orders, mission memberships).
   *
   * @param id user id
   */
  @DeleteMapping("/users/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public void deleteUser(@PathVariable @NotNull UUID id) {
    userDeletionService.deleteUser(id);
  }

  /**
   * Folds an approved duplicate account into the account the member keeps (REQ-SEC-055).
   *
   * <p>Owned data moves to the survivor, the Discord identity is re-linked in Keycloak, and the
   * duplicate's row and Keycloak user are removed. The path names the duplicate, the body the
   * survivor. ADMIN-only.
   *
   * @param id the duplicate account to dissolve
   * @param adminUserId the acting admin, recorded as the audit actor
   * @param body the surviving account's id and the duplicate's optimistic-lock version
   * @return the surviving account, carrying the moved Discord link
   */
  @PostMapping("/users/{id}/consolidate")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public UserDto consolidateAccount(
      @PathVariable @NotNull UUID id,
      @CurrentUserId UUID adminUserId,
      @NotNull @RequestBody @Valid ConsolidateAccountRequest body) {
    return userMapper.toDto(
        accountConsolidationService.consolidate(
            id, body.targetUserId(), body.version(), adminUserId));
  }

  /**
   * Response for {@link #getMyPayoutPreference} / {@link #updateMyPayoutPreference}: the user's
   * current default payout preference (or {@code null} when never chosen) plus the user-row
   * optimistic-lock version the selector echoes back on save.
   *
   * @param defaultPayoutPreference the stored default, or {@code null} for "no explicit choice".
   * @param version the user row's current {@code @Version}.
   */
  public record MyPayoutPreferenceResponse(
      @Nullable PayoutPreference defaultPayoutPreference, Long version) {}

  /**
   * Body for {@link #updateMyPayoutPreference}: the new default payout preference and the expected
   * optimistic-lock version. The preference is {@code @NotNull} — the profile selector always posts
   * a concrete {@code PAYOUT} / {@code DONATE} (a user wanting the implicit default simply picks
   * {@code PAYOUT}); there is no API path that clears it back to {@code null}.
   *
   * @param preference the new default payout preference; never {@code null}.
   * @param version the {@code @Version} of the user row the caller last read; never {@code null}.
   */
  public record MyPayoutPreferenceRequest(
      @jakarta.validation.constraints.NotNull PayoutPreference preference,
      @jakarta.validation.constraints.NotNull Long version) {}

  /**
   * Response for {@link #getMyBlueprintSharing} / {@link #updateMyBlueprintSharing}: whether the
   * user opted into global blueprint sharing plus the user-row optimistic-lock version the toggle
   * echoes back on save.
   *
   * @param shareBlueprintsGlobally the stored opt-in flag.
   * @param version the user row's current {@code @Version}.
   */
  public record MyBlueprintSharingResponse(boolean shareBlueprintsGlobally, Long version) {}

  /**
   * Body for {@link #updateMyBlueprintSharing}: the new opt-in flag and the expected
   * optimistic-lock version.
   *
   * @param shareBlueprintsGlobally the new opt-in value; never {@code null}.
   * @param version the {@code @Version} of the user row the caller last read; never {@code null}.
   */
  public record MyBlueprintSharingRequest(
      @jakarta.validation.constraints.NotNull Boolean shareBlueprintsGlobally,
      @jakarta.validation.constraints.NotNull Long version) {}

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
