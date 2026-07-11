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
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserSyncResultDto;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.service.UserSyncService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
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
 * REST surface for the local {@code app_user} mirror. The {@code /me} endpoints derive the user id
 * from the JWT — never from the URL — so a caller can never impersonate another user via this path.
 * {@code /attributes}, the logistician/mission-manager toggles and {@code DELETE} are admin-scoped.
 *
 * <p>The class-level {@link Transactional} keeps the persistence session open across the {@code
 * userMapper.toDto} projection every endpoint returns: {@link UserMapper} resolves the caller's
 * Staffel + capability flags through {@code OrgUnitMembershipRepository} and reads the LAZY {@code
 * user.getRoles()} collection, both of which need an open session — the write endpoints that map
 * the just-saved user rely on it too. The membership mapping was moved into {@link
 * OrgUnitMembershipService} (L4, #923, ADR-0067), and the two membership endpoints project through
 * the service's own {@code …Dto} transactions without depending on this annotation — so it now
 * stands for {@code userMapper} alone, and retiring it only requires moving the {@code UserDto}
 * projection into the service layer as well.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Transactional
public class UserController {

  private static final Set<String> ALLOWED_SORT = Set.of("username", "email", "rank", "id");

  private final UserService userService;
  private final UserMapper userMapper;
  private final AuthHelperService authHelperService;
  private final OrgUnitMembershipService orgUnitMembershipService;
  private final UserSyncService userSyncService;
  private final TaskMetrics taskMetrics;

  /**
   * Admin-triggered manual run of the Keycloak user sync — the "Sync now" button on the
   * member-management page. Runs the same reconciliation as the hourly {@link
   * de.greluc.krt.profit.basetool.backend.task.UserSyncTask}, through {@link
   * TaskMetrics#recordCountingRethrow} so it publishes the identical {@code user_sync} meters (and
   * refreshes the {@code UserSyncStale} last-success gauge) yet still returns the synced count and
   * surfaces a failure as an RFC 7807 problem response. The caller re-reads the member list after a
   * 2xx to show the refreshed roster.
   *
   * <p>Runs {@code NOT_SUPPORTED} to opt out of the class-level {@link Transactional}: each {@code
   * userService.syncUser} and the bank-holder reconcile must open their OWN transaction (exactly as
   * on the scheduled path) rather than sharing one page-spanning transaction whose first failure
   * would poison the rest.
   *
   * @return the number of users reconciled this run
   */
  @PostMapping("/sync")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
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
    Page<de.greluc.krt.profit.basetool.backend.model.User> p = userService.findAll(pageable);
    return PageResponse.of(p.map(userMapper::toDto).map(this::redactForPeerIfNeeded));
  }

  /**
   * Lightweight typeahead projection (id + username + displayName). Widened by {@code
   * BANK_MANAGEMENT} (REQ-BANK-009): bank managers resolve grantees and holders via this lookup and
   * need not hold any org role (REQ-BANK-008). Also widened by {@code BANK_EMPLOYEE}
   * (REQ-BANK-044): bank employees resolve the deposit/withdrawal counterparty (Einzahler /
   * Empf&auml;nger) from this lookup and likewise need not hold any org role.
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
  public List<de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto> lookupUsers() {
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
   * Bank-audience twin of {@link #searchUsers}: the paged username/displayName substring search
   * behind the bank pickers that resolve grantees / holders across the whole user base (register a
   * holder, grant the Bank-Employee role, set an approval limit — ADR-0089, the {@code
   * remoteSource} scaling switch of #1193 / ADR-0053). Query, scope and projection are identical to
   * {@link #searchUsers} — {@link UserService#searchByUsername(String, Pageable)} already resolves
   * the same {@code currentUserListScopeSquadronIds} scope, which for an org-role-less bank manager
   * is the unfiltered all-users set. The ONLY difference is the widened role gate: it mirrors
   * {@code /lookup} (adds {@code BANK_EMPLOYEE}, which covers {@code BANK_MANAGEMENT} via the role
   * hierarchy) so a bank employee/manager who holds no org role can drive the picker. Kept as a
   * separate path rather than widening {@code /search} so the ordinary picker's authorization
   * regime stays unchanged (REQ-BANK-008/009/044).
   *
   * @return paged user DTOs (peer-redacted for non-elevated callers, exactly as {@code /search})
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
   * Shared body of the two user-search endpoints ({@link #searchUsers} / {@link
   * #searchUsersForBank}): resolves the page request against the whitelisted sort fields, runs the
   * squadron-scoped username/displayName search, and maps each hit through the peer redaction. The
   * two endpoints differ only in their {@code @PreAuthorize} role gate, so the query + projection
   * live here once.
   *
   * <p>A {@code null} query — the browse-mode empty {@code ?query=} that the global {@code
   * emptyAsNull} string binder collapses to {@code null} — is normalised to the empty string, which
   * the {@code LIKE '%%'} search treats as a match-all filter. This makes opening a {@code
   * remoteSource} picker without typing return the scoped roster rather than 500ing on the required
   * param (#1193).
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
    Page<de.greluc.krt.profit.basetool.backend.model.User> p =
        userService.searchByUsername(query == null ? "" : query, pageable);
    return PageResponse.of(p.map(userMapper::toDto).map(this::redactForPeerIfNeeded));
  }

  /**
   * Returns the user DTO. Multi-tenancy: a non-admin caller asking for a user that belongs to a
   * foreign squadron always gets the peer-redacted shape, even when the caller carries {@code
   * ROLE_LOGISTICIAN} or {@code ROLE_OFFICER}. Without the squadron-scope gate an officer of
   * squadron A could fetch the email / real name of any user in squadron B by guessing a UUID (the
   * list endpoints are squadron-scoped via {@link
   * de.greluc.krt.profit.basetool.backend.service.UserService#findAll}; this {@code byId} path was
   * the only multi-tenancy hole left after the 2026-05-20 audit, finding H-3).
   *
   * @param id user id
   * @return the user DTO, peer-redacted for cross-squadron non-admin callers
   */
  @GetMapping("/{id}")
  @PreAuthorize(
      "hasAnyRole('" + Roles.ADMIN + "', '" + Roles.OFFICER + "', '" + Roles.KRT_MEMBER + "')")
  @Transactional(readOnly = true)
  public UserDto getUserById(@PathVariable @NotNull UUID id) {
    de.greluc.krt.profit.basetool.backend.model.User user = userService.findById(id);
    UserDto dto = userMapper.toDto(user);
    if (isCrossSquadronNonAdmin(user)) {
      return redactToPeerShape(dto);
    }
    return redactForPeerIfNeeded(dto);
  }

  /**
   * Lists every org unit the given user is a member of, materialised as picker-optimised {@link
   * OrgUnitMembershipOptionDto} rows. Backs the R5.d owner-picker fragment: a Thymeleaf form with
   * an explicit target-user dropdown can populate its {@code <select>} of legal {@code
   * owningOrgUnitId} values directly from this endpoint, without having to thread membership data
   * through every page controller.
   *
   * <p>Access policy: open to every authenticated member, plus {@code BANK_EMPLOYEE} (REQ-BANK-044:
   * the deposit/withdrawal counterparty's org-unit picker resolves the chosen user's memberships
   * here, and a bank employee need not hold any org role per REQ-BANK-008). The endpoint reveals
   * only the names and shorthands of org units a target user belongs to — equivalent in sensitivity
   * to the {@code /lookup} typeahead, which is already broadly accessible. A non-admin cannot
   * derive any personally identifying data from the response (no display name, no email, no rank).
   * Keeping the surface symmetric with {@code /lookup} avoids forcing the picker fragment to branch
   * on the caller's role at render time.
   *
   * @param id the user id whose memberships to list; never {@code null}.
   * @param allKinds when {@code true} the response spans <strong>all four</strong> org-unit kinds
   *     (Staffel + SK + Bereich + Organisationsleitung) — the bank counterparty picker
   *     (REQ-BANK-044) where a Bereich/OL member's unit must be selectable; the default ({@code
   *     false}) keeps the legacy Staffel/SK-only owner-picker shape so the sidebar and Job-Order
   *     pickers are unchanged.
   * @return picker-friendly option DTOs sorted Staffel-first then SK alphabetical (default) or
   *     top-down by kind across all four kinds ({@code allKinds=true}); never {@code null},
   *     possibly empty when the user has no memberships.
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
        ? orgUnitMembershipService.listDirectMembershipOptions(id)
        : orgUnitMembershipService.listOptionsForUser(id);
  }

  /**
   * Returns {@code true} when the caller is a non-admin and the target user belongs to NO squadron
   * the caller can see via {@code OwnerScopeService}. Used to tighten {@link #getUserById} to "same
   * squadron or admin" for full-PII access. Users without a squadron (admins, unassigned) are
   * treated as cross-squadron for non-admin callers — full PII on an unassigned account remains
   * admin-only.
   *
   * <p>REQ-ORG-017: the target may now hold up to two Staffeln, so the gate ORs across ALL of the
   * target's Staffeln — the caller keeps full PII as soon as it can see ANY one of them, mirroring
   * the caller-side membership union. A same-Staffel peer is therefore never over-redacted just
   * because the shared Staffel happens not to be the target's name-sorted primary.
   *
   * @param user target user resolved by id; never {@code null}
   * @return {@code true} if the caller is a non-admin and shares none of the user's squadrons
   */
  private boolean isCrossSquadronNonAdmin(
      @NotNull de.greluc.krt.profit.basetool.backend.model.User user) {
    if (authHelperService.isAdmin()) {
      return false;
    }
    // Post-R9 D3 (V101): the user's home Staffel(n) are sourced from org_unit_membership — the
    // legacy User.squadron column was dropped.
    java.util.List<UUID> targetSquadronIds =
        orgUnitMembershipService.findStaffelMembershipOrgUnitIds(user.getId());
    if (targetSquadronIds.isEmpty()) {
      return true;
    }
    return targetSquadronIds.stream().noneMatch(authHelperService::canSeeSquadron);
  }

  /**
   * Returns the calling user's own record (derived from the JWT subject). The {@code @PreAuthorize}
   * is redundant with the {@link de.greluc.krt.profit.basetool.backend.config.SecurityConfig} URL
   * matcher that already gates {@code /api/v1/users/me} as {@code authenticated()}, but having it
   * on the handler keeps the guarantee local to this method — if the URL pattern is ever
   * refactored, the {@code @Jwt} binding here would NPE on anonymous instead of failing safely.
   * Audit finding L-2 (2026-05-20).
   *
   * @param jwt caller's JWT — never {@code null} thanks to the {@code @PreAuthorize}
   * @return the user DTO
   */
  @GetMapping("/me")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public UserDto getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
    de.greluc.krt.profit.basetool.backend.model.User me =
        userService.findById(userService.getUserIdFromJwt(jwt));
    return withSelfEmail(userMapper.toDto(me), me);
  }

  /**
   * Returns the calling user's <em>pickable</em> owning-org-unit options for the create/stamp forms
   * (epic #692 Phase 5, REQ-ORG-016 / REQ-ORG-018): the caller's direct memberships plus their
   * cascading leadership reach (a Bereichsleitung/OL leader's subordinate Staffeln/SKs and their
   * own Bereich/OL). This is the drill-down counterpart of {@code GET /{id}/memberships}, which
   * stays strictly the user's direct memberships and is shared by the admin member views and the
   * refinery-store/transfer receiver picker. Resolved for the <em>current caller only</em> (never
   * an arbitrary id) so it cannot enumerate another user's reach.
   *
   * <p>For an ordinary member the result equals their direct memberships, so the owner picker is
   * unchanged for non-leaders.
   *
   * @param jwt caller's JWT — never {@code null} thanks to the {@code @PreAuthorize}.
   * @return the caller's pickable org-unit options across all reachable kinds; never {@code null}.
   */
  @GetMapping("/me/pickable-org-units")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public List<OrgUnitMembershipOptionDto> getMyPickableOrgUnits(@AuthenticationPrincipal Jwt jwt) {
    return orgUnitMembershipService.listPickerOptionsWithDescendants(
        userService.getUserIdFromJwt(jwt));
  }

  /**
   * Returns the org-unit ids the calling user is a <em>direct</em> member of, across every kind
   * (Staffel / SK / Bereich / Organisationsleitung), with no leadership cascade. The home-page
   * upcoming-missions grid uses it to flag a mission whose owning org unit the caller is directly
   * assigned to with a "Meine Einheit" chip (REQ-MISSION-012). Resolved for the current caller only
   * (never an arbitrary id), and — unlike {@code GET /{id}/memberships} — open to every
   * authenticated user: a membership-less account simply gets an empty set rather than a 403. Only
   * opaque ids leave the API (no name, shorthand or kind), so the response carries no PII.
   *
   * @param jwt caller's JWT — never {@code null} thanks to the {@code @PreAuthorize}.
   * @return the caller's direct org-unit ids across all kinds; never {@code null}, possibly empty.
   */
  @GetMapping("/me/org-unit-ids")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public Set<UUID> getMyOrgUnitIds(@AuthenticationPrincipal Jwt jwt) {
    return orgUnitMembershipService.findDirectMembershipOrgUnitIds(
        userService.getUserIdFromJwt(jwt));
  }

  /**
   * Updates the calling user's own description + displayName. The JWT identifies the row — no
   * impersonation possible.
   *
   * @param request update payload (carries the expected version)
   * @return the persisted DTO
   */
  @PutMapping("/me/description")
  @PreAuthorize("isAuthenticated()")
  public UserDto updateMyDescription(
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody @jakarta.validation.Valid UserDescriptionRequest request) {
    de.greluc.krt.profit.basetool.backend.model.User me =
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
  @GetMapping("/me/payout-preference")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public MyPayoutPreferenceResponse getMyPayoutPreference(@AuthenticationPrincipal Jwt jwt) {
    de.greluc.krt.profit.basetool.backend.model.User me =
        userService.findById(userService.getUserIdFromJwt(jwt));
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
  @PutMapping("/me/payout-preference")
  @PreAuthorize("isAuthenticated()")
  public MyPayoutPreferenceResponse updateMyPayoutPreference(
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody @jakarta.validation.Valid MyPayoutPreferenceRequest request) {
    de.greluc.krt.profit.basetool.backend.model.User me =
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
  @GetMapping("/me/blueprint-sharing")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public MyBlueprintSharingResponse getMyBlueprintSharing(@AuthenticationPrincipal Jwt jwt) {
    de.greluc.krt.profit.basetool.backend.model.User me =
        userService.findById(userService.getUserIdFromJwt(jwt));
    return new MyBlueprintSharingResponse(me.isShareBlueprintsGlobally(), me.getVersion());
  }

  /**
   * Sets whether the calling user opts into global blueprint sharing (REQ-INV-018). The JWT
   * identifies the row — no impersonation possible. Carries the optimistic-lock version so a
   * concurrent edit surfaces as a 409 instead of a silent overwrite. Enabling the flag makes the
   * user's owned blueprints count toward the leadership availability overview and the item-order
   * blueprint-coverage view for every org unit (read-only, name-only exposure); the viewer-access
   * gates are unchanged.
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @param request the new opt-in value plus the expected version.
   * @return the persisted flag and the new version.
   */
  @PutMapping("/me/blueprint-sharing")
  @PreAuthorize("isAuthenticated()")
  public MyBlueprintSharingResponse updateMyBlueprintSharing(
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody @jakarta.validation.Valid MyBlueprintSharingRequest request) {
    de.greluc.krt.profit.basetool.backend.model.User me =
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
  @PutMapping("/me/read-announcement/{announcementId}")
  @PreAuthorize("isAuthenticated()")
  public UserDto updateReadAnnouncement(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @NotNull UUID announcementId) {
    de.greluc.krt.profit.basetool.backend.model.User me =
        userService.updateReadAnnouncement(userService.getUserIdFromJwt(jwt), announcementId);
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
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public UserDto updateUserAttributes(
      @PathVariable @NotNull UUID id,
      @RequestBody @jakarta.validation.Valid UserAttributesRequest request) {
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
   * SPEZIALKOMMANDO_PLAN.md §7.4 single-POST membership-delta endpoint. Lets the admin member-edit
   * page persist every Staffel-assignment + flag-toggle + SK add / remove / patch as one atomic
   * transaction. Per-row optimistic-lock survives because every change record carries its own
   * {@code version}; an inconsistent batch (one row stale, the rest fresh) rolls back the whole
   * transaction and surfaces as a 409 — partial application is not exposed.
   *
   * <p>The response is re-read and mapped through {@link
   * OrgUnitMembershipService#findAllMembershipDtosForUser(UUID)} rather than by projecting the
   * entities the delta call returns: the projection then runs inside the membership service's own
   * transaction, so this endpoint does not depend on the class-level {@link Transactional} staying
   * open across the two service calls (ADR-0067) and survives its planned retirement.
   *
   * @param id user primary key.
   * @param request the delta to apply; never {@code null}, but both halves may be {@code null} /
   *     empty.
   * @return the user's complete post-write membership list.
   */
  @PatchMapping("/{id}/memberships")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public MembershipDeltaResponse patchMemberships(
      @PathVariable @NotNull UUID id,
      @RequestBody @jakarta.validation.Valid MembershipDeltaRequest request) {
    userService.applyMembershipDelta(id, request);
    return new MembershipDeltaResponse(orgUnitMembershipService.findAllMembershipDtosForUser(id));
  }

  /**
   * Reads the user's complete membership set as full DTOs — each carrying its per-membership
   * Logistician / Mission-Manager flags (REQ-SEC-005) and optimistic-lock {@code version} (ADMIN
   * only). Backs the member-edit page, which seeds its editable Staffel slots (REQ-ORG-017 — up to
   * two Staffeln, each with its own flags) from this view. Distinct from {@code GET
   * /{id}/memberships}: that endpoint returns the lean picker-option projection consumed by the
   * owner picker and carries neither flags nor version, so it cannot back an editable form.
   *
   * @param id user primary key.
   * @return the user's memberships (Staffel + every SK) as full {@link OrgUnitMembershipDto}s,
   *     wrapped in the same response shape the membership-delta PATCH returns.
   */
  @GetMapping("/{id}/memberships/detail")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public MembershipDeltaResponse getMembershipsDetail(@PathVariable @NotNull UUID id) {
    return new MembershipDeltaResponse(orgUnitMembershipService.findAllMembershipDtosForUser(id));
  }

  /**
   * ADMIN-only: deletes a user account along with all owned data (ships, inventory, refinery
   * orders, mission memberships).
   *
   * @param id user id
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public void deleteUser(@PathVariable @NotNull UUID id) {
    userService.deleteUser(id);
  }

  /** Body for {@link #updateUserAttributes}. */
  @lombok.Data
  public static class UserAttributesRequest {
    @jakarta.validation.constraints.NotNull private Integer rank;

    // Bound the free-text fields (security audit L1): description is a TEXT column with no DB
    // backstop, so without @Size an authenticated caller could store a multi-MB blob per write.
    @jakarta.validation.constraints.Size(max = 10_000)
    private String description;

    @jakarta.validation.constraints.Size(max = 255)
    private String displayName;

    @jakarta.validation.constraints.NotNull private Long version;
    @org.jetbrains.annotations.Nullable private LocalDate joinDate;
  }

  /** Body for {@link #updateMyDescription}. */
  @lombok.Data
  public static class UserDescriptionRequest {
    // Bound the free-text self-service fields (security audit L1): description maps to a TEXT
    // column
    // with no DB length backstop. @Size only rejects over-length input; the fields stay nullable
    // (a null description means "no change", a blank displayName clears it) so partial-update
    // semantics are unchanged — do NOT add @NotBlank.
    @jakarta.validation.constraints.Size(max = 10_000)
    private String description;

    @jakarta.validation.constraints.Size(max = 255)
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
      @org.jetbrains.annotations.Nullable PayoutPreference defaultPayoutPreference, Long version) {}

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
   * Strips the non-email PII that a peer (non-Officer, non-Admin) does not need to see. Officers
   * and admins get the DTO unchanged; plain members get the slim peer shape. {@code email} is no
   * longer governed here at all — {@link UserMapper#toDto(User)} omits it for every projection (it
   * is re-added only on the {@code /me*} self path via {@link #withSelfEmail}), so even an
   * officer/admin never receives a peer's email through this controller. This helper now only hides
   * the remaining peer-irrelevant fields from plain members. Audit finding H-4: previously any
   * KRT_MEMBER could paginate {@code /api/v1/users/search} and harvest every member's email.
   *
   * <p>The peer view keeps {@code id}, {@code username}, {@code displayName}, {@code
   * effectiveName}, {@code rank}, {@code inKeycloak}, {@code squadron}, {@code version} — enough
   * for the participant pickers in the mission editor to identify peers visually; drops {@code
   * description}, {@code roles}, {@code permissions}, {@code lastReadAnnouncementId}, {@code
   * isLogistician}, {@code isMissionManager}, {@code joinDate} (and {@code email}, already {@code
   * null} from the mapper).
   *
   * @param dto the persisted user DTO
   * @return the redacted DTO for non-elevated callers, or the original for officer/admin
   */
  private UserDto redactForPeerIfNeeded(UserDto dto) {
    if (dto == null || authHelperService.isLogisticianOrAbove()) {
      return dto;
    }
    return redactToPeerShape(dto);
  }

  /**
   * Returns the slim peer view of {@code dto} unconditionally — drops PII fields ({@code email},
   * description, roles, permissions, flags, joinDate, lastReadAnnouncementId) and keeps the public
   * callsign tuple. Used by {@link #getUserById} for the cross-squadron non-admin path (audit
   * finding H-3), where role-based escalation does NOT widen the view — an officer of squadron A
   * must not see PII of squadron B's members.
   *
   * @param dto persisted user DTO; never {@code null}
   * @return the slim peer-view DTO
   */
  private UserDto redactToPeerShape(@NotNull UserDto dto) {
    return new UserDto(
        dto.id(),
        dto.username(),
        dto.displayName(),
        dto.effectiveName(),
        null, // email
        dto.rank(),
        null, // description
        null, // roles
        null, // permissions
        null, // lastReadAnnouncementId
        false, // isLogistician
        false, // isMissionManager
        dto.inKeycloak(),
        dto.squadron(),
        dto.squadrons(),
        dto.version(),
        null, // joinDate
        null // discordLinked – Discord-link status is not exposed to peers (admin-only column)
        );
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
  private UserDto withSelfEmail(
      @NotNull UserDto dto, @NotNull de.greluc.krt.profit.basetool.backend.model.User user) {
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
