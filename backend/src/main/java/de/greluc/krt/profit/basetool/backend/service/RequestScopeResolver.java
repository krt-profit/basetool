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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.support.RequestMemo;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.support.StaffelMembershipResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the caller's effective org-unit context for the current request: the active pin, the
 * {@link ScopePredicate} scope vectors, the caller's memberships and oversight reach, memoised per
 * request.
 *
 * <p>{@link AccessGateService} and {@link OrgUnitStampingService} build on it; {@link
 * OwnerScopeService} is the facade the {@code @PreAuthorize} expressions call. Read-only
 * transactional.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestScopeResolver {

  /**
   * HTTP request header carrying the caller's active OrgUnit pin; absent means no pin. An admin pin
   * is honoured directly, a non-admin pin only when it matches one of the caller's memberships.
   */
  public static final String ACTIVE_ORG_UNIT_HEADER = "X-Active-Org-Unit-Id";

  /**
   * Request-attribute key under which the result of {@link #readPersistentSquadronFromUser()} is
   * cached for the duration of the current HTTP request. Stored as {@code Optional<UUID>} (never
   * {@code null}) so the cache can distinguish "resolved to empty" from "not yet resolved".
   */
  private static final RequestMemo.Key<Optional<UUID>> CACHE_KEY_PERSISTENT_USER_SQUADRON_ID =
      RequestMemo.Key.of(RequestScopeResolver.class, "persistentUserSquadronId");

  /**
   * Request-attribute key under which the result of {@link #currentSquadron()} is cached for the
   * duration of the current HTTP request. Same distinction-via-Optional contract as {@link
   * #CACHE_KEY_PERSISTENT_USER_SQUADRON_ID}.
   */
  private static final RequestMemo.Key<Optional<Squadron>> CACHE_KEY_CURRENT_SQUADRON =
      RequestMemo.Key.of(RequestScopeResolver.class, "currentSquadron");

  /**
   * Request-memo key for {@link #currentMemberOrgUnitIds()}, so the membership read happens at most
   * once per request.
   */
  private static final RequestMemo.Key<Set<UUID>> CACHE_KEY_MEMBER_ORG_UNIT_IDS =
      RequestMemo.Key.of(RequestScopeResolver.class, "memberOrgUnitIds");

  /**
   * Request-memo key for {@link #currentCallerMemberships()}, so the caller's membership rows are
   * read once per request.
   */
  private static final RequestMemo.Key<List<OrgUnitMembership>> CACHE_KEY_CALLER_MEMBERSHIPS =
      RequestMemo.Key.of(RequestScopeResolver.class, "callerMemberships");

  /**
   * Request-memo key for {@link #canViewJobOrders()}, so the profit-eligibility query runs once per
   * request even when checked per row.
   */
  private static final RequestMemo.Key<Boolean> CACHE_KEY_CAN_VIEW_JOB_ORDERS =
      RequestMemo.Key.of(RequestScopeResolver.class, "canViewJobOrders");

  private final AuthHelperService authHelper;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final OrgUnitCascadeService orgUnitCascadeService;
  private final StaffelMembershipResolver staffelMembershipResolver;
  private final HttpServletRequest request;

  /**
   * Returns the org unit that filters the current request: the pin header for an admin, the
   * persistent home Staffel for everyone else. Memoised per request.
   *
   * @return the active org-unit id; empty means "all squadrons" for an admin and "no access"
   *     otherwise.
   */
  @NotNull
  public Optional<UUID> currentSquadronId() {
    if (authHelper.isAdmin()) {
      return readActiveSquadronFromHeader();
    }
    return readPersistentSquadronFromUser();
  }

  /**
   * Plan-aligned alias for {@link #currentSquadronId()}. R2.d will start migrating call sites to
   * the org-unit-named API; the existing {@code currentSquadronId()} method stays so the
   * compatibility shim keeps working without translation.
   *
   * @return the active org-unit id, or empty when no filter applies.
   */
  @NotNull
  public Optional<UUID> currentOrgUnitId() {
    return currentSquadronId();
  }

  /**
   * Returns the effective scope vector of the current request as a {@link ScopePredicate}.
   *
   * <ul>
   *   <li>Admin without pin: {@code adminAllScope=true}.
   *   <li>Admin with pin: {@code activeOrgUnitId} set, own memberships ignored.
   *   <li>Non-admin: {@code memberOrgUnitIds} is the union of their memberships and cascaded reach.
   * </ul>
   *
   * @return a never-null scope vector describing what the current request should see.
   * @throws AuthenticationCredentialsNotFoundException when the current request carries no
   *     authenticated caller.
   */
  @NotNull
  public ScopePredicate currentScopePredicate() {
    if (!authHelper.isAuthenticated()) {
      throw new AuthenticationCredentialsNotFoundException(
          "No scope for an unauthenticated caller — every scoped read requires a login"
              + " (REQ-SEC-052). Reaching this means an endpoint lost its gate.");
    }
    if (authHelper.isAdmin()) {
      Optional<UUID> active = readActiveSquadronFromHeader();
      return active
          .map(id -> new ScopePredicate(false, id, Set.of()))
          .orElseGet(() -> new ScopePredicate(true, null, Set.of()));
    }
    Set<UUID> memberOrgUnitIds = currentMemberOrgUnitIds();
    Optional<UUID> pinned = readActiveSquadronFromHeader();
    if (pinned.isPresent() && memberOrgUnitIds.contains(pinned.get())) {
      return new ScopePredicate(false, pinned.get(), Set.of());
    }
    return new ScopePredicate(false, null, memberOrgUnitIds);
  }

  /**
   * Scope for the hangar unit overview (REQ-HANGAR-003): like {@link #currentScopePredicate()},
   * except that an unpinned OL member gets {@code adminAllScope}, including ownerless ships
   * (ADR-0048). Grants no other admin rights.
   *
   * @return {@code adminAllScope} for an unpinned OL member, otherwise exactly {@link
   *     #currentScopePredicate()}.
   */
  @NotNull
  public ScopePredicate currentUnitOverviewScope() {
    ScopePredicate base = currentScopePredicate();
    if (!base.adminAllScope()
        && base.activeOrgUnitId() == null
        && !authHelper.isAdmin()
        && currentUserIsOlMember()) {
      return new ScopePredicate(true, null, Set.of());
    }
    return base;
  }

  /**
   * Resolves the Staffel scope set for the user-list, search, typeahead and promotion-matrix
   * queries (REQ-ORG-017).
   *
   * <ul>
   *   <li>admin without a pin: {@code null};
   *   <li>any caller with a pin: the pinned id;
   *   <li>non-admin without a pin: their own Staffel ids, or {@code null} when they have none.
   * </ul>
   *
   * @return a non-empty squadron id set to filter on, or {@code null} for no filter.
   */
  @Nullable
  public Set<UUID> currentUserListScopeSquadronIds() {
    if (authHelper.isAdmin()) {
      Optional<UUID> pin = readActiveSquadronFromHeader();
      return pin.<Set<UUID>>map(Set::of).orElse(null);
    }
    Optional<UUID> callerId = authHelper.currentUserId();
    if (callerId.isEmpty()) {
      return null;
    }
    List<OrgUnitMembership> rows =
        orgUnitMembershipRepository.findAllByIdUserIdAndKind(callerId.get(), OrgUnitKind.SQUADRON);
    if (rows.isEmpty()) {
      return null;
    }
    Optional<UUID> pinned = readActiveSquadronFromHeader();
    if (pinned.isPresent()
        && rows.stream().anyMatch(r -> r.getId().getOrgUnitId().equals(pinned.get()))) {
      return Set.of(pinned.get());
    }
    Set<UUID> ids = new LinkedHashSet<>();
    for (OrgUnitMembership m : rows) {
      ids.add(m.getId().getOrgUnitId());
    }
    return ids;
  }

  /**
   * Whether the non-admin caller holds more than one Staffel and has pinned none, so a
   * single-Staffel create would have to guess (REQ-ORG-017). Such creates reject with a 400. Always
   * {@code false} for admins.
   *
   * @return {@code true} iff a single-Staffel auto-stamp would be ambiguous for the current caller.
   */
  public boolean hasAmbiguousStaffelContext() {
    if (authHelper.isAdmin()) {
      return false;
    }
    Optional<UUID> callerId = authHelper.currentUserId();
    if (callerId.isEmpty()) {
      return false;
    }
    List<OrgUnitMembership> rows =
        orgUnitMembershipRepository.findAllByIdUserIdAndKind(callerId.get(), OrgUnitKind.SQUADRON);
    if (rows.size() <= 1) {
      return false;
    }
    Optional<UUID> pinned = readActiveSquadronFromHeader();
    return pinned.isEmpty()
        || rows.stream().noneMatch(r -> r.getId().getOrgUnitId().equals(pinned.get()));
  }

  /**
   * Resolves every OrgUnit the non-admin caller reaches: direct memberships plus the leadership
   * cascade of {@link OrgUnitCascadeService#expandWithDescendants(java.util.Collection)}
   * (REQ-ORG-015). Always a concrete id set, never an admin grant; empty for anonymous callers.
   * Memoised per request.
   *
   * @return the OrgUnit ids the caller is a member of or leads by cascade, never {@code null}.
   */
  @NotNull
  public Set<UUID> currentMemberOrgUnitIds() {
    return RequestMemo.get(
        request,
        CACHE_KEY_MEMBER_ORG_UNIT_IDS,
        () -> orgUnitCascadeService.expandWithDescendants(currentCallerMemberships()));
  }

  /**
   * Returns the caller's {@code org_unit_membership} rows, memoised per request (REQ-DATA-003);
   * empty for an anonymous caller.
   *
   * @return the caller's membership rows, never {@code null}.
   */
  @NotNull
  private List<OrgUnitMembership> currentCallerMemberships() {
    return RequestMemo.get(
        request,
        CACHE_KEY_CALLER_MEMBERSHIPS,
        () ->
            authHelper
                .currentUserId()
                .map(orgUnitMembershipRepository::findAllByIdUserId)
                .orElseGet(List::of));
  }

  /**
   * Returns the org units the caller is a direct member of, without the leadership cascade; the
   * "own placed orders" key of the requester-side job-order access (REQ-ORDERS-023).
   *
   * @return the caller's direct-membership org-unit ids, never {@code null}.
   */
  @NotNull
  public Set<UUID> currentDirectMembershipOrgUnitIds() {
    Set<UUID> ids = new LinkedHashSet<>();
    for (OrgUnitMembership m : currentCallerMemberships()) {
      ids.add(m.getId().getOrgUnitId());
    }
    return ids;
  }

  /**
   * Whether the caller may see the job-order list and details: admins always, others only as a
   * member of at least one profit-eligible org unit. Memoised per request.
   *
   * @return {@code true} iff the caller may view job orders.
   */
  public boolean canViewJobOrders() {
    return RequestMemo.get(request, CACHE_KEY_CAN_VIEW_JOB_ORDERS, this::resolveCanViewJobOrders);
  }

  /**
   * Computes the {@link #canViewJobOrders()} verdict without the request-scoped memo — the admin
   * short-circuit, the empty-membership rejection, and the profit-eligibility count. Split out so
   * the public method only owns the caching.
   *
   * @return {@code true} iff the caller is an admin or a member of at least one profit-eligible org
   *     unit.
   */
  private boolean resolveCanViewJobOrders() {
    if (authHelper.isAdmin()) {
      return true;
    }
    Set<UUID> memberOrgUnitIds = currentMemberOrgUnitIds();
    if (memberOrgUnitIds.isEmpty()) {
      return false;
    }
    return orgUnitRepository.countProfitEligibleByIdIn(memberOrgUnitIds) > 0;
  }

  /**
   * Whether the caller may view and limited-edit the orders their own org unit placed
   * (REQ-ORDERS-023), regardless of profit eligibility: admins and any caller with at least one
   * membership.
   *
   * @return {@code true} iff the caller may view the orders their own org unit requested.
   */
  public boolean canViewOwnJobOrders() {
    if (authHelper.isAdmin()) {
      return true;
    }
    return !currentMemberOrgUnitIds().isEmpty();
  }

  /**
   * Whether the caller may open the org-unit blueprint availability overview: admins, officers, and
   * holders of an oversight seat (see {@link #isOversightSeat(OrgUnitMembership)}).
   *
   * @return {@code true} iff the caller is an admin, an officer, or holds at least one oversight
   *     seat.
   */
  public boolean canAccessBlueprintOverview() {
    if (authHelper.isAdmin() || authHelper.hasReachableRole(Roles.authority(Roles.OFFICER))) {
      return true;
    }
    return currentCallerMemberships().stream().anyMatch(RequestScopeResolver::isOversightSeat);
  }

  /**
   * Cascading read scope for the blueprint availability overview and the org-unit bank balances
   * (REQ-BANK-021): the org units the caller oversees, including subordinate units (REQ-ORG-015).
   *
   * <ul>
   *   <li>admin: {@link #currentScopePredicate()};
   *   <li>officer: their Staffel; SK lead: the SKs they lead;
   *   <li>Bereichsleitung / OL: their seat and everything below it, via {@link
   *       OrgUnitCascadeService#cascadedOfficerReach(java.util.Collection)}.
   * </ul>
   *
   * <p>A pin applies only when it names one of these units. An empty set means no rows.
   *
   * @return a never-null cascading scope vector of the org units whose data the caller may oversee.
   */
  @NotNull
  public ScopePredicate currentOversightScope() {
    if (authHelper.isAdmin()) {
      return currentScopePredicate();
    }
    Set<UUID> oversightOrgUnitIds = new LinkedHashSet<>();
    List<OrgUnitMembership> memberships = currentCallerMemberships();
    if (authHelper.hasReachableRole(Roles.authority(Roles.OFFICER))) {
      for (OrgUnitMembership m : memberships) {
        if (m.getKind() == OrgUnitKind.SQUADRON) {
          oversightOrgUnitIds.add(m.getId().getOrgUnitId());
        }
      }
    }
    for (OrgUnitMembership m : memberships) {
      if (m.getRole() == MembershipRole.SK_LEAD || m.getRole().isSquadronRank()) {
        oversightOrgUnitIds.add(m.getId().getOrgUnitId());
      }
    }
    oversightOrgUnitIds.addAll(orgUnitCascadeService.cascadedOfficerReach(memberships));
    Optional<UUID> pinned = readActiveSquadronFromHeader();
    if (pinned.isPresent() && oversightOrgUnitIds.contains(pinned.get())) {
      return new ScopePredicate(false, pinned.get(), Set.of());
    }
    return new ScopePredicate(false, null, oversightOrgUnitIds);
  }

  /**
   * Non-cascading own-level oversight scope, used for org-unit bank booking requests
   * (REQ-BANK-022): the officer's Staffel, the SKs an SK lead leads, the Bereichsleitung's Bereich
   * and the OL member's Organisationsleitung, never units below them. Admins get {@link
   * #currentScopePredicate()}.
   *
   * <p>A pin applies only when it names one of the caller's own-level seats.
   *
   * @return a never-null, non-cascaded scope vector of the caller's own-level oversight seats.
   */
  @NotNull
  public ScopePredicate currentOwnLevelOversightScope() {
    if (authHelper.isAdmin()) {
      return currentScopePredicate();
    }
    Set<UUID> ownLevelOrgUnitIds = new LinkedHashSet<>();
    List<OrgUnitMembership> memberships = currentCallerMemberships();
    if (authHelper.hasReachableRole(Roles.authority(Roles.OFFICER))) {
      for (OrgUnitMembership m : memberships) {
        if (m.getKind() == OrgUnitKind.SQUADRON) {
          ownLevelOrgUnitIds.add(m.getId().getOrgUnitId());
        }
      }
    }
    for (OrgUnitMembership m : memberships) {
      if (isOversightSeat(m)) {
        ownLevelOrgUnitIds.add(m.getId().getOrgUnitId());
      }
    }
    Optional<UUID> pinned = readActiveSquadronFromHeader();
    if (pinned.isPresent() && ownLevelOrgUnitIds.contains(pinned.get())) {
      return new ScopePredicate(false, pinned.get(), Set.of());
    }
    return new ScopePredicate(false, null, ownLevelOrgUnitIds);
  }

  /**
   * Whether the membership carries a functional rank ({@link
   * MembershipRole#confersOwnLevelOversight()}) and therefore oversight over its own org unit.
   *
   * @param m the membership row to classify; never {@code null}.
   * @return {@code true} iff the membership grants own-level oversight over its org unit.
   */
  private static boolean isOversightSeat(@NotNull OrgUnitMembership m) {
    return m.getRole().confersOwnLevelOversight();
  }

  /**
   * Whether the caller holds a Bereich- or OL-level seat ({@link MembershipRole#isAreaOrOl()}),
   * which reveals the cartel-wide special accounts on the org-unit bank page (REQ-BANK-028).
   * Excludes officers and SK leads; admins always qualify.
   *
   * @return {@code true} iff the caller is an admin or holds a Bereich-/OL-level oversight seat.
   */
  public boolean currentUserHasAreaOrOlOversight() {
    if (authHelper.isAdmin()) {
      return true;
    }
    return currentCallerMemberships().stream().anyMatch(RequestScopeResolver::isAreaOrOlSeat);
  }

  /**
   * Whether the membership is a Bereichsleitung or OL seat ({@link MembershipRole#isAreaOrOl()}).
   *
   * @param m the membership row to classify; never {@code null}.
   * @return {@code true} iff the membership is a Bereichsleitung or OL seat.
   */
  private static boolean isAreaOrOlSeat(@NotNull OrgUnitMembership m) {
    return m.getRole().isAreaOrOl();
  }

  /**
   * Whether the caller holds an {@code OL_MEMBER} seat. Pure membership check without an admin
   * short-circuit (REQ-BANK-037).
   *
   * @return {@code true} iff the caller has at least one {@code OL_MEMBER} membership.
   */
  public boolean currentUserIsOlMember() {
    return currentCallerMemberships().stream()
        .anyMatch(m -> m.getRole() == MembershipRole.OL_MEMBER);
  }

  /**
   * Whether the caller holds a {@code BEREICHSLEITER} seat on any Bereich. Pure membership check
   * without an admin short-circuit (REQ-BANK-037).
   *
   * @return {@code true} iff the caller has at least one {@code BEREICHSLEITER} membership.
   */
  public boolean currentUserIsBereichsleiter() {
    return currentCallerMemberships().stream()
        .anyMatch(m -> m.getRole() == MembershipRole.BEREICHSLEITER);
  }

  /**
   * Whether the caller holds exactly the given {@link MembershipRole} on the given org unit. Pure
   * membership check without an admin short-circuit.
   *
   * @param orgUnitId the org unit to check; never {@code null}
   * @param role the membership role to match; never {@code null}
   * @return {@code true} iff the caller has a membership on that unit carrying that role
   */
  public boolean currentUserHoldsRoleOnOrgUnit(
      @NotNull UUID orgUnitId, @NotNull MembershipRole role) {
    return currentCallerMemberships().stream()
        .anyMatch(m -> m.getId().getOrgUnitId().equals(orgUnitId) && m.getRole() == role);
  }

  /**
   * Whether the caller holds any membership on the given org unit, including a rank-less {@code
   * MEMBER} seat.
   *
   * @param orgUnitId the org unit to check; never {@code null}
   * @return {@code true} iff the caller has any membership on that unit
   */
  public boolean currentUserIsMemberOfOrgUnit(@NotNull UUID orgUnitId) {
    return currentCallerMemberships().stream()
        .anyMatch(m -> m.getId().getOrgUnitId().equals(orgUnitId));
  }

  /**
   * Whether the caller is a member of the given Bereich or of any of its child Staffeln and
   * Spezialkommandos (REQ-BANK-048).
   *
   * @param bereichId the owning Bereich org unit; never {@code null}
   * @return {@code true} iff the caller has any membership on the Bereich or one of its children
   */
  public boolean currentUserIsMemberOfAreaCascade(@NotNull UUID bereichId) {
    List<UUID> childIds = orgUnitRepository.findChildOrgUnitIds(bereichId);
    return currentCallerMemberships().stream()
        .anyMatch(
            m -> {
              UUID ou = m.getId().getOrgUnitId();
              return ou.equals(bereichId) || childIds.contains(ou);
            });
  }

  /**
   * Loads the {@link Squadron} matching {@link #currentSquadronId()}, memoised per request; used to
   * stamp {@code owningSquadron} on created aggregates that have no owner of their own.
   *
   * @return the {@link Squadron} for the current effective context, or empty when none applies.
   */
  @NotNull
  public Optional<Squadron> currentSquadron() {
    return RequestMemo.get(request, CACHE_KEY_CURRENT_SQUADRON, this::loadCurrentSquadron);
  }

  /**
   * The uncached load behind {@link #currentSquadron()}.
   *
   * @return the {@link Squadron} for the current effective context, or empty when none applies.
   */
  @NotNull
  private Optional<Squadron> loadCurrentSquadron() {
    return currentSquadronId()
        .flatMap(orgUnitRepository::findById)
        .map(ou -> Hibernate.unproxy(ou, OrgUnit.class))
        .filter(Squadron.class::isInstance)
        .map(Squadron.class::cast);
  }

  /**
   * Returns the {@link OrgUnit} matching {@link #currentOrgUnitId()}.
   *
   * @return the current effective {@link OrgUnit}, or empty when none applies.
   */
  @NotNull
  public Optional<OrgUnit> currentOrgUnit() {
    return currentSquadron().map(s -> (OrgUnit) s);
  }

  /**
   * Whether the per-squadron promotion feature flag is on for the caller's scope: the flag of the
   * effective (pinned or home) squadron, or {@code true} when there is none.
   *
   * @return {@code true} when the promotion menu may be exposed for the caller.
   */
  public boolean isPromotionFeatureEnabledForCurrentScope() {
    return currentSquadron().map(Squadron::isPromotionEnabled).orElse(true);
  }

  /**
   * Whether the caller may read any promotion data: admins and non-admins with an effective home
   * squadron. List and eligibility reads return empty otherwise.
   *
   * @return {@code true} for admins and for non-admins with an effective squadron.
   */
  public boolean hasPromotionReadAccess() {
    return authHelper.isAdmin() || currentSquadronId().isPresent();
  }

  /**
   * Throws {@link AccessDeniedException} when the promotion feature flag is off for the caller's
   * scope (see {@link #isPromotionFeatureEnabledForCurrentScope()}); called before every promotion
   * write.
   *
   * @throws AccessDeniedException if the flag is disabled for the caller's scope.
   */
  public void assertPromotionFeatureEnabled() {
    if (!isPromotionFeatureEnabledForCurrentScope()) {
      throw new AccessDeniedException(
          "Promotion feature is disabled for the caller's squadron; ask an administrator to"
              + " re-enable it.");
    }
  }

  /**
   * Reads the active-context pin from the {@link #ACTIVE_ORG_UNIT_HEADER} request header.
   *
   * @return the parsed active OrgUnit id, or empty when the header is absent, blank or malformed.
   */
  @NotNull
  public Optional<UUID> readActiveSquadronFromHeader() {
    return parseHeaderUuid(request.getHeader(ACTIVE_ORG_UNIT_HEADER));
  }

  /**
   * Parses a header value into a UUID without throwing.
   *
   * @param raw raw header value from {@link HttpServletRequest#getHeader(String)}; may be {@code
   *     null}.
   * @return parsed UUID, or empty on {@code null}, blank or malformed input.
   */
  @NotNull
  private static Optional<UUID> parseHeaderUuid(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(raw.trim()));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  /**
   * Resolves the current non-admin caller's single ACTIVE Staffel id from {@code
   * org_unit_membership} (kind=SQUADRON), memoised on the request. Honours an {@link
   * #ACTIVE_ORG_UNIT_HEADER} pin that points at one of the caller's own Staffel memberships;
   * otherwise falls back to the deterministic name-sorted primary owned by {@link
   * StaffelMembershipResolver}. Empty for an anonymous / Staffel-less caller.
   *
   * @return the caller's active Staffel id, or empty when none applies.
   */
  @NotNull
  private Optional<UUID> readPersistentSquadronFromUser() {
    return RequestMemo.get(
        request, CACHE_KEY_PERSISTENT_USER_SQUADRON_ID, this::resolvePersistentSquadronFromUser);
  }

  /**
   * The uncached resolution behind {@link #readPersistentSquadronFromUser()}.
   *
   * @return the caller's active Staffel id, or empty when none applies.
   */
  @NotNull
  private Optional<UUID> resolvePersistentSquadronFromUser() {
    return authHelper
        .currentUserId()
        .flatMap(
            userId -> {
              List<OrgUnitMembership> rows =
                  orgUnitMembershipRepository.findAllByIdUserIdAndKind(
                      userId, OrgUnitKind.SQUADRON);
              if (rows.isEmpty()) {
                return Optional.empty();
              }
              Optional<UUID> pinned = readActiveSquadronFromHeader();
              if (pinned.isPresent()
                  && rows.stream().anyMatch(r -> r.getId().getOrgUnitId().equals(pinned.get()))) {
                return pinned;
              }
              return staffelMembershipResolver.resolveNameSortedStaffelIds(rows).stream()
                  .findFirst();
            });
  }
}
