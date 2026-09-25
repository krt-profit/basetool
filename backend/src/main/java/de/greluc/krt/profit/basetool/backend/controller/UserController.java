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
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.ConsolidateAccountRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserSyncResultDto;
import de.greluc.krt.profit.basetool.backend.service.AccountConsolidationService;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.service.UserDeletionService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.service.UserSyncService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.support.UserDtoRedaction;
import de.greluc.krt.profit.basetool.backend.web.CurrentUserId;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Transactional
public class UserController {

  private static final Set<String> ALLOWED_SORT = Set.of("username", "email", "rank", "id");

  /**
   * Sort whitelist of the reference searches: the projected columns only ({@code email} is not part
   * of the reference projection, so it is not offered).
   */
  private static final Set<String> REFERENCE_SORT = Set.of("username", "rank", "id");

  private final UserService userService;
  private final UserDeletionService userDeletionService;
  private final UserMapper userMapper;
  private final AuthHelperService authHelperService;
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  private final UserSyncService userSyncService;

  /** The consolidate action's orchestrator (REQ-SEC-055): duplicate in, survivor out. */
  private final AccountConsolidationService accountConsolidationService;

  private final TaskMetrics taskMetrics;

  /**
   * Runs the Keycloak user sync on demand for the member-management "Sync now" button, publishing
   * the same {@code user_sync} meters as {@link
   * de.greluc.krt.profit.basetool.backend.task.UserSyncTask} via {@link
   * TaskMetrics#recordCountingRethrow}.
   *
   * <p>Runs {@code NOT_SUPPORTED} so each user reconciliation opens its own transaction.
   *
   * @return the number of users reconciled this run
   */
  @NotNull
  @PostMapping("/sync")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public UserSyncResultDto syncUsersNow() {
    int syncedCount =
        taskMetrics.recordCountingRethrow(
            ScheduledJob.USER_SYNC, userSyncService::syncFromKeycloak);
    return new UserSyncResultDto(syncedCount);
  }

