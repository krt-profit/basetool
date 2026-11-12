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

package de.greluc.krt.profit.basetool.backend.scope.api;

import de.greluc.krt.profit.basetool.backend.kernel.RequestMemo;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the caller's effective org-unit context for the current request: the honoured pin, the
 * {@link ScopePredicate} scope vectors, the caller's memberships and oversight reach, memoised per
 * request.
 *
 * <p>One pin rule holds for every surface (REQ-ORG-032): a pin counts only when it names a unit the
 * caller may select, and any other pin counts as no pin. {@link AccessGateService} and {@link
 * OrgUnitStampingService} build on it; {@code @PreAuthorize} expressions call it as the {@code
 * requestScopeResolver} bean. Read-only transactional.
 */
@Service("requestScopeResolver")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestScopeResolver {

  /**
   * HTTP request header carrying the caller's active OrgUnit pin; absent means no pin. It counts
   * only as {@link #currentPin()} allows.
   */
  public static final String ACTIVE_ORG_UNIT_HEADER = "X-Active-Org-Unit-Id";

  /**
   * Request-memo key for {@link #currentPin()}, an {@code Optional} so "resolved to no pin" is told
   * apart from "not yet resolved".
   */
  private static final RequestMemo.Key<Optional<UUID>> CACHE_KEY_PIN =
      RequestMemo.Key.of(RequestScopeResolver.class, "pin");

  /**
   * Request-memo key for {@link #currentOrgUnit()}, an {@code Optional} like {@link
   * #CACHE_KEY_PIN}.
   */
  private static final RequestMemo.Key<Optional<OrgUnit>> CACHE_KEY_CURRENT_ORG_UNIT =
      RequestMemo.Key.of(RequestScopeResolver.class, "currentOrgUnit");

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

  /** Request-memo key for {@link #currentOfficerOrgUnitIds()}. */
  private static final RequestMemo.Key<Set<UUID>> CACHE_KEY_OFFICER_ORG_UNIT_IDS =
      RequestMemo.Key.of(RequestScopeResolver.class, "officerOrgUnitIds");

  private final AuthHelperService authHelper;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final OrgUnitCascadeService orgUnitCascadeService;
  private final HttpServletRequest request;

  /**
   * Returns the active-context pin of the current request when the caller may select its unit
   * (REQ-ORG-032): an existing active org unit for an admin, a unit of {@link
   * #currentMemberOrgUnitIds()} for everyone else. A stale, foreign, unknown or malformed pin is
   * empty, and every surface then behaves as without a pin. Outside an HTTP request, as for
   * background work run as a caller, there is no pin, so such work meets the same gates unpinned
   * (ADR-0236 §6). Memoised per request.
   *
   * @return the honoured pin, or empty when the request carries none that counts
   */
  @NotNull
  public Optional<UUID> currentPin() {
    return RequestMemo.get(request, CACHE_KEY_PIN, this::resolvePin);
  }

  /**
   * The uncached resolution behind {@link #currentPin()}.
   *
   * @return the honoured pin, or empty
   */
  @NotNull
  private Optional<UUID> resolvePin() {
    if (!RequestMemo.readable(request)) {
      return Optional.empty();
    }
    Optional<UUID> raw = parseHeaderUuid(request.getHeader(ACTIVE_ORG_UNIT_HEADER));
    return raw.isPresent() && authHelper.isAuthenticated() && isSelectable(raw.get())
        ? raw
        : Optional.empty();
  }

  /**
   * Whether the caller may select the org unit as their active context: an admin every active unit,
   * everyone else the active units of their reach ({@link #currentMemberOrgUnitIds()}), which the
   * switcher lists.
   *
   * @param orgUnitId the org unit; never {@code null}
   * @return {@code true} iff a pin on the unit is honoured for the caller
   */
  public boolean isSelectable(@NotNull UUID orgUnitId) {
    return (authHelper.isAdmin() || currentMemberOrgUnitIds().contains(orgUnitId))
        && orgUnitRepository.existsByIdAndActiveTrue(orgUnitId);
  }

  /**
   * Returns what the active context of the current request covers (REQ-ORG-024): the honoured pin;
   * else for an admin every unit; else the caller's only unit, or all of their units when they
   * reach several, or none.
   *
   * @return the effective context; never {@code null}
   */
  @NotNull
  public ActiveOrgUnitContext currentActiveContext() {
    if (!authHelper.isAuthenticated()) {
      return ActiveOrgUnitContext.NONE;
    }
    Optional<UUID> pin = currentPin();
    if (pin.isPresent()) {
      return ActiveOrgUnitContext.orgUnit(pin.get());
    }
    if (authHelper.isAdmin()) {
      return ActiveOrgUnitContext.ALL_ORG_UNITS;
    }
    Set<UUID> reach = currentMemberOrgUnitIds();
    return switch (reach.size()) {
      case 0 -> ActiveOrgUnitContext.NONE;
      case 1 -> ActiveOrgUnitContext.orgUnit(reach.iterator().next());
      default -> ActiveOrgUnitContext.ALL_MY_ORG_UNITS;
    };
  }

  /**
   * Returns the effective scope vector of the current request as a {@link ScopePredicate}.
   *
   * <ul>
   *   <li>Admin without pin: {@code adminAllScope=true}.
   *   <li>Pinned caller: {@code activeOrgUnitId} set to the honoured pin, nothing else.
   *   <li>Non-admin without pin: {@code memberOrgUnitIds} is the union of their memberships and
   *       cascaded reach.
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
    Optional<UUID> pin = currentPin();
    if (pin.isPresent()) {
      return new ScopePredicate(false, pin.get(), Set.of());
    }
    return authHelper.isAdmin()
        ? new ScopePredicate(true, null, Set.of())
        : new ScopePredicate(false, null, currentMemberOrgUnitIds());
  }

  /**
   * The {@link #currentScopePredicate()} widened by the caller's grant of {@code roleName}: an
   * unpinned non-admin's scope also covers the units their Logistician or Mission Manager grant
   * reaches, cascade included (decision 18, REQ-SEC-005). A pinned caller and an admin get exactly
   * {@link #currentScopePredicate()}.
   *
   * @param roleName the contextual role of the surface, {@link Roles#LOGISTICIAN} or {@link
   *     Roles#MISSION_MANAGER}; never {@code null}
   * @return a never-null scope vector for the list queries of that surface
   */
  @NotNull
  public ScopePredicate currentScopePredicateGranting(@NotNull String roleName) {
    ScopePredicate base = currentScopePredicate();
    if (base.adminAllScope() || base.activeOrgUnitId() != null || authHelper.isAdmin()) {
      return base;
    }
    Set<UUID> granted = currentGrantedOrgUnitIds(roleName);
    if (granted.isEmpty()) {
      return base;
    }
    Set<UUID> reach = new LinkedHashSet<>(currentMemberOrgUnitIds());
    reach.addAll(granted);
    return new ScopePredicate(false, null, reach);
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
   * Resolves every OrgUnit the non-admin caller reaches: direct memberships plus the leadership
   * cascade of {@link OrgUnitCascadeService#expandWithDescendants(java.util.Collection)}
   * (REQ-ORG-015). A membership confers reach only while the caller holds {@code KRT Member}
   * (ADR-0300, REQ-SEC-015), and only on an active unit (REQ-ORG-032); without {@code KRT Member}
   * the set is empty. Always a concrete id set, never an admin grant; empty for anonymous callers.
   * Memoised per request.
   *
   * @return the OrgUnit ids the caller is a member of or leads by cascade, never {@code null}.
   */
  @NotNull
  public Set<UUID> currentMemberOrgUnitIds() {
    return RequestMemo.get(
        request,
        CACHE_KEY_MEMBER_ORG_UNIT_IDS,
        () ->
            authHelper.holdsKrtMember()
                ? orgUnitCascadeService.expandWithDescendants(currentCallerMemberships())
                : Set.of());
  }

  /**
   * Returns the caller's {@code org_unit_membership} rows on active org units, memoised per request
   * (REQ-DATA-003): a membership on a deactivated unit confers nothing until the unit is
   * reactivated (REQ-ORG-032). Empty for an anonymous caller.
   *
   * @return the caller's membership rows on active units, never {@code null}.
   */
  @NotNull
  private List<OrgUnitMembership> currentCallerMemberships() {
    return RequestMemo.get(
        request,
        CACHE_KEY_CALLER_MEMBERSHIPS,
        () ->
            authHelper
                .currentUserId()
                .map(orgUnitMembershipRepository::findAllOnActiveOrgUnitsByIdUserId)
                .orElseGet(List::of));
  }

  /**
   * Whether the caller may see the job-order list and details: admins always, others only as a
   * member of at least one active Profit-hierarchy org unit. Memoised per request.
   *
   * @return {@code true} iff the caller may view job orders.
   */
  public boolean canViewJobOrders() {
    return RequestMemo.get(request, CACHE_KEY_CAN_VIEW_JOB_ORDERS, this::resolveCanViewJobOrders);
  }

  /**
   * Computes the {@link #canViewJobOrders()} verdict without the request-scoped memo — the admin
   * short-circuit, the empty-membership rejection, and the active-hierarchy count. Split out so the
   * public method only owns the caching.
   *
   * @return {@code true} iff the caller is an admin or a member of at least one active
   *     Profit-hierarchy org unit.
   */
  private boolean resolveCanViewJobOrders() {
    if (authHelper.isAdmin()) {
      return true;
    }
    Set<UUID> memberOrgUnitIds = currentMemberOrgUnitIds();
    if (memberOrgUnitIds.isEmpty()) {
      return false;
    }
    return orgUnitRepository.countInActiveProfitHierarchyByIdIn(memberOrgUnitIds) > 0;
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
    return !currentMemberOrgUnitIds().isEmpty()
        || !currentGrantedOrgUnitIdsInActiveContext(Roles.LOGISTICIAN).isEmpty();
  }

  /**
   * Whether the caller may open the org-unit blueprint availability overview: admins and holders of
   * an oversight seat (see {@link #isOversightSeat(OrgUnitMembership)}) who hold {@code KRT
   * Member}.
   *
   * @return {@code true} iff the caller is an admin or holds at least one oversight seat
   */
  public boolean canAccessBlueprintOverview() {
    if (authHelper.isAdmin()) {
      return true;
    }
    return authHelper.holdsKrtMember()
        && currentCallerMemberships().stream().anyMatch(RequestScopeResolver::isOversightSeat);
  }

  /**
   * Cascading read scope for the blueprint availability overview and the org-unit bank balances
   * (REQ-BANK-021): the org units the caller oversees, including subordinate units (REQ-ORG-015).
   *
   * <ul>
   *   <li>admin: {@link #currentScopePredicate()};
   *   <li>squadron rank / SK lead: their own unit;
   *   <li>Bereichsleitung / OL: their seat and everything below it, via {@link
   *       OrgUnitCascadeService#cascadedOfficerReach(java.util.Collection)}.
   * </ul>
   *
   * <p>A pin narrows the scope to exactly the pinned unit when the caller oversees it, and to
   * nothing when they do not (REQ-ORG-032). An empty set means no rows.
   *
   * @return a never-null cascading scope vector of the org units whose data the caller may oversee.
   */
  @NotNull
  public ScopePredicate currentOversightScope() {
    if (authHelper.isAdmin()) {
      return currentScopePredicate();
    }
    Set<UUID> oversightOrgUnitIds = currentOversightOrgUnitIds();
    return currentPin()
        .map(pin ->
            oversightOrgUnitIds.contains(pin)
                ? new ScopePredicate(false, pin, Set.of())
                : new ScopePredicate(false, null, Set.of()))
        .orElseGet(() -> new ScopePredicate(false, null, oversightOrgUnitIds));
  }

  /**
   * The org units a non-admin caller oversees: every unit with an SK lead or functional-rank seat,
   * and the cascaded leadership reach.
   *
   * @return the overseen org-unit ids, never {@code null}.
   */
  @NotNull
  private Set<UUID> currentOversightOrgUnitIds() {
    return authHelper.holdsKrtMember()
        ? orgUnitCascadeService.officerReach(currentCallerMemberships())
        : Set.of();
  }

  /**
   * Returns the org units in which the caller holds officer reach through a leadership seat
   * (REQ-SEC-015, ADR-0300): the own unit of an SK lead or squadron rank, plus the cascaded reach
   * of Bereich and OL seats. Empty unless the caller holds {@code KRT Member}; an admin's pass is
   * not part of the set. Memoised per request.
   *
   * @return the caller's officer org-unit ids; never {@code null}, possibly empty
   */
  @NotNull
  public Set<UUID> currentOfficerOrgUnitIds() {
    return RequestMemo.get(
        request,
        CACHE_KEY_OFFICER_ORG_UNIT_IDS,
        () ->
            authHelper.holdsKrtMember()
                ? Collections.unmodifiableSet(
                    orgUnitCascadeService.officerReach(currentCallerMemberships()))
                : Set.of());
  }

  /**
   * Whether the org unit lies in the active context (REQ-ORG-032): always without an honoured pin,
   * otherwise only the pinned unit itself, a Bereich or Organisationsleitung pin included. Narrows
   * a write gate that decides from ranks alone, for admins as for everyone else; it never grants.
   *
   * @param orgUnitId the org unit a write targets; never {@code null}
   * @return {@code true} iff no unit is pinned or the pinned unit is {@code orgUnitId}
   */
  public boolean isInActiveContext(@NotNull UUID orgUnitId) {
    return currentPin().map(orgUnitId::equals).orElse(true);
  }

  /**
   * Narrows a set of reached org units to the active context (REQ-ORG-032): unchanged when nothing
   * is pinned, exactly the pinned unit when it is among them, and empty when it is not. A Bereich
   * or Organisationsleitung pin is that unit alone, not the units below it.
   *
   * @param reach the org units the caller reaches; never {@code null}
   * @return the reached units the pin leaves; never {@code null}
   */
  @NotNull
  public Set<UUID> narrowToPin(@NotNull Set<UUID> reach) {
    return currentPin()
        .<Set<UUID>>map(pin -> reach.contains(pin) ? Set.of(pin) : Set.of())
        .orElse(reach);
  }

  /**
   * Whether the caller holds officer reach in the active context: in the pinned unit when one is
   * pinned, in at least one unit otherwise. Admins always pass. Drives visibility only, never a
   * write (REQ-SEC-047).
   *
   * @return {@code true} iff the caller is an admin or holds reach in the active context
   */
  public boolean canActAsOfficerInActiveContext() {
    return authHelper.isAdmin() || !narrowToPin(currentOfficerOrgUnitIds()).isEmpty();
  }

  /**
   * Returns the org units in which the caller holds the contextual role {@code roleName}, own or
   * seat-derived, including the units below a Bereich or Organisationsleitung grant (REQ-SEC-005):
   * the contextual authorities, never the flat role. An admin's pass is not part of the set.
   *
   * <p>The one resolution behind every per-unit Logistician and Mission Manager gate and list
   * scope; the pin is not applied (see {@link #currentGrantedOrgUnitIdsInActiveContext(String)}).
   *
   * @param roleName the contextual role, e.g. {@link Roles#LOGISTICIAN}; never {@code null}
   * @return the org-unit ids of the caller's grants; never {@code null}, possibly empty
   */
  @NotNull
  public Set<UUID> currentGrantedOrgUnitIds(@NotNull String roleName) {
    Set<UUID> direct = new LinkedHashSet<>();
    authHelper
        .currentAuthentication()
        .filter(Authentication::isAuthenticated)
        .ifPresent(a -> {
          for (GrantedAuthority authority : a.getAuthorities()) {
            if (authority instanceof OrgUnitContextualAuthority ctx
                && roleName.equals(ctx.roleName())) {
              direct.add(ctx.orgUnitId());
            }
          }
        });
    Set<UUID> granted = new LinkedHashSet<>(direct);
    granted.addAll(orgUnitCascadeService.unitsBelow(direct));
    return Collections.unmodifiableSet(granted);
  }

  /**
   * {@link #currentGrantedOrgUnitIds(String)} inside the active context ({@link
   * #narrowToPin(Set)}): the pinned unit when it lies in the granted set, nothing when it does not,
   * the whole set when no unit is pinned.
   *
   * @param roleName the contextual role; never {@code null}
   * @return the org-unit ids the caller's grants cover in the active context; never {@code null}
   */
  @NotNull
  public Set<UUID> currentGrantedOrgUnitIdsInActiveContext(@NotNull String roleName) {
    return narrowToPin(currentGrantedOrgUnitIds(roleName));
  }

  /**
   * Whether the caller holds a Mission Manager grant in an Organisationsleitung or Bereich unit
   * inside the active context, which is what manages a mission without an owning unit (REQ-SEC-065,
   * REQ-ORG-032). An admin's pass is not part of this check.
   *
   * @return {@code true} iff a leadership unit carries one of the caller's Mission Manager grants
   *     in the active context
   */
  public boolean holdsLeadershipMissionManagerGrantInActiveContext() {
    Set<UUID> grants = currentGrantedOrgUnitIdsInActiveContext(Roles.MISSION_MANAGER);
    return !grants.isEmpty() && !orgUnitRepository.findLeadershipOrgUnitIdsAmong(grants).isEmpty();
  }

  /**
   * Whether the caller holds a Mission Manager grant in the active context: in the pinned unit when
   * one is pinned, in at least one unit otherwise. Admins always pass. Drives visibility only,
   * never a write.
   *
   * @return {@code true} iff the caller is an admin or holds a grant in the active context
   */
  public boolean canActAsMissionManagerInActiveContext() {
    return authHelper.isAdmin()
        || !currentGrantedOrgUnitIdsInActiveContext(Roles.MISSION_MANAGER).isEmpty();
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
   * Whether the caller holds an {@code OL_MEMBER} seat that confers anything, which it does only
   * while the caller holds {@code KRT Member} (REQ-SEC-015). Pure membership check without an admin
   * short-circuit (REQ-BANK-037).
   *
   * @return {@code true} iff the caller holds {@code KRT Member} and an {@code OL_MEMBER} seat.
   */
  public boolean currentUserIsOlMember() {
    return authHelper.holdsKrtMember()
        && currentCallerMemberships().stream()
            .anyMatch(m -> m.getRole() == MembershipRole.OL_MEMBER);
  }

  /**
   * Whether the caller holds exactly the given {@link MembershipRole} on the given org unit as a
   * seat that confers anything, which it does only while the caller holds {@code KRT Member}
   * (REQ-SEC-015). Pure membership check without an admin short-circuit.
   *
   * @param orgUnitId the org unit to check; never {@code null}
   * @param role the membership role to match; never {@code null}
   * @return {@code true} iff the caller holds {@code KRT Member} and a membership on that unit
   *     carrying that role
   */
  public boolean currentUserHoldsRoleOnOrgUnit(
      @NotNull UUID orgUnitId, @NotNull MembershipRole role) {
    return authHelper.holdsKrtMember()
        && currentCallerMemberships().stream()
            .anyMatch(m -> m.getId().getOrgUnitId().equals(orgUnitId) && m.getRole() == role);
  }

  /**
   * Loads the one org unit of any kind the effective context covers ({@link
   * #currentActiveContext()}), unproxied; empty when it covers several units or none. Memoised per
   * request.
   *
   * @return the current context's org unit, or empty
   */
  @NotNull
  public Optional<OrgUnit> currentOrgUnit() {
    return RequestMemo.get(request, CACHE_KEY_CURRENT_ORG_UNIT, this::loadCurrentOrgUnit);
  }

  /**
   * The uncached load behind {@link #currentOrgUnit()}.
   *
   * @return the current context's org unit, or empty
   */
  @NotNull
  private Optional<OrgUnit> loadCurrentOrgUnit() {
    UUID orgUnitId = currentActiveContext().orgUnitId();
    return orgUnitId == null
        ? Optional.empty()
        : orgUnitRepository.findById(orgUnitId).map(ou -> Hibernate.unproxy(ou, OrgUnit.class));
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
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
