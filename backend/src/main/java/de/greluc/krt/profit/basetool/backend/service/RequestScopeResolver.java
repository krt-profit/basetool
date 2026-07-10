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
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.support.StaffelMembershipResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Request-scoped context core of {@link OwnerScopeService} (L3 split, #922): resolves the caller's
 * effective org-unit context — the active-context header / persistent Staffel, the {@link
 * ScopePredicate} scope vectors, the caller's membership rows and the cascading/own-level oversight
 * reach — and owns the per-request memoisation those resolutions share. The {@code can*}
 * authorization gates ({@link AccessGateService}) and the create-time owner-stamping ({@link
 * OrgUnitStampingService}) are layered on top of this bean; {@link OwnerScopeService} is the
 * delegating facade that composes all three behind the {@code ownerScopeService} bean name the
 * {@code @PreAuthorize} SpEL strings resolve against.
 *
 * <p>Two org-unit contexts feed into the resolution:
 *
 * <ul>
 *   <li>For a non-admin user, the persistent {@code app_user.squadron_id} they were assigned to
 *       (now sourced from {@code org_unit_membership}).
 *   <li>For an admin, the {@link #ACTIVE_ORG_UNIT_HEADER} request header relayed by the frontend's
 *       WebClient. {@code null} / missing means "all squadrons" — admins are not constrained when
 *       no active selection exists.
 * </ul>
 *
 * <p>The class-level {@code @Transactional(readOnly = true)} mirrors the {@link OwnerScopeService}
 * setting — every repository call here is read-only, and lets Spring skip the dirty-check flush and
 * route to the read replica if one is configured.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestScopeResolver {

  /**
   * Name of the HTTP request header through which the frontend relays the caller's active OrgUnit
   * selection. A {@code null}/missing value means "no active selection" (admin sees all OrgUnits);
   * a non-blank UUID restricts the request to that OrgUnit's data for its duration. Source of truth
   * lives on the frontend (Redis-backed Spring Session via {@code MeFrontendController}); the
   * backend treats the header as untrusted-but-bounded input — an admin pin is honoured directly, a
   * non-admin pin only when it matches one of the caller's memberships (re-validated in {@link
   * #currentScopePredicate()}). Read by {@link #readActiveSquadronFromHeader()}. Re-exported by
   * {@link OwnerScopeService#ACTIVE_ORG_UNIT_HEADER} so the historical public constant keeps
   * resolving.
   */
  public static final String ACTIVE_ORG_UNIT_HEADER = "X-Active-Org-Unit-Id";

  /**
   * Request-attribute key under which the result of {@link #readPersistentSquadronFromUser()} is
   * cached for the duration of the current HTTP request. Stored as {@code Optional<UUID>} (never
   * {@code null}) so the cache can distinguish "resolved to empty" from "not yet resolved".
   */
  private static final String CACHE_KEY_PERSISTENT_USER_SQUADRON_ID =
      RequestScopeResolver.class.getName() + ".persistentUserSquadronId";

  /**
   * Request-attribute key under which the result of {@link #currentSquadron()} is cached for the
   * duration of the current HTTP request. Same distinction-via-Optional contract as {@link
   * #CACHE_KEY_PERSISTENT_USER_SQUADRON_ID}.
   */
  private static final String CACHE_KEY_CURRENT_SQUADRON =
      RequestScopeResolver.class.getName() + ".currentSquadron";

  /**
   * Request-attribute key under which {@link #currentMemberOrgUnitIds()} caches the caller's
   * membership org-unit ids for the duration of the current HTTP request. Stored as the resolved
   * {@code Set<UUID>} (possibly empty) directly — a present attribute of type {@link java.util.Set}
   * means "already resolved this request", so the membership read happens at most once even though
   * several gates consult it.
   */
  private static final String CACHE_KEY_MEMBER_ORG_UNIT_IDS =
      RequestScopeResolver.class.getName() + ".memberOrgUnitIds";

  /**
   * Request-attribute key under which {@link #currentCallerMemberships()} caches the current
   * caller's raw {@code org_unit_membership} rows for the duration of the request. The
   * blueprint-overview gate plus the cascading and own-level oversight scopes each read the same
   * membership list; memoising it collapses those repeated {@code findAllByIdUserId} reads (e.g.
   * the gate + body double-read on the availability overview) to a single query per request.
   */
  private static final String CACHE_KEY_CALLER_MEMBERSHIPS =
      RequestScopeResolver.class.getName() + ".callerMemberships";

  /**
   * Request-attribute key under which {@link #canViewJobOrders()} caches its boolean verdict for
   * the duration of the current HTTP request. The profit-eligibility gate is request-constant (it
   * derives only from the caller's memberships), yet on the order <em>lookup</em> path it is
   * consulted once per row via the {@code canSeeJobOrder} filter; memoising the verdict collapses
   * the otherwise-repeated {@code countProfitEligibleByIdIn} aggregate to a single query per
   * request. A present attribute of type {@link Boolean} means "already resolved this request".
   */
  private static final String CACHE_KEY_CAN_VIEW_JOB_ORDERS =
      RequestScopeResolver.class.getName() + ".canViewJobOrders";

  private final AuthHelperService authHelper;
  private final SquadronRepository squadronRepository;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final OrgUnitCascadeService orgUnitCascadeService;
  private final StaffelMembershipResolver staffelMembershipResolver;
  private final HttpServletRequest request;

  /**
   * Returns the org-unit context that filters the current request. For admins this reads the {@code
   * X-Active-Org-Unit-Id} request header (the frontend's switcher pushed there via the relay
   * filter); for everyone else this loads the user's persistent home squadron. Empty result means
   * "no filter" for admins ("all squadrons") and "no access" for non-admins (typically
   * unauthenticated / anonymous).
   *
   * <p>The non-admin branch's {@code org_unit_membership} lookup is memoised on the {@link
   * HttpServletRequest} via {@link #readPersistentSquadronFromUser()}, so repeated calls within the
   * same request collapse to a single DB hit. The admin branch reads the request header (in-memory)
   * and is not cached separately — it is already constant-time.
   *
   * @return the active org-unit id, or empty when no filter applies.
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
   * R6.c / SPEZIALKOMMANDO_PLAN.md §3.5 + §5.4: returns the full effective scope vector for the
   * current request, encoded as a {@link ScopePredicate}. Repository queries combine the three
   * fields ({@code adminAllScope}, {@code activeOrgUnitId}, {@code memberOrgUnitIds}) into a single
   * JPQL clause that handles every caller class — admin all-scope, admin/non-admin pinned to a
   * specific OrgUnit, non-admin with multi-membership union, and anonymous — with the same
   * predicate shape. Before R6.c the staffel-scoped queries took a single nullable {@code
   * scopeSquadronId} that collapsed admin-all and non-admin-with-multi-membership into the same
   * code path, silently hiding SK data from multi-membership users.
   *
   * <p>Resolution flow:
   *
   * <ul>
   *   <li>Admin without active header → {@code adminAllScope=true}, all other fields empty.
   *   <li>Admin with active header → {@code activeOrgUnitId=header value}, {@code adminAllScope=
   *       false}, memberships empty (admins do not constrain by their own memberships even when
   *       they happen to have some).
   *   <li>Non-admin (with or without future R5.e pinning) → {@code memberOrgUnitIds = union of
   *       User.squadron + SK memberships}, {@code adminAllScope=false}, {@code activeOrgUnitId=
   *       null}. The R5.e pinning will switch this branch to populate {@code activeOrgUnitId} from
   *       the same X-Active-Org-Unit-Id header that admins use today.
   *   <li>Anonymous → all empty / false / null. The repository predicate falls through to "no rows
   *       except cross-staffel public escape".
   * </ul>
   *
   * <p>The membership union read is hybrid pre-D3: {@link
   * de.greluc.krt.profit.basetool.backend.model.User#getSquadron()} for the Staffel link (still
   * authoritative on {@code app_user.squadron_id}) plus {@link
   * OrgUnitMembershipRepository#findAllByIdUserIdAndKind} for SK rows. Once D3 drops {@code
   * app_user.squadron_id} and migrates the legacy Staffel membership onto {@code
   * org_unit_membership}, this method switches to a single {@code findAllByIdUserId} read.
   *
   * @return a never-null scope vector describing what the current request should see.
   */
  @NotNull
  public ScopePredicate currentScopePredicate() {
    if (authHelper.isAdmin()) {
      Optional<UUID> active = readActiveSquadronFromHeader();
      return active
          .map(id -> new ScopePredicate(false, id, java.util.Set.of()))
          .orElseGet(() -> new ScopePredicate(true, null, java.util.Set.of()));
    }
    // R5.e: non-admin path. Read the same active-OrgUnit header the admin switcher uses — once
    // the frontend's R5.e switcher widening lets non-admins pick from their memberships, the
    // header carries that selection. The pin is only honoured when it points to a unit the caller
    // can actually reach — their direct memberships unioned with the epic #692 / REQ-ORG-015
    // cascade, so a Bereichsleitung/OL may pin to a descendant unit but never to a foreign one.
    // This is the defence against a spoofed header from a curl call; a pin outside that reach
    // silently collapses to the reach-union read so the user never sees data they did not opt into.
    java.util.Set<UUID> memberOrgUnitIds = currentMemberOrgUnitIds();
    Optional<UUID> pinned = readActiveSquadronFromHeader();
    if (pinned.isPresent() && memberOrgUnitIds.contains(pinned.get())) {
      return new ScopePredicate(false, pinned.get(), java.util.Set.of());
    }
    return new ScopePredicate(false, null, memberOrgUnitIds);
  }

  /**
   * Effective scope for the hangar <b>unit overview</b> ({@code /hangar/squadron}, REQ-HANGAR-003).
   * Identical to {@link #currentScopePredicate()} for every caller except one owner-approved
   * widening: a non-pinned <b>OL member</b> sees <em>every</em> ship — including the ownerless
   * personal ships ({@code owningOrgUnit == null}) of members who belong to no org unit at all — so
   * the OL's cross-org view of the fleet is complete.
   *
   * <p>This is a deliberate, narrowly-scoped exception to the REQ-ORG-015 hard invariant
   * ("OL/Bereich leadership never inherits the admin carve-outs, including ownerless-row access"):
   * it grants the {@code adminAllScope=true} read <em>only</em> for this one aggregation surface
   * and confers no {@code isAdmin()} rights anywhere else — every other {@code can*} gate and
   * scoped list still routes OL through the concrete-membership-union {@link
   * #currentScopePredicate()}. The exception is recorded in ADR-0048 and amends REQ-ORG-015 in
   * {@code org-unit-tenancy.md}.
   *
   * <p>The widening applies only when <b>no single unit is pinned</b> (owner decision: a pin still
   * narrows the unit overview to the pinned unit, like every other scoped surface) and only to an
   * OL member — a plain or BL member keeps their exact membership/cascade reach (their own
   * Staffeln/SKs, and a BL their Bereich's Staffeln/SKs), and an admin keeps the unchanged
   * admin-all / admin-pin behaviour.
   *
   * @return the unit-overview scope vector: {@code adminAllScope} for a non-pinned OL member,
   *     otherwise exactly {@link #currentScopePredicate()}.
   */
  @NotNull
  public ScopePredicate currentUnitOverviewScope() {
    ScopePredicate base = currentScopePredicate();
    // Only upgrade a non-admin OL member who has not pinned a single unit; everyone else (admins,
    // plain/BL members, and any pinned caller) keeps the base scope unchanged.
    if (!base.adminAllScope()
        && base.activeOrgUnitId() == null
        && !authHelper.isAdmin()
        && currentUserIsOlMember()) {
      return new ScopePredicate(true, null, java.util.Set.of());
    }
    return base;
  }

  /**
   * Resolves the SQUADRON-scope <em>set</em> for the admin user-list / search / typeahead /
   * promotion-Bewertungsmatrix queries. REQ-ORG-017 relaxed a member to up to two Staffeln, so the
   * unpinned non-admin scope is the <b>union</b> of the caller's own Staffeln rather than a single
   * one. A faithful generalisation of {@link #currentSquadronId()} (the pre-REQ-ORG-017 single
   * value) that only widens the two-Staffel case and otherwise preserves the legacy behaviour
   * exactly:
   *
   * <ul>
   *   <li>admin without a pin → {@code null} (no filter — the cross-staffel list);
   *   <li>admin/non-admin with an active pin → the singleton pinned id;
   *   <li>non-admin without a pin and at least one Staffel → the set of the caller's own Staffel
   *       ids (one element for a single-Staffel member — byte-identical to before; two for a
   *       dual-Staffel member);
   *   <li>non-admin without a pin and no Staffel → {@code null}, preserving the legacy "unfiltered"
   *       behaviour (so Bereich/OL leadership and guests keep seeing the full picker list).
   * </ul>
   *
   * <p>The returned set is never empty (a caller with no Staffel collapses to {@code null}), so the
   * repository {@code IN :scopeSquadronIds} clause never degenerates to an {@code IN ()}.
   *
   * @return the squadron id set the user-list queries filter on, or {@code null} for the unfiltered
   *     admin/leadership all-scope.
   */
  @org.jetbrains.annotations.Nullable
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
   * {@code true} iff the current non-admin caller holds more than one Staffel (REQ-ORG-017) and has
   * NOT pinned one of them via the active-context switcher — i.e. a single-Staffel auto-stamp would
   * have to pick arbitrarily. Create flows that auto-stamp exactly one owning Staffel (e.g. the
   * promotion topic / rank-requirement create) consult this to honour the owner's "pin, else
   * choose" decision: when it returns {@code true} they reject the create with a clean 400 telling
   * the user to pin the target Staffel first, rather than silently defaulting to the name-sorted
   * primary. Admins are never ambiguous here (they always stamp via an explicit switcher focus or
   * are rejected in "all squadrons" mode), and a caller with zero or one Staffel is unambiguous.
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
   * Helper for {@link #currentScopePredicate()}: resolves every OrgUnit id the current non-admin
   * caller has effective reach over — their direct memberships <em>plus</em> the cascading
   * leadership expansion (epic #692, REQ-ORG-015). Used by the union-of-memberships branch of the
   * scope predicate. Returns the empty set for anonymous callers and (technically) for admins,
   * although the latter never reaches this method through {@link #currentScopePredicate()}.
   *
   * <p>Cascade (delegated to {@link
   * OrgUnitCascadeService#expandWithDescendants(java.util.Collection)} so the converter and this
   * resolver share one definition): a Bereichsleitung membership ({@code is_bereichsleiter}/{@code
   * is_bereichskoordinator}/{@code is_bereichsoperator}) widens reach to the Bereich's Staffeln +
   * SKs; an OL membership ({@code is_ol_member}) widens reach to <em>every</em> org unit; a plain
   * Staffel/SK membership (or a flag-less Bereich/OL seat) widens nothing. The expansion is always
   * a concrete id set — never {@code adminAllScope}, never an {@code isAdmin()} grant — so
   * OL/Bereich leadership stays officer-equivalent and never inherits the admin carve-outs (the
   * HARD INVARIANT of REQ-ORG-015). For a caller with no leadership flag the result is exactly
   * their direct membership ids, i.e. byte-for-byte the pre-#692 behaviour.
   *
   * <p>The result is memoised on the {@link HttpServletRequest} for the duration of the request.
   * Since the Job-Order profit gate ({@link #canViewJobOrders()}) landed, the resolver is consulted
   * more than once per request — the list path reads it via both {@code canViewJobOrders()} and
   * {@link #currentScopePredicate()}, and the detail/edit paths read it via {@code
   * canViewJobOrders()} — so the single cached {@code Set<UUID>} collapses what would otherwise be
   * repeated {@code findAllByIdUserId} + hierarchy reads into one.
   *
   * <p>Post-D3: every membership row (Staffel + SK + Bereich + OL) flows through {@code
   * OrgUnitMembershipRepository.findAllByIdUserId} — the legacy {@code User.squadron} column was
   * dropped in R9 Step 5 / V101.
   *
   * @return the union of OrgUnit ids the caller is a member of or has cascading leadership reach
   *     over, never {@code null}.
   */
  @NotNull
  public java.util.Set<UUID> currentMemberOrgUnitIds() {
    Object cached = request.getAttribute(CACHE_KEY_MEMBER_ORG_UNIT_IDS);
    if (cached instanceof java.util.Set<?> set) {
      @SuppressWarnings("unchecked")
      java.util.Set<UUID> typed = (java.util.Set<UUID>) set;
      return typed;
    }
    // Reuse the request-memoised membership rows (REQ-DATA-003): the blueprint-overview gate
    // and the oversight scopes now share one membership read. currentCallerMemberships() is
    // empty for anonymous callers and expandWithDescendants of an empty input is the empty
    // set, so both the anonymous and member paths behave exactly as before.
    java.util.Set<UUID> ids =
        orgUnitCascadeService.expandWithDescendants(currentCallerMemberships());
    request.setAttribute(CACHE_KEY_MEMBER_ORG_UNIT_IDS, ids);
    return ids;
  }

  /**
   * The current caller's raw {@code org_unit_membership} rows, memoised on the request. Returns an
   * empty list for an anonymous caller. Shared by the blueprint-overview gate and the cascading /
   * own-level oversight scopes so they read the membership table once per request instead of once
   * each (REQ-DATA-003).
   *
   * @return the caller's membership rows, never {@code null}.
   */
  @NotNull
  private List<OrgUnitMembership> currentCallerMemberships() {
    Object cached = request.getAttribute(CACHE_KEY_CALLER_MEMBERSHIPS);
    if (cached instanceof List<?> list) {
      @SuppressWarnings("unchecked")
      List<OrgUnitMembership> typed = (List<OrgUnitMembership>) list;
      return typed;
    }
    List<OrgUnitMembership> memberships =
        authHelper
            .currentUserId()
            .map(orgUnitMembershipRepository::findAllByIdUserId)
            .orElseGet(List::of);
    request.setAttribute(CACHE_KEY_CALLER_MEMBERSHIPS, memberships);
    return memberships;
  }

  /**
   * The org-unit ids the current caller is a <em>direct</em> member of (the raw {@code
   * org_unit_membership} rows), <strong>without</strong> the leadership cascade that {@link
   * #currentMemberOrgUnitIds()} adds. This is the "own placed orders" key for the requester-side
   * job-order access (REQ-ORDERS-023): an order counts as the caller's own iff its requesting org
   * unit is one the caller directly belongs to — deliberately NOT the cascade, so an OL/Bereich
   * seat does not turn every unit's orders into "theirs". Empty for an anonymous caller.
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
   * {@code true} iff the current principal may enter the Job-Order area at all — i.e. see the order
   * list and order details. Only members of a <em>profit-eligible</em> org unit (Squadron or
   * Spezialkommando) participate in the order workflow: Kartell departments are split into Profit
   * and non-Profit, and only the Profit side processes orders. A non-Profit unit may still
   * <em>place</em> orders (as the requesting/Auftraggeber side) but its members must not see the
   * order queue — mirroring how anonymous guests can submit an order yet cannot track it.
   *
   * <ul>
   *   <li>Admin → always {@code true} (system-wide oversight, like every other {@code can*}
   *       short-circuit here).
   *   <li>Non-admin → {@code true} iff at least one of the caller's membership org units (any kind)
   *       is flagged {@code is_profit_eligible}.
   *   <li>Anonymous / member of only non-profit units → {@code false}.
   * </ul>
   *
   * <p>This is the viewer-side gate folded into {@link AccessGateService#canSeeJobOrder(UUID)} (so
   * order details + material claims respect it) and short-circuited by {@code
   * JobOrderService.getAllJobOrders} (so the list returns empty). It is independent of which
   * specific order is responsible to whom — a non-profit member sees nothing, including the
   * otherwise-public SK queue.
   *
   * @return {@code true} iff the caller may view job orders.
   */
  public boolean canViewJobOrders() {
    if (request.getAttribute(CACHE_KEY_CAN_VIEW_JOB_ORDERS) instanceof Boolean cached) {
      return cached;
    }
    boolean verdict = resolveCanViewJobOrders();
    request.setAttribute(CACHE_KEY_CAN_VIEW_JOB_ORDERS, verdict);
    return verdict;
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
   * {@code true} iff the current principal may view and limited-edit the job orders their own org
   * unit <em>placed</em> (the requesting/Auftraggeber side) — the capability that drives the "Meine
   * Auftr&auml;ge" menu entry and stops a non-profit requester from being redirected away from
   * their own orders (REQ-ORDERS-023). Independent of the profit-eligibility gate {@link
   * #canViewJobOrders()}: a member of a purely non-profit unit fails {@code canViewJobOrders} yet
   * may still track the orders that unit requested.
   *
   * <ul>
   *   <li>Admin &rarr; {@code true} (already covered by {@link #canViewJobOrders()}, kept true here
   *       for consistency).
   *   <li>Non-admin &rarr; {@code true} iff the caller is a member of at least one org unit (which
   *       could therefore be the requesting org unit of some order).
   *   <li>Anonymous / memberless &rarr; {@code false} — a guest may place an order but not track
   *       it.
   * </ul>
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
   * #364 blueprint-availability gate: {@code true} iff the current principal may open the org-unit
   * blueprint availability overview at all. The overview is an oversight feature restricted to
   * leadership:
   *
   * <ul>
   *   <li>admins — always (they see every org unit, or the one they pinned);
   *   <li>officers — for their own Staffel;
   *   <li>Spezialkommando leads — for the SK(s) they lead;
   *   <li>Bereichsleitung / OL members — for their Bereich's (or every) unit (epic #692 Phase 6).
   * </ul>
   *
   * <p>A plain member, or a contextual logistician who holds no oversight seat, is rejected (no
   * menu entry, empty / forbidden API). The leadership branch scans the caller's memberships for
   * any oversight seat — see {@link #isOversightSeat(OrgUnitMembership)}: an SK-lead ({@code
   * is_lead}, pinned to SK memberships by the V95 CHECK) or — since epic #692 Phase 6 — a
   * Bereichsleitung / OL membership.
   *
   * @return {@code true} iff the caller is an admin, an officer, or holds at least one oversight
   *     seat (SK-lead / Bereichsleitung / OL).
   */
  public boolean canAccessBlueprintOverview() {
    if (authHelper.isAdmin() || authHelper.hasReachableRole(Roles.authority(Roles.OFFICER))) {
      return true;
    }
    return currentCallerMemberships().stream().anyMatch(RequestScopeResolver::isOversightSeat);
  }

  /**
   * #364 effective scope for the blueprint-availability overview <em>and</em> the org-unit bank
   * balance-view (F1, REQ-BANK-021), encoded as a {@link ScopePredicate} so the aggregation reuses
   * the same three-field shape as the staffel-scoped list queries. Unlike {@link
   * #currentScopePredicate()} — which returns the union of <em>all</em> of a non-admin's
   * memberships — this restricts a non-admin to the org units they have oversight over, mirroring
   * {@link #canAccessBlueprintOverview()}. This is the <b>cascading</b> (view) scope — it drills
   * down into subordinate units:
   *
   * <ul>
   *   <li>admin → delegates to {@link #currentScopePredicate()} (all org units, or the pinned one);
   *   <li>officer → their own Staffel (via {@link #readPersistentSquadronFromUser()});
   *   <li>SK lead → every SK they lead;
   *   <li>Bereichsleitung → their Bereich <em>and</em> every Staffel/SK of it; OL member → every
   *       org unit — the cascading, officer-equivalent reach (epic #692 Phase 6, REQ-ORG-015) via
   *       {@link OrgUnitCascadeService#cascadedOfficerReach(java.util.Collection)}, which also
   *       contributes the Bereich/OL seat itself so the caller's own AREA/CARTEL account is in
   *       scope. Never an admin-all marker (the HARD INVARIANT);
   *   <li>an active pin is honoured only when it points at one of those oversight org units,
   *       otherwise it is ignored and the full oversight union applies.
   * </ul>
   *
   * <p>A caller with an empty oversight set (e.g. a plain member who reached the service despite
   * the gate) yields {@code memberOrgUnitIds = {}}, which the aggregation treats as "no rows".
   *
   * <p>This cascading scope is for <b>reads</b> (view). The own-level write scope a
   * Bereichsleitung/OL may raise a bank booking request against (F2, REQ-BANK-022, owner decision
   * Q4) is {@link #currentOwnLevelOversightScope()} — deliberately <em>not</em> cascaded, so a
   * subordinate account reached by drill-down is view-only.
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
      // REQ-ORG-017: an officer oversees ALL of their own Staffeln (up to two), not just the
      // name-sorted primary — add every SQUADRON membership rather than the single active one.
      for (OrgUnitMembership m : memberships) {
        if (m.getKind() == OrgUnitKind.SQUADRON) {
          oversightOrgUnitIds.add(m.getId().getOrgUnitId());
        }
      }
    }
    for (OrgUnitMembership m : memberships) {
      // SK leads oversee their own SK; from epic #800 (REQ-ROLE-002) the four squadron ranks
      // oversee their own squadron the same way (officer-equivalent, own-unit only, no cascade).
      if (m.getRole() == MembershipRole.SK_LEAD || m.getRole().isSquadronRank()) {
        oversightOrgUnitIds.add(m.getId().getOrgUnitId());
      }
    }
    // Epic #692 Phase 6 (REQ-ORG-015/-019): a Bereichsleitung member oversees their Bereich + its
    // Staffeln/SKs, an OL member every org unit — the cascading, officer-equivalent reach (never
    // admin). cascadedOfficerReach also contributes the Bereich/OL seat itself, so the caller's own
    // AREA/CARTEL account is in scope.
    oversightOrgUnitIds.addAll(orgUnitCascadeService.cascadedOfficerReach(memberships));
    Optional<UUID> pinned = readActiveSquadronFromHeader();
    if (pinned.isPresent() && oversightOrgUnitIds.contains(pinned.get())) {
      return new ScopePredicate(false, pinned.get(), Set.of());
    }
    return new ScopePredicate(false, null, oversightOrgUnitIds);
  }

  /**
   * Epic #692 Phase 6 (REQ-BANK-022, owner decision Q4): the caller's <b>own-level</b> oversight
   * seats, encoded as a {@link ScopePredicate}. This is the write-side companion of {@link
   * #currentOversightScope()} and is deliberately <em>not</em> cascaded — it names only the org
   * units the caller leads at their own level, never the descendants they may merely view:
   *
   * <ul>
   *   <li>admin → delegates to {@link #currentScopePredicate()} (all org units, or the pinned one);
   *   <li>officer → their own Staffel (the squadron {@code ORG_UNIT} account);
   *   <li>SK lead → every SK they lead (its {@code ORG_UNIT} account);
   *   <li>Bereichsleitung → their Bereich (its {@code AREA} account) — but <em>not</em> the child
   *       Staffel/SK accounts;
   *   <li>OL member → the Organisationsleitung (the {@code CARTEL} account) — but <em>not</em> the
   *       AREA/ORG_UNIT accounts below it.
   * </ul>
   *
   * <p>This backs the org-unit bank booking-request gate ({@code
   * OrgUnitBankAccessService.createBookingRequest}): a Bereichsleitung/OL may raise a
   * confirm-before-post request only against their own-level account, while subordinate accounts
   * reached through the cascading view ({@link #currentOversightScope()}) stay view-only. The
   * officer flow from epic #666 is unchanged — an officer's own-level scope is exactly their
   * Staffel, as before. An active pin is honoured only when it points at one of the caller's
   * own-level seats.
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
      // REQ-ORG-017: an officer's own-level scope is ALL of their Staffeln (up to two), not just
      // the
      // name-sorted primary.
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
   * {@code true} iff the membership is an <em>oversight seat</em> — one that confers
   * officer-equivalent oversight over its own org unit (and, for Bereich/OL, cascading reach below
   * it). These are every membership carrying a functional rank ({@link
   * MembershipRole#confersOwnLevelOversight()}, i.e. {@code role != MEMBER}): the SK-lead seat, the
   * Bereichsleitung / OL seats (epic #692 Phase 6, REQ-ORG-015) and, from epic #800 (REQ-ROLE-002),
   * the four squadron ranks. A rank-less ({@code MEMBER}) Staffel/SK/Bereich/OL seat is not an
   * oversight seat. Shared by {@link #canAccessBlueprintOverview()} and {@link
   * #currentOwnLevelOversightScope()} so the gate and the own-level scope agree on what "oversight"
   * means.
   *
   * @param m the membership row to classify; never {@code null}.
   * @return {@code true} iff the membership grants own-level oversight over its org unit.
   */
  private static boolean isOversightSeat(@NotNull OrgUnitMembership m) {
    return m.getRole().confersOwnLevelOversight();
  }

  /**
   * {@code true} iff the caller holds <b>Bereich- or OL-level oversight</b> — the seniority that,
   * on the org-unit bank page, additionally reveals the cartel-wide special accounts
   * (Sonderkonten), which belong to no single org unit and are therefore not reachable through the
   * org-unit cascade (REQ-BANK-028). Unlike {@link #isOversightSeat(OrgUnitMembership)} this
   * deliberately <em>excludes</em> the SK-lead seat and the officer role: an officer or SK lead
   * oversees only their own unit's account, so they do not get the org-wide special-account view.
   * The seats that qualify are the Bereichsleitung flags ({@code is_bereichsleiter}/{@code
   * is_bereichskoordinator}/{@code is_bereichsoperator}) and the OL flag ({@code is_ol_member}) —
   * now read through {@link MembershipRole#isAreaOrOl()}; a rank-less (chart-only) Bereich/OL
   * membership does not qualify. Admins always qualify — they see every account anyway.
   *
   * <p>This is consulted only by the org-unit-aware bank seam ({@link OrgUnitBankAccessService});
   * it adds no org-unit logic to the bank itself, which stays org-unit-blind (REQ-BANK-008,
   * ADR-0011).
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
   * {@code true} iff the membership is a Bereich- or OL-level oversight seat — the subset of {@link
   * #isOversightSeat(OrgUnitMembership)} that excludes the SK-lead and squadron-rank seats, read
   * through {@link MembershipRole#isAreaOrOl()} ({@code BEREICHSLEITER} / {@code
   * BEREICHSKOORDINATOR} / {@code BEREICHSOPERATOR} / {@code OL_MEMBER}). Backs {@link
   * #currentUserHasAreaOrOlOversight()}.
   *
   * @param m the membership row to classify; never {@code null}.
   * @return {@code true} iff the membership is a Bereichsleitung or OL seat.
   */
  private static boolean isAreaOrOlSeat(@NotNull OrgUnitMembership m) {
    return m.getRole().isAreaOrOl();
  }

  /**
   * {@code true} iff the caller holds an {@code OL_MEMBER} seat — a member of the
   * Organisationsleitung. Pure membership check (no admin short-circuit), so the org-unit bank seam
   * can use it to resolve the collegial holder of the {@code CARTEL} account and the
   * OL-can-configure-SPECIAL-visibility rule (REQ-BANK-037), where "OL" means the OL body, not the
   * admin carve-out.
   *
   * @return {@code true} iff the caller has at least one {@code OL_MEMBER} membership.
   */
  public boolean currentUserIsOlMember() {
    return currentCallerMemberships().stream()
        .anyMatch(m -> m.getRole() == MembershipRole.OL_MEMBER);
  }

  /**
   * {@code true} iff the caller holds a {@code BEREICHSLEITER} seat on any Bereich. Pure membership
   * check (no admin short-circuit), used by the org-unit bank seam for the SPECIAL-account
   * auto-view rule (every Bereichsleiter sees Sonderkonten, REQ-BANK-037) — deliberately narrower
   * than {@link #currentUserHasAreaOrOlOversight()}, which also includes
   * Bereichskoordinatoren/-operatoren and OL members.
   *
   * @return {@code true} iff the caller has at least one {@code BEREICHSLEITER} membership.
   */
  public boolean currentUserIsBereichsleiter() {
    return currentCallerMemberships().stream()
        .anyMatch(m -> m.getRole() == MembershipRole.BEREICHSLEITER);
  }

  /**
   * {@code true} iff the caller holds exactly the given {@link MembershipRole} on the given org
   * unit. Pure membership check used by the org-unit bank seam to resolve a derived responsible
   * holder (e.g. the {@code STAFFELLEITER} of a Staffel, the {@code BEREICHSLEITER} of a PROFIT
   * Bereich) and to evaluate {@code MEMBERSHIP_ROLE} view grants.
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
   * {@code true} iff the caller is a member of the given org unit at all (any role, including a
   * rank-less {@code MEMBER} seat). Pure membership check used by the org-unit bank seam to
   * evaluate the {@code ALL_MEMBERS} view grant on an org-unit account.
   *
   * @param orgUnitId the org unit to check; never {@code null}
   * @return {@code true} iff the caller has any membership on that unit
   */
  public boolean currentUserIsMemberOfOrgUnit(@NotNull UUID orgUnitId) {
    return currentCallerMemberships().stream()
        .anyMatch(m -> m.getId().getOrgUnitId().equals(orgUnitId));
  }

  /**
   * {@code true} iff the caller is a member anywhere in the whole area cascade of the given Bereich
   * — a direct member of the Bereich itself (the Bereichsleitung) <em>or</em> a member of any of
   * its child Staffeln / Spezialkommandos. Pure membership check used by the org-unit bank seam to
   * evaluate the {@code AREA_MEMBERS} view grant / approval-limit tier ("Mitglieder des Bereichs",
   * REQ-BANK-048) on a Bereichskonto. Since the org hierarchy is exactly three levels (OL &gt;
   * Bereich &gt; Staffel/SK), the Bereich's direct children are its whole subtree.
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
   * Convenience entry point for the aggregate-service create paths: returns the {@link Squadron}
   * entity that matches {@link #currentSquadronId()}, loaded from the DB. Empty when the caller has
   * no effective squadron (admin in "all squadrons" mode, guest, or unauthenticated). Services use
   * this to stamp {@code owningSquadron} on newly-created aggregates that have no owner field of
   * their own (e.g. {@code Operation}) — aggregates that DO carry an owner ({@code Ship}, {@code
   * Mission}, …) prefer to derive the squadron from the owner so a future user-squadron move does
   * not silently retag history.
   *
   * <p>Result is memoised per {@link HttpServletRequest} so repeated calls in one request collapse
   * to a single {@code squadronRepository.findById} round-trip.
   *
   * @return the {@link Squadron} for the current effective context, or empty when none applies.
   */
  @NotNull
  public Optional<Squadron> currentSquadron() {
    Optional<Optional<Squadron>> cached = readCachedOptional(CACHE_KEY_CURRENT_SQUADRON);
    if (cached.isPresent()) {
      return cached.get();
    }
    Optional<Squadron> resolved = currentSquadronId().flatMap(squadronRepository::findById);
    request.setAttribute(CACHE_KEY_CURRENT_SQUADRON, resolved);
    return resolved;
  }

  /**
   * Plan-aligned org-unit-typed accessor: returns the {@link OrgUnit} matching {@link
   * #currentOrgUnitId()}. Today the active context is always a {@link Squadron} (the admin switcher
   * accepts only Staffel ids); R2.d will widen the switcher to accept Spezialkommando ids too. The
   * method's return type is already widened so the eventual rollout is a one-line repository swap
   * rather than another signature change.
   *
   * @return the current effective {@link OrgUnit}, or empty when none applies.
   */
  @NotNull
  public Optional<OrgUnit> currentOrgUnit() {
    // Cast through Optional<Squadron> for now — Squadron is the only OrgUnit subtype that can
    // currently be the active context. R2.d will replace this with a polymorphic OrgUnitRepository
    // lookup once Squadron + SpecialCommand are both selectable in the admin switcher.
    return currentSquadron().map(s -> (OrgUnit) s);
  }

  /**
   * Reports whether the per-squadron promotion-system feature flag is on for the caller's scope.
   *
   * <ul>
   *   <li>Admin without an active pin (all-scopes mode) — {@code true}; admins keep access so they
   *       can re-enable a squadron that locked itself out without losing the menu entry.
   *   <li>Admin pinned to a squadron — uses the pinned squadron's flag so the pinned view stays
   *       consistent with what a member of that squadron would see. An admin who wants to re-enable
   *       a locked-out squadron either clears the pin first (all-scopes mode) or navigates directly
   *       to {@code /admin/settings} (not gated by this check).
   *   <li>Non-admin with an effective squadron — returns the flag stored on that squadron's row.
   *   <li>Caller without an effective squadron (anonymous / member without squadron) — {@code
   *       true}, since the squadron-scope filter already returns empty lists for them.
   * </ul>
   *
   * <p>The pin-awareness comes from {@link #currentSquadron()}, which already resolves an admin's
   * pin from the request header and a non-admin's home squadron from the membership row. The
   * earlier blanket admin bypass was dropped because it broke the pinned-view UX — see CLAUDE.md
   * "Multi-squadron tenancy" for the updated semantics.
   *
   * @return {@code true} when the promotion menu may be exposed for the caller.
   */
  public boolean isPromotionFeatureEnabledForCurrentScope() {
    return currentSquadron().map(Squadron::isPromotionEnabled).orElse(true);
  }

  /**
   * {@code true} iff the current caller may read <em>any</em> promotion data. Promotion is
   * per-squadron, so a caller needs either the elevated all-squadrons view (admin) or an effective
   * home squadron to see a system at all. A non-admin without any squadron membership has no
   * promotion system of their own; every promotion list / eligibility read must short-circuit to
   * empty for them rather than fall through to the {@code null}-scope cross-squadron union that
   * admins rely on. Detail reads are already covered by {@link
   * AccessGateService#canSeeSquadron(UUID)} (which returns {@code false} for a squadron-less
   * non-admin), so this guard is only needed on the {@code null}-means-all list / eligibility
   * paths.
   *
   * @return {@code true} for admins (all-scopes or pinned) and for non-admins with an effective
   *     squadron; {@code false} for a squadron-less non-admin or anonymous caller.
   */
  public boolean hasPromotionReadAccess() {
    return authHelper.isAdmin() || currentSquadronId().isPresent();
  }

  /**
   * Throws {@link AccessDeniedException} when the per-squadron promotion-system feature flag is off
   * for the caller's scope. Admins bypass the check (see {@link
   * #isPromotionFeatureEnabledForCurrentScope()} for the resolution rules). Used at the top of
   * every promotion write-service method to short-circuit the request with HTTP 403 before any
   * mutation runs.
   *
   * @throws AccessDeniedException if a non-admin caller's home squadron has the flag disabled.
   */
  public void assertPromotionFeatureEnabled() {
    if (!isPromotionFeatureEnabledForCurrentScope()) {
      throw new AccessDeniedException(
          "Promotion feature is disabled for the caller's squadron; ask an administrator to"
              + " re-enable it.");
    }
  }

  /**
   * Reads the active-context pin from the {@link #ACTIVE_ORG_UNIT_HEADER} request header. Shared by
   * the scope predicates and the create-time owner-stamping ({@link OrgUnitStampingService}) and
   * the ownerless-personal-row gate ({@link AccessGateService}) so they all honour the same
   * untrusted pin. Empty on a {@code null}, blank or malformed header value.
   *
   * @return the parsed active OrgUnit id, or empty when the header is absent / malformed.
   */
  @NotNull
  public Optional<UUID> readActiveSquadronFromHeader() {
    return parseHeaderUuid(request.getHeader(ACTIVE_ORG_UNIT_HEADER));
  }

  /**
   * Parses a single header value into a UUID. Returns {@link Optional#empty()} on {@code null},
   * blank, or malformed input. Malformed input is debug-logged inside the caller rather than thrown
   * so a stray client cannot spam the WARN channel.
   *
   * @param raw raw header value from {@link HttpServletRequest#getHeader(String)}; may be {@code
   *     null}.
   * @return parsed UUID or empty.
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
    Optional<Optional<UUID>> cached = readCachedOptional(CACHE_KEY_PERSISTENT_USER_SQUADRON_ID);
    if (cached.isPresent()) {
      return cached.get();
    }
    // REQ-ORG-017: the user's Staffel lives in org_unit_membership (kind=SQUADRON), and a user may
    // now hold up to TWO Staffeln (the V98 uq_org_unit_membership_one_squadron index was relaxed to
    // <=2 in V164). This single-valued accessor resolves the caller's ACTIVE Staffel: honour an
    // X-Active-Org-Unit-Id pin that points at one of the caller's own Staffel memberships
    // (mirroring
    // the non-admin pin handling in currentScopePredicate()); otherwise fall back to a
    // DETERMINISTIC
    // name-sorted primary (matching UserMapper.resolveSquadron / UserDto.squadron) rather than an
    // arbitrary first row, so the auto-stamp and single-value surfaces agree with the displayed
    // primary Staffel.
    Optional<UUID> resolved =
        authHelper
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
                      && rows.stream()
                          .anyMatch(r -> r.getId().getOrgUnitId().equals(pinned.get()))) {
                    return pinned;
                  }
                  // No matching pin: the deterministic name-sorted primary. The name-sort (and the
                  // single-Staffel fast path that skips the squadron load) is owned by
                  // StaffelMembershipResolver so this fallback agrees with UserDto.squadron /
                  // OrgUnitMembershipService.findStaffelMembershipOrgUnitIds by construction.
                  return staffelMembershipResolver.resolveNameSortedStaffelIds(rows).stream()
                      .findFirst();
                });
    request.setAttribute(CACHE_KEY_PERSISTENT_USER_SQUADRON_ID, resolved);
    return resolved;
  }

  /**
   * Reads a previously-cached {@link Optional} from the current {@link HttpServletRequest} under
   * {@code key}. The outer {@link Optional} of the return value signals presence in the cache: an
   * outer {@link Optional#empty()} means "key not yet written, do the real work", while a present
   * outer Optional wraps the cached value (which may itself be {@link Optional#empty()} for the
   * "resolved-to-empty" case). Keeps the unchecked cast confined to a single helper instead of
   * being repeated at every call site.
   *
   * @param key request-attribute key under which the cached Optional was previously stored.
   * @param <T> element type of the cached Optional.
   * @return outer-present iff the key has been written this request; the inner Optional is the
   *     cached value as written.
   */
  @NotNull
  @SuppressWarnings("unchecked")
  private <T> Optional<Optional<T>> readCachedOptional(@NotNull String key) {
    Object raw = request.getAttribute(key);
    if (raw instanceof Optional<?> opt) {
      return Optional.of((Optional<T>) opt);
    }
    return Optional.empty();
  }
}