  /**
   * Paged user list. Open to every authenticated member because the participant pickers in the
   * mission editor consume it.
   *
   * @return paged user DTOs
   */
  @GetMapping
  @PreAuthorize(
      "hasAnyRole('" + Roles.ADMIN + "', '" + Roles.OFFICER + "', '" + Roles.KRT_MEMBER + "')")
  @Transactional(readOnly = true)
  public PageResponse<UserDto> getAllUsers(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, ALLOWED_SORT, "username");
    Page<User> p = userService.findAll(pageable);
    userMapper.primeStaffelMemberships(p.getContent());
    return PageResponse.of(p.map(userMapper::toDto).map(this::redactForPeerIfNeeded));
  }

  /**
   * Lightweight typeahead projection (id, username, displayName) of all users, also available to
   * {@code BANK_MANAGEMENT} and {@code BANK_EMPLOYEE} without an org role (REQ-BANK-009,
   * REQ-BANK-044).
   *
   * @return all users as reference DTOs
   */
  @GetMapping("/lookup")
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
   * Paged username/displayName substring search.
   *
   * @return paged user DTOs
   */
  @GetMapping("/search")
  @PreAuthorize(
      "hasAnyRole('" + Roles.ADMIN + "', '" + Roles.OFFICER + "', '" + Roles.KRT_MEMBER + "')")
  @Transactional(readOnly = true)
  public PageResponse<UserDto> searchUsers(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return runSearch(query, page, size, sort);
  }

  /**
   * Bank-audience twin of {@link #searchUsers} for the bank grantee/holder pickers: identical
   * query, scope and projection, with the role gate widened to {@code BANK_EMPLOYEE}
   * (REQ-BANK-008/009/044).
   *
   * @return paged user DTOs, peer-redacted for non-elevated callers
   */
  @GetMapping("/search-bank")
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
  public PageResponse<UserDto> searchUsersForBank(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return runSearch(query, page, size, sort);
  }

  /**
   * Slim paged picker search with the same query, scope and role gate as {@link #searchUsers},
   * returning SQL-projected {@link
   * de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto} rows for the {@code
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
  @GetMapping("/search/references")
  @PreAuthorize(
      "hasAnyRole('" + Roles.ADMIN + "', '" + Roles.OFFICER + "', '" + Roles.KRT_MEMBER + "')")
  @Transactional(readOnly = true)
  public PageResponse<UserReferenceDto> searchUserReferences(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return runReferenceSearch(query, page, size, sort);
  }

  /**
   * Bank-audience twin of {@link #searchUserReferences}, with the widened role gate of {@link
   * #searchUsersForBank}, for the {@code remote-bank-users} pickers (REQ-BANK-008/009/044).
   *
   * @param query free-text username/displayName filter, or {@code null}/blank to match all
   * @param page requested page index, or {@code null} for the first page
   * @param size requested page size, or {@code null} for the default
   * @param sort requested sort expression ({@code username}, {@code rank} or {@code id}), or {@code
   *     null} for the username default
   * @return one page of matching user references
   */
  @GetMapping("/search-bank/references")
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
  public PageResponse<UserReferenceDto> searchUserReferencesForBank(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    return runReferenceSearch(query, page, size, sort);
  }

  /**
   * Shared body of the two reference-search endpoints: resolves the page request against {@link
   * #REFERENCE_SORT} and runs the squadron-scoped projection. A {@code null} query (the browse-mode
   * empty {@code ?query=}) is normalised to the match-all empty string, as in {@link #runSearch}.
   *
   * @param query free-text filter, or {@code null}/blank to match all
   * @param page requested page index, or {@code null}
   * @param size requested page size, or {@code null}
   * @param sort requested sort expression, or {@code null}
   * @return one page of matching user references
   */
  private PageResponse<UserReferenceDto> runReferenceSearch(
      String query, Integer page, Integer size, String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, REFERENCE_SORT, "username");
    return PageResponse.of(
        userService.searchReferencesByUsername(query == null ? "" : query, pageable));
  }

  /**
   * Shared body of {@link #searchUsers} and {@link #searchUsersForBank}: runs the squadron-scoped
   * username/displayName search with whitelisted sort and applies peer redaction.
   *
   * <p>A {@code null} query is treated as match-all.
   *
   * @param query free-text username/displayName filter, or {@code null}/blank to match all.
   * @param page requested page index, or {@code null} for the first page.
   * @param size requested page size, or {@code null} for the default.
   * @param sort requested sort expression, or {@code null} for the username default.
   * @return the paged, peer-redacted search result.
   */
  private PageResponse<UserDto> runSearch(String query, Integer page, Integer size, String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, ALLOWED_SORT, "username");
    Page<User> p = userService.searchByUsername(query == null ? "" : query, pageable);
    userMapper.primeStaffelMemberships(p.getContent());
    return PageResponse.of(p.map(userMapper::toDto).map(this::redactForPeerIfNeeded));
  }

  /**
   * Returns a user by id; a non-admin caller asking for a user outside their squadrons always gets
   * the peer-redacted shape, regardless of role.
   *
   * @param id user id
   * @return the user DTO, peer-redacted for cross-squadron non-admin callers
   */
  @GetMapping("/{id}")
  @PreAuthorize(
      "hasAnyRole('" + Roles.ADMIN + "', '" + Roles.OFFICER + "', '" + Roles.KRT_MEMBER + "')")
  @Transactional(readOnly = true)
  public UserDto getUserById(@PathVariable @NotNull UUID id) {
    User user = userService.findById(id);
    UserDto dto = userMapper.toDto(user);
    if (isCrossSquadronNonAdmin(user)) {
      return redactToPeerShape(dto);
    }
    return redactForPeerIfNeeded(dto);
  }

  /**
   * Lists the org units a user belongs to as picker-optimised {@link OrgUnitMembershipOptionDto}
   * rows for the owner picker.
   *
   * <p>Open to every authenticated member and {@code BANK_EMPLOYEE} (REQ-BANK-044); the response
   * carries only org-unit names and shorthands, no personal data.
   *
   * @param id the user id whose memberships to list; never {@code null}.
   * @param allKinds {@code true} to include all four org-unit kinds (Staffel, SK, Bereich,
   *     Organisationsleitung); {@code false} for Staffel and SK only.
   * @return option DTOs sorted Staffel-first then SK alphabetical, or top-down by kind when {@code
   *     allKinds=true}; never {@code null}, possibly empty.
   */
  @GetMapping("/{id}/memberships")
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
  public List<OrgUnitMembershipOptionDto> getUserMemberships(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false, defaultValue = "false") boolean allKinds) {
    return allKinds
        ? orgUnitMembershipQueryService.listDirectMembershipOptions(id)
        : orgUnitMembershipQueryService.listOptionsForUser(id);
  }

  /**
   * Returns whether the caller is a non-admin who can see none of the target user's Staffeln via
   * {@code OwnerScopeService}. A target without any squadron counts as cross-squadron.
   *
   * @param user target user resolved by id; never {@code null}
   * @return {@code true} if the caller is a non-admin and shares none of the user's squadrons
   */
  private boolean isCrossSquadronNonAdmin(@NotNull User user) {
    if (authHelperService.isAdmin()) {
      return false;
    }
    List<UUID> targetSquadronIds =
        orgUnitMembershipQueryService.findStaffelMembershipOrgUnitIds(user.getId());
    if (targetSquadronIds.isEmpty()) {
      return true;
    }
    return targetSquadronIds.stream().noneMatch(authHelperService::canSeeSquadron);
  }

  /**
   * Returns the calling user's own record, derived from the JWT subject.
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}
   * @return the user DTO
   */
  @NotNull
  @GetMapping("/me")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public UserDto getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
    User me = userService.findById(userService.getUserIdFromJwt(jwt));
    return withSelfEmail(userMapper.toDto(me), me);
  }

  /**
   * Returns the calling user's pickable owning-org-unit options for create forms: direct
   * memberships plus the units reachable through leadership (REQ-ORG-016, REQ-ORG-018).
   *
   * <p>Resolved for the caller only; for an ordinary member it equals their direct memberships.
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @return the caller's pickable org-unit options across all reachable kinds; never {@code null}.
   */
  @GetMapping("/me/pickable-org-units")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public List<OrgUnitMembershipOptionDto> getMyPickableOrgUnits(@AuthenticationPrincipal Jwt jwt) {
    return orgUnitMembershipQueryService.listPickerOptionsWithDescendants(
        userService.getUserIdFromJwt(jwt));
  }

  /**
   * Lists the org units the calling user is a direct member of, as {@link
   * OrgUnitMembershipOptionDto} rows; the JWT-resolved twin of {@code GET /{id}/memberships}.
   *
   * <p>Open to every authenticated caller; no memberships yield an empty list, not 403.
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @param allKinds {@code true} to include all four org-unit kinds (Staffel, SK, Bereich,
   *     Organisationsleitung); {@code false} for Staffel and SK only.
   * @return option DTOs, sorted as the sibling endpoint sorts them; never {@code null}, possibly
   *     empty.
   */
  @GetMapping("/me/memberships")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public List<OrgUnitMembershipOptionDto> getMyMemberships(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false, defaultValue = "false") boolean allKinds) {
    UUID callerId = userService.getUserIdFromJwt(jwt);
    return allKinds
        ? orgUnitMembershipQueryService.listDirectMembershipOptions(callerId)
        : orgUnitMembershipQueryService.listOptionsForUser(callerId);
  }

  /**
   * Returns the ids of every org unit the calling user is a direct member of, across all kinds and
   * without leadership cascade (REQ-MISSION-012).
   *
   * <p>Open to every authenticated caller; no memberships yield an empty set.
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @return the caller's direct org-unit ids; never {@code null}, possibly empty.
   */
  @GetMapping("/me/org-unit-ids")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public Set<UUID> getMyOrgUnitIds(@AuthenticationPrincipal Jwt jwt) {
    return orgUnitMembershipQueryService.findDirectMembershipOrgUnitIds(
        userService.getUserIdFromJwt(jwt));
  }

  /**
   * Updates the calling user's own description + displayName. The JWT identifies the row — no
   * impersonation possible.
   *
   * @param request update payload (carries the expected version)
   * @return the persisted DTO
   */
  @NotNull
  @PutMapping("/me/description")
  @PreAuthorize("isAuthenticated()")
  public UserDto updateMyDescription(
      @AuthenticationPrincipal Jwt jwt,
      @NotNull @RequestBody @Valid UserDescriptionRequest request) {
    User me =
        userService.updateUserDescription(
            userService.getUserIdFromJwt(jwt),
            request.getDescription(),
            request.getDisplayName(),
            request.getVersion());
    return withSelfEmail(userMapper.toDto(me), me);
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
  @GetMapping("/me/payout-preference")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public MyPayoutPreferenceResponse getMyPayoutPreference(@AuthenticationPrincipal Jwt jwt) {
    User me = userService.findById(userService.getUserIdFromJwt(jwt));
    return new MyPayoutPreferenceResponse(me.getDefaultPayoutPreference(), me.getVersion());
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
  @PutMapping("/me/payout-preference")
  @PreAuthorize("isAuthenticated()")
  public MyPayoutPreferenceResponse updateMyPayoutPreference(
      @AuthenticationPrincipal Jwt jwt,
      @NotNull @RequestBody @Valid MyPayoutPreferenceRequest request) {
    User me =
        userService.updateUserDefaultPayoutPreference(
            userService.getUserIdFromJwt(jwt), request.preference(), request.version());
    return new MyPayoutPreferenceResponse(me.getDefaultPayoutPreference(), me.getVersion());
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
  @GetMapping("/me/blueprint-sharing")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public MyBlueprintSharingResponse getMyBlueprintSharing(@AuthenticationPrincipal Jwt jwt) {
    User me = userService.findById(userService.getUserIdFromJwt(jwt));
    return new MyBlueprintSharingResponse(me.isShareBlueprintsGlobally(), me.getVersion());
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
  @PutMapping("/me/blueprint-sharing")
  @PreAuthorize("isAuthenticated()")
  public MyBlueprintSharingResponse updateMyBlueprintSharing(
      @AuthenticationPrincipal Jwt jwt,
      @NotNull @RequestBody @Valid MyBlueprintSharingRequest request) {
    User me =
        userService.updateUserShareBlueprintsGlobally(
            userService.getUserIdFromJwt(jwt),
            request.shareBlueprintsGlobally(),
            request.version());
    return new MyBlueprintSharingResponse(me.isShareBlueprintsGlobally(), me.getVersion());
  }

  /**
   * Records that the calling user has read the given announcement (clears the unread badge).
   *
   * @param announcementId announcement just read
   * @return the persisted DTO
   */
  @NotNull
  @PutMapping("/me/read-announcement/{announcementId}")
  @PreAuthorize("isAuthenticated()")
  public UserDto updateReadAnnouncement(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @NotNull UUID announcementId) {
    User me = userService.updateReadAnnouncement(userService.getUserIdFromJwt(jwt), announcementId);
    return withSelfEmail(userMapper.toDto(me), me);
  }

  /**
   * Admin/officer-only: edits an arbitrary user's attributes (rank, description, displayName,
   * joinDate). Carries optimistic-lock version in the body so concurrent admin edits surface a 409
   * instead of silently overwriting.
   *
   * @param id user id
   * @param request typed body (NOT query params — keeps user values out of access logs)
   * @return the persisted DTO
   */
  @PutMapping("/{id}/attributes")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public UserDto updateUserAttributes(
      @PathVariable @NotNull UUID id, @NotNull @RequestBody @Valid UserAttributesRequest request) {
    return userMapper.toDto(
        userService.updateUserAttributes(
            id,
            request.getRank(),
            request.getDescription(),
            request.getDisplayName(),
            request.getVersion(),
            request.getJoinDate()));
  }

  /**
   * Applies a membership delta (Staffel assignments, flag toggles, SK add/remove/patch) for a user
   * in one atomic transaction.
   *
   * <p>Each change carries its own {@code version}; one stale row rolls back the whole batch with
   * 409.
   *
   * @param id user primary key.
   * @param request the delta to apply; never {@code null}, but both halves may be {@code null} /
   *     empty.
   * @return the user's complete post-write membership list.
   */
  @NotNull
  @PatchMapping("/{id}/memberships")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public MembershipDeltaResponse patchMemberships(
      @PathVariable @NotNull UUID id, @RequestBody @Valid MembershipDeltaRequest request) {
    userService.applyMembershipDelta(id, request);
    return new MembershipDeltaResponse(
        orgUnitMembershipQueryService.findAllMembershipDtosForUser(id));
  }

  /**
   * Returns the user's complete membership set as full DTOs with per-membership flags and {@code
   * version}, backing the admin member-edit form (ADMIN only).
   *
   * @param id user primary key.
   * @return the user's memberships (Staffel + every SK) as full {@link OrgUnitMembershipDto}s,
   *     wrapped in the same response shape the membership-delta PATCH returns.
   */
  @NotNull
  @GetMapping("/{id}/memberships/detail")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public MembershipDeltaResponse getMembershipsDetail(@PathVariable @NotNull UUID id) {
    return new MembershipDeltaResponse(
        orgUnitMembershipQueryService.findAllMembershipDtosForUser(id));
  }

  /**
   * ADMIN-only: deletes a user account along with all owned data (ships, inventory, refinery
   * orders, mission memberships).
   *
   * @param id user id
   */
  @DeleteMapping("/{id}")
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
  @PostMapping("/{id}/consolidate")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public UserDto consolidateAccount(
      @PathVariable @NotNull UUID id,
      @CurrentUserId UUID adminUserId,
      @NotNull @RequestBody @Valid ConsolidateAccountRequest body) {
    return userMapper.toDto(
        accountConsolidationService.consolidate(
            id, body.targetUserId(), body.version(), adminUserId));
  }

  /** Body for {@link #updateUserAttributes}. */
  @Data
  public static class UserAttributesRequest {
    @jakarta.validation.constraints.NotNull private Integer rank;

    @Size(max = 10_000)
    private String description;

    @Size(max = 255)
    private String displayName;

    @jakarta.validation.constraints.NotNull private Long version;
    @Nullable private LocalDate joinDate;
  }

  /** Body for {@link #updateMyDescription}. */
  @Data
  public static class UserDescriptionRequest {
    @Size(max = 10_000)
    private String description;

    @Size(max = 255)
    private String displayName;

    @jakarta.validation.constraints.NotNull private Long version;
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
   * Reduces a user DTO to the peer shape for callers below logistician; logisticians and above get
   * it unchanged.
   *
   * <p>Email is never present here, since {@link UserMapper#toDto(User)} omits it.
   *
   * @param dto the persisted user DTO
   * @return the redacted DTO for non-elevated callers, otherwise the original
   */
  private UserDto redactForPeerIfNeeded(UserDto dto) {
    if (dto == null || authHelperService.isLogisticianOrAbove()) {
      return dto;
    }
    return redactToPeerShape(dto);
  }

  /**
   * Returns the slim peer view of {@code dto} unconditionally, regardless of the caller's role.
   *
   * @param dto persisted user DTO; never {@code null}
   * @return the slim peer-view DTO
   */
  private UserDto redactToPeerShape(@NotNull UserDto dto) {
    return UserDtoRedaction.toPeerShape(dto);
  }

  /**
   * Re-attaches the caller's own {@code email} to a DTO produced by {@link UserMapper#toDto(User)},
   * which deliberately omits it. Used only by the {@code /me*} self endpoints: a user may always
   * see their own email in their own profile, while {@code toDto} keeps it {@code null} for every
   * other (peer / list / admin) projection so a user's email never reaches anyone else. All
   * non-email fields are copied straight from {@code dto}.
   *
   * @param dto the email-free DTO from the mapper; never {@code null}
   * @param user the caller's own entity, the source of the email; never {@code null}
   * @return a copy of {@code dto} with {@code email} populated from {@code user}
   */
  @NotNull
  private UserDto withSelfEmail(@NotNull UserDto dto, @NotNull User user) {
    return new UserDto(
        dto.id(),
        dto.username(),
        dto.displayName(),
        dto.effectiveName(),
        user.getEmail(),
        dto.rank(),
        dto.description(),
        dto.roles(),
        dto.permissions(),
        dto.lastReadAnnouncementId(),
        dto.isLogistician(),
        dto.isMissionManager(),
        dto.inKeycloak(),
        dto.squadron(),
        dto.squadrons(),
        dto.version(),
        dto.joinDate(),
        dto.discordLinked());
  }
}
