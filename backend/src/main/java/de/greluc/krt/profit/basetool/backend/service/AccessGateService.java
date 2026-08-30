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

import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorization-gate slab of {@link OwnerScopeService} (L3 split, #922): the {@code can*} decision
 * methods that gate every {@code @PreAuthorize} on the org-unit-scoped aggregates (mission, hangar,
 * inventory, refinery, operation, job order). Each gate evaluates the effective-scope vector
 * resolved by {@link RequestScopeResolver} ({@link RequestScopeResolver#currentScopePredicate()},
 * {@link RequestScopeResolver#canViewJobOrders()}, the active-context pin) so a per-row detail/edit
 * check can never diverge from what the scoped lists show.
 *
 * <p>The gates are invoked from SpEL as {@code @ownerScopeService.canX(...)} — {@link
 * OwnerScopeService} is the delegating facade that keeps the {@code ownerScopeService} bean name
 * and forwards each gate here, so the SpEL strings resolve unchanged.
 *
 * <p>The class-level {@code @Transactional(readOnly = true)} mirrors {@link OwnerScopeService}:
 * every repository call here is a read-only lookup behind the decision.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccessGateService {

  private final RequestScopeResolver requestScopeResolver;
  private final AuthHelperService authHelper;
  private final MissionRepository missionRepository;
  private final JobOrderRepository jobOrderRepository;
  private final JobOrderHandoverRepository jobOrderHandoverRepository;
  private final JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  private final InventoryItemRepository inventoryItemRepository;
  private final RefineryOrderRepository refineryOrderRepository;
  private final OperationRepository operationRepository;
  private final ShipRepository shipRepository;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;

  /**
   * {@code true} iff the current principal may see data owned by {@code squadronId} — where the id
   * may name either a Staffel or a Spezialkommando. Evaluates the very same effective-scope vector
   * the staffel-scoped <em>list</em> queries use ({@link
   * RequestScopeResolver#currentScopePredicate()} → {@link ScopePredicate#permits(UUID)}), so a
   * per-row detail/edit check can never diverge from what the lists show:
   *
   * <ul>
   *   <li>Admin without an active pin: {@code true} for every org unit.
   *   <li>Admin or non-admin pinned to one org unit: {@code true} only for that pinned id.
   *   <li>Non-admin without a pin: {@code true} for any org unit they are a member of — the union
   *       of their Staffel <em>and</em> every Spezialkommando they belong to.
   *   <li>Anonymous: {@code false} for everything (only Mission's {@code isInternal = false} public
   *       escape, applied by the callers, lets a guest through).
   * </ul>
   *
   * <p>Before this delegated to {@link RequestScopeResolver#currentScopePredicate()} it consulted
   * only the home Staffel, which denied SK members — and squadron-less SK leads entirely —
   * detail/edit access to their own SK's strict aggregates and internal missions, even though the
   * lists (which already used the predicate) showed those rows. Strict-staffel isolation is
   * preserved: a non-admin still matches only org units in their own membership set, and a foreign
   * pin collapses to that set rather than granting foreign access.
   *
   * @param squadronId the org-unit id (Staffel or Spezialkommando) whose data the caller wants to
   *     read; never {@code null}.
   * @return {@code true} iff the caller may see the given org unit's data.
   */
  public boolean canSeeSquadron(@NotNull UUID squadronId) {
    return requestScopeResolver.currentScopePredicate().permits(squadronId);
  }

  /**
   * Plan-aligned alias for {@link #canSeeSquadron(UUID)} — same semantics, generalised name so R2.d
   * can migrate the SpEL strings onto an org-unit-shaped vocabulary without changing behaviour.
   * Once Spezialkommando ids start flowing through the admin switcher, this method's implementation
   * will move ahead of the legacy {@code canSeeSquadron} and the latter will start delegating in
   * the opposite direction.
   *
   * @param orgUnitId the org-unit id whose data the caller wants to read; never {@code null}.
   * @return {@code true} iff the caller may see the given org unit's data.
   */
  public boolean canSeeOrgUnit(@NotNull UUID orgUnitId) {
    return canSeeSquadron(orgUnitId);
  }

  /**
   * {@code true} iff the current principal may write to data owned by {@code squadronId}. Identical
   * rule to {@link #canSeeSquadron(UUID)} — write access tracks read access for the staffel-scoped
   * aggregates. Kept as a separate method so future read/write divergence (e.g. a read-only viewer
   * role) can land here without breaking existing call sites.
   *
   * @param squadronId the squadron whose data the caller wants to write; never {@code null}.
   * @return {@code true} iff the caller may write to the given squadron's data.
   */
  public boolean canEditSquadron(@NotNull UUID squadronId) {
    return canSeeSquadron(squadronId);
  }

  /**
   * Plan-aligned alias for {@link #canEditSquadron(UUID)}; pairs with {@link #canSeeOrgUnit(UUID)}
   * the same way the legacy pair does.
   *
   * @param orgUnitId the org-unit id whose data the caller wants to write; never {@code null}.
   * @return {@code true} iff the caller may write to the given org unit's data.
   */
  public boolean canEditOrgUnit(@NotNull UUID orgUnitId) {
    return canEditSquadron(orgUnitId);
  }

  /**
   * SPEZIALKOMMANDO_PLAN.md §6.1 contextual-authority check. Returns {@code true} iff the current
   * authenticated principal carries an {@link
   * de.greluc.krt.profit.basetool.backend.support.OrgUnitContextualAuthority} matching {@code
   * (roleName, orgUnitId)}. Admins always pass — they have implicit elevated access in every
   * OrgUnit (mirrors the {@link #canEditSquadron} short-circuit).
   *
   * <p>Designed for {@code @PreAuthorize} SpEL where the OrgUnit id is only known at runtime (from
   * a request DTO field, a path variable, etc.). Example:
   *
   * <pre>{@code
   * @PreAuthorize("@ownerScopeService.hasRoleInOrgUnit(#dto.owningOrgUnitId, 'LOGISTICIAN')")
   * public InventoryItemDto createInventoryItem(@Valid @RequestBody InventoryItemCreateDto dto)
   * }</pre>
   *
   * <p>The dual-track migration: the JWT converter emits both the flat {@code ROLE_LOGISTICIAN}
   * (back-compat for existing {@code hasRole('LOGISTICIAN')} gates) and the contextual {@code
   * ROLE_LOGISTICIAN@<uuid>}. This helper matches against the contextual surface; flat-role gates
   * keep working through {@code hasRole(...)} unchanged.
   *
   * @param orgUnitId the OrgUnit the caller wants to act on; never {@code null}. {@code null}
   *     OrgUnit id is a programming error — use {@link #canEditSquadron(UUID)} for the "any
   *     OrgUnit" semantics, this method is exclusively contextual.
   * @param roleName the role to check for. Standard values today: {@code "LOGISTICIAN"}, {@code
   *     "MISSION_MANAGER"}. Never {@code null}.
   * @return {@code true} iff the caller is an admin or holds the contextual authority.
   */
  public boolean hasRoleInOrgUnit(@NotNull UUID orgUnitId, @NotNull String roleName) {
    if (authHelper.isAdmin()) {
      return true;
    }
    Optional<org.springframework.security.core.Authentication> authentication =
        authHelper.currentAuthentication();
    if (authentication.isEmpty()
        || !authentication.get().isAuthenticated()
        || authentication.get().getAuthorities() == null) {
      return false;
    }
    de.greluc.krt.profit.basetool.backend.support.OrgUnitContextualAuthority target =
        new de.greluc.krt.profit.basetool.backend.support.OrgUnitContextualAuthority(
            roleName, orgUnitId);
    for (org.springframework.security.core.GrantedAuthority a :
        authentication.get().getAuthorities()) {
      if (a instanceof de.greluc.krt.profit.basetool.backend.support.OrgUnitContextualAuthority ctx
          && ctx.equals(target)) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code true} iff the current principal may read mission {@code missionId}. Combines the generic
   * {@link #canSeeSquadron(UUID)} check with Mission's cross-staffel-visibility rule
   * (MULTI_SQUADRON_PLAN.md section 1): non-internal missions are visible from any squadron,
   * internal missions only from the owning squadron and admins. Non-existent ids return {@code
   * false}.
   *
   * <p>Audit hardenings on top of the cross-staffel rule:
   *
   * <ul>
   *   <li><b>M-2</b>: anonymous callers do not see {@code COMPLETED} / {@code CANCELLED} missions
   *       at all. The mission archive is restricted to authenticated members so a guest cannot
   *       (re-)write the participant list / finance ledger of an already-archived mission.
   *   <li><b>M-3</b>: walks the {@code parent} chain — a sub-mission with {@code isInternal=false}
   *       below an {@code isInternal=true} parent does not leak the parent's existence to anonymous
   *       callers. If ANY ancestor is internal-and-foreign, access is denied.
   * </ul>
   *
   * @param missionId mission to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the mission.
   */
  public boolean canSeeMission(@NotNull UUID missionId) {
    // em.find scope load (#1139): reads only status / isInternal / owningOrgUnit and walks the
    // parent chain — never the roster — so it must not run the graphed roster findById inside the
    // security interceptor's transaction (which would load the aggregate a second time before the
    // controller transaction loads it again). em.find loads no collection graph and never
    // auto-flushes.
    return missionRepository
        .findByIdForAuthorization(missionId)
        .map(
            m -> {
              if (!authHelper.isAuthenticated()
                  && ("COMPLETED".equals(m.getStatus()) || "CANCELLED".equals(m.getStatus()))) {
                return false;
              }
              for (Mission ancestor = m; ancestor != null; ancestor = ancestor.getParent()) {
                if (!canSeeMissionRow(ancestor)) {
                  return false;
                }
              }
              return true;
            })
        .orElse(false);
  }

  /**
   * Per-row visibility check shared by {@link #canSeeMission(UUID)} and its parent-chain walk.
   *
   * <ul>
   *   <li><b>Ownerless mission</b> ({@code owningOrgUnit == null}, a leadership / "Bereichsleitung"
   *       mission created by a user who belongs to no OrgUnit): visible to everyone when
   *       non-internal (the public default), and to organisation members-or-above ({@link
   *       AuthHelperService#isMemberOrAbove()}, which reaches admins) when internal. An internal
   *       ownerless mission is thus hidden from guests and anonymous visitors — the membershipless
   *       analogue of a Staffel-internal mission being visible to that Staffel.
   *   <li><b>Org-owned mission</b>: visible if the caller may see the owning org unit, or the
   *       mission is explicitly non-internal (the cross-staffel public escape).
   * </ul>
   */
  private boolean canSeeMissionRow(Mission m) {
    if (m.getOwningOrgUnit() == null) {
      if (!Boolean.TRUE.equals(m.getIsInternal())) {
        return true;
      }
      return authHelper.isMemberOrAbove();
    }
    if (canSeeSquadron(m.getOwningOrgUnit().getId())) {
      return true;
    }
    return !Boolean.TRUE.equals(m.getIsInternal());
  }

  /**
   * {@code true} iff the current principal may edit mission {@code missionId}. Strict
   * owning-squadron check — {@link #canSeeMission(UUID)}'s public-mission escape clause does NOT
   * apply to write operations (editing/finalising is the owning squadron's prerogative).
   * Non-existent ids return {@code false}.
   *
   * <p>An <b>ownerless mission</b> ({@code owningOrgUnit == null} — a leadership /
   * "Bereichsleitung" mission) has no owning org unit to scope against, so this per-row check is a
   * no-op and returns {@code true}; the effective write gate is then {@code
   * MissionSecurityService.canManageMission}'s usual elevated-role-or-owner/manager check (owner,
   * co-managers, mission-managers/officers, admins) — the same path as a normal mission, minus the
   * squadron-scope narrowing.
   *
   * @param missionId mission to inspect; never {@code null}.
   * @return {@code true} iff the caller may edit the mission.
   */
  public boolean canEditMission(@NotNull UUID missionId) {
    // em.find scope load (#1139): reads only owningOrgUnit — no roster, no auto-flush.
    return missionRepository
        .findByIdForAuthorization(missionId)
        .map(m -> m.getOwningOrgUnit() == null || canEditSquadron(m.getOwningOrgUnit().getId()))
        .orElse(false);
  }

  /**
   * {@code true} iff the current principal may read job order {@code jobOrderId} (Phase 3, #343).
   * Job Orders are a <em>conditionally</em> staffel-scoped aggregate:
   *
   * <ul>
   *   <li>responsible = Spezialkommando → <b>public</b>: visible to every <em>profit-eligible</em>
   *       caller (see {@link RequestScopeResolver#canViewJobOrders()}), so the central SK queue
   *       stays a shared workspace across all profit squadrons. A non-profit member sees it no more
   *       than the rest of the order area.
   *   <li>responsible = Squadron → <b>private</b>: visible only to a member of that squadron and to
   *       admins. The requester does NOT grant visibility (a squadron-private order is invisible to
   *       the customer squadron unless it happens to also be the responsible one).
   * </ul>
   *
   * <p>Both branches are additionally gated by the viewer-side profit check: a caller who belongs
   * to no profit-eligible org unit (and is not an admin) may read no order at all.
   *
   * <p>A {@code null} responsible org unit (legacy rows before the V130 backfill) is treated as
   * visible — defensive only; the backfill + NOT NULL constraint means no such row survives in
   * practice. Non-existent ids return {@code false}.
   *
   * @param jobOrderId job order to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the order.
   */
  public boolean canSeeJobOrder(@NotNull UUID jobOrderId) {
    return jobOrderRepository.findById(jobOrderId).map(this::canSeeJobOrderRow).orElse(false);
  }

  /**
   * Entity overload of {@link #canSeeJobOrder(UUID)} for callers that already hold a managed {@link
   * JobOrder} (e.g. the active-order lookup projection, which loads the rows in one query) — avoids
   * a per-row {@code findById} re-fetch. Same visibility contract as the id overload (viewer-side
   * profit gate, SK-public escape, squadron-private otherwise).
   *
   * @param order the job order to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the order.
   */
  public boolean canSeeJobOrder(@NotNull JobOrder order) {
    return canSeeJobOrderRow(order);
  }

  /**
   * Per-row read check shared by {@link #canSeeJobOrder(UUID)}. First applies the viewer-side
   * profit gate ({@link RequestScopeResolver#canViewJobOrders()}): a caller who is not a member of
   * any profit-eligible org unit (and is not an admin) may see no order at all — not even the
   * otherwise-public SK queue. For a permitted viewer, SK-responsible orders are public and
   * squadron-responsible orders defer to {@link #canSeeSquadron(UUID)}.
   *
   * @param o the job order whose responsible org unit gates visibility.
   * @return {@code true} iff the caller may read the row.
   */
  private boolean canSeeJobOrderRow(JobOrder o) {
    if (!requestScopeResolver.canViewJobOrders()) {
      return false;
    }
    OrgUnit responsible = o.getResponsibleOrgUnit();
    if (responsible == null || responsible.getKind() == OrgUnitKind.SPECIAL_COMMAND) {
      return true;
    }
    return canSeeSquadron(responsible.getId());
  }

  /**
   * {@code true} iff the current principal may see the item job-order <em>blueprint-coverage</em>
   * view of {@code jobOrderId} — who among the order's responsible (processing) squadron/SK owns
   * the blueprints for the order's required items. This is <strong>stricter</strong> than {@link
   * #canSeeJobOrder(UUID)}: an SK-responsible order is publicly readable (its detail page is shown
   * to every profit-eligible member), but the coverage view exposes which named members hold which
   * blueprints, so it is restricted to members of the responsible org unit itself.
   *
   * <p>The check delegates to {@link #canSeeSquadron(UUID)} on the order's responsible org unit,
   * which evaluates the same effective-scope vector the staffel-scoped lists use: a non-admin
   * matches only org units in their own membership set (whether the responsible unit is a Staffel
   * or a Spezialkommando), an admin without an active pin matches every org unit, and an admin
   * pinned to another org unit does not. There is therefore no SK-public escape here — a non-member
   * viewing an SK order's detail page is denied the coverage view (HTTP 403), and the frontend
   * simply omits the section. A {@code null} responsible org unit (legacy pre-backfill rows) and
   * non-existent ids return {@code false}.
   *
   * @param jobOrderId the job order whose blueprint-coverage view the caller wants to read; never
   *     {@code null}.
   * @return {@code true} iff the caller is a member of the order's responsible org unit (or an
   *     admin with matching scope).
   */
  public boolean canSeeJobOrderBlueprintOwners(@NotNull UUID jobOrderId) {
    return jobOrderRepository
        .findById(jobOrderId)
        .map(JobOrder::getResponsibleOrgUnit)
        .map(responsible -> canSeeSquadron(responsible.getId()))
        .orElse(false);
  }

  /**
   * {@code true} iff the current principal may see the <em>inventory owner identity and
   * location</em> of the stock earmarked / linked to {@code jobOrderId} — the per-entry owner and
   * Standort the order-detail Item-Bestand panel, the material collection and the two inventory
   * pickers render (REQ-ORDERS-029). Like {@link #canSeeJobOrderBlueprintOwners(UUID)} this is
   * <strong>stricter</strong> than {@link #canSeeJobOrder(UUID)}: it drops the SK-public escape, so
   * for a Spezialkommando-processed (publicly readable) order it is {@code true} only for members
   * of the responsible SK (or admins with matching scope), never for a member of the merely
   * <em>requesting</em> squadron. A requesting-side viewer therefore still sees the order and its
   * progress, but the fulfilling side's owner/location is redacted (ADR-0107).
   *
   * <p>For a squadron-responsible order it coincides with {@link #canSeeJobOrder(UUID)} (whose
   * squadron branch already requires {@link #canSeeSquadron(UUID)} on the responsible unit), so the
   * redaction only ever engages on the SK-public escape path. A {@code null} responsible org unit
   * (legacy pre-backfill rows) and non-existent ids return {@code false} — i.e. owner/location is
   * redacted by default, the safe direction for a redactor gate.
   *
   * @param jobOrderId the job order whose linked-inventory owner/location the caller wants to read;
   *     never {@code null}.
   * @return {@code true} iff the caller is a member of the order's responsible org unit (or an
   *     admin with matching scope).
   */
  public boolean canSeeJobOrderInventoryOwners(@NotNull UUID jobOrderId) {
    return jobOrderRepository
        .findById(jobOrderId)
        .map(JobOrder::getResponsibleOrgUnit)
        .map(responsible -> canSeeSquadron(responsible.getId()))
        .orElse(false);
  }

  /**
   * {@code true} iff the current principal may edit job order {@code jobOrderId} (Phase 3, #343).
   * Mirrors {@link #canSeeJobOrder(UUID)} but for writes:
   *
   * <ul>
   *   <li>responsible = Spezialkommando → editable by anyone the endpoint's role gate admits
   *       (LOGISTICIAN+). The central SK queue is a shared workspace, so write access is governed
   *       by role rather than by squadron scope; this method does not further restrict it.
   *   <li>responsible = Squadron → editable only by a member of that squadron and admins, exactly
   *       like {@link #canEditSquadron(UUID)}.
   * </ul>
   *
   * <p>Both branches are additionally gated by the viewer-side profit check ({@link
   * RequestScopeResolver#canViewJobOrders()}): a caller who belongs to no profit-eligible org unit
   * (and is not an admin) may edit no order, mirroring the read path.
   *
   * <p>Non-existent ids return {@code false}.
   *
   * @param jobOrderId job order to inspect; never {@code null}.
   * @return {@code true} iff the caller may edit the order.
   */
  public boolean canEditJobOrder(@NotNull UUID jobOrderId) {
    return jobOrderRepository.findById(jobOrderId).map(this::canEditJobOrderRow).orElse(false);
  }

  /**
   * Per-row write check shared by {@link #canEditJobOrder(UUID)}. First applies the same
   * viewer-side profit gate as the read path ({@link RequestScopeResolver#canViewJobOrders()}): a
   * caller who is not a member of any profit-eligible org unit (and is not an admin) may edit no
   * order at all — not even the shared SK queue. For a permitted caller, SK-responsible orders are
   * open to the role gate and squadron-responsible orders defer to {@link #canEditSquadron(UUID)}.
   *
   * @param o the job order whose responsible org unit gates write access.
   * @return {@code true} iff the caller may edit the row.
   */
  private boolean canEditJobOrderRow(JobOrder o) {
    if (!requestScopeResolver.canViewJobOrders()) {
      return false;
    }
    OrgUnit responsible = o.getResponsibleOrgUnit();
    if (responsible == null || responsible.getKind() == OrgUnitKind.SPECIAL_COMMAND) {
      return true;
    }
    return canEditSquadron(responsible.getId());
  }

  /**
   * {@code true} iff the current principal may read job order {@code jobOrderId} as its
   * <em>requester</em> (Auftraggeber) — a direct member of the order's requesting org unit — even
   * when the profit-eligibility gate ({@link RequestScopeResolver#canViewJobOrders()}) would
   * otherwise deny them (REQ-ORDERS-023). This is an <em>additional</em> escape, ORed with {@link
   * #canSeeJobOrder(UUID)} at the endpoint level; it never widens the general order queue (which
   * stays responsible-scoped) — it only unlocks the caller's own placed order, whose detail
   * response the controller redacts (no Bearbeiter, no materials summary). Non-existent ids return
   * {@code false}.
   *
   * @param jobOrderId job order to inspect; never {@code null}.
   * @return {@code true} iff the caller is a direct member of the order's requesting org unit.
   */
  public boolean canSeeJobOrderAsRequester(@NotNull UUID jobOrderId) {
    return jobOrderRepository.findById(jobOrderId).map(this::isOrderRequesterRow).orElse(false);
  }

  /**
   * {@code true} iff the current principal may edit job order {@code jobOrderId} within the
   * requester limits (REQ-ORDERS-023): they are a direct member of the order's requesting org unit
   * AND the order is still <em>fully undelivered</em> — it has no material handover and no item
   * handover yet (the whole-order freeze). Once any delivery is recorded the requesting owner can
   * no longer change quantities, add/remove lines, or edit the comment. This gates the dedicated
   * requester-edit endpoints, which carry no {@code hasRole('LOGISTICIAN')} requirement (a
   * requesting-unit member need not be a logistician). Non-existent ids return {@code false}.
   *
   * @param jobOrderId job order to inspect; never {@code null}.
   * @return {@code true} iff the caller may edit the order as its (still-undelivered) requester.
   */
  public boolean canEditJobOrderAsRequester(@NotNull UUID jobOrderId) {
    return jobOrderRepository
        .findById(jobOrderId)
        .map(o -> isOrderRequesterRow(o) && !orderHasAnyDelivery(o.getId()))
        .orElse(false);
  }

  /**
   * Per-row requester check shared by {@link #canSeeJobOrderAsRequester(UUID)} and {@link
   * #canEditJobOrderAsRequester(UUID)}: {@code true} iff the order carries a requesting org unit
   * and the caller is a <em>direct</em> member of it (no leadership cascade — see {@link
   * RequestScopeResolver#currentUserIsMemberOfOrgUnit(UUID)}). Deliberately independent of the
   * profit gate, so a non-profit ordering-squad member matches their own placed orders.
   *
   * @param o the job order whose requesting org unit gates the escape.
   * @return {@code true} iff the caller directly belongs to the order's requesting org unit.
   */
  private boolean isOrderRequesterRow(JobOrder o) {
    OrgUnit requesting = o.getRequestingOrgUnit();
    return requesting != null
        && requestScopeResolver.currentUserIsMemberOfOrgUnit(requesting.getId());
  }

  /**
   * {@code true} iff the order has recorded at least one delivery — a material handover ({@link
   * de.greluc.krt.profit.basetool.backend.model.JobOrderHandover}) or an item handover ({@link
   * de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandover}). Drives the whole-order
   * freeze of {@link #canEditJobOrderAsRequester(UUID)}: once any delivery exists the requesting
   * owner may no longer edit the order (REQ-ORDERS-023). Uses lightweight existence queries rather
   * than loading the handover collections.
   *
   * @param jobOrderId the order to inspect; never {@code null}.
   * @return {@code true} iff at least one material or item handover exists for the order.
   */
  private boolean orderHasAnyDelivery(@NotNull UUID jobOrderId) {
    return jobOrderHandoverRepository.existsByJobOrderId(jobOrderId)
        || jobOrderItemHandoverRepository.existsByJobOrderId(jobOrderId);
  }

  /**
   * Read/write access check for an <em>ownerless personal aggregate</em> row — a ship, refinery
   * order, or inventory item whose {@code owningOrgUnit} is {@code null} because the creating user
   * belongs to no org unit (see {@link
   * OrgUnitStampingService#resolveOrgUnitForPickerOutputNullable(User, UUID)}). Such a row has no
   * org-unit scope to match, so it is reachable only by:
   *
   * <ul>
   *   <li>an admin in all-scopes mode (no active pin) — mirrors the {@code isAdminAllScope}
   *       short-circuit in the list queries, so a row an admin sees in the list stays openable;
   *   <li>its own owning user — identified by comparing the row's per-user owner against the JWT
   *       {@code sub}, which is the {@code app_user} primary key, so {@link
   *       AuthHelperService#currentUserId()} compares directly against {@code owner.getId()}.
   * </ul>
   *
   * <p>An admin <em>with</em> an active pin is treated like any scoped caller and does NOT see
   * ownerless rows — consistent with {@link #canSeeSquadron(UUID)} returning {@code false} for a
   * pinned admin against a non-matching scope, and with the list queries excluding null-owner rows
   * once {@code activeOrgUnitId} is set.
   *
   * @param owner the row's per-user owner ({@code ship.owner} / {@code refinery_order.owner} /
   *     {@code inventory_item.user}); may be {@code null} defensively, which denies all non-admin
   *     access.
   * @return {@code true} iff the current caller may see/edit the ownerless personal row.
   */
  private boolean canAccessOwnerlessPersonalRow(@Nullable User owner) {
    if (authHelper.isAdmin() && requestScopeResolver.readActiveSquadronFromHeader().isEmpty()) {
      return true;
    }
    return isCurrentUserOwner(owner);
  }

  /**
   * {@code true} iff the current authenticated principal is the per-user owner of the row carrying
   * {@code owner}. The JWT {@code sub} is the {@code app_user} primary key, so {@link
   * AuthHelperService#currentUserId()} compares directly against {@code owner.getId()}. An
   * anonymous caller (no {@code currentUserId}) and a {@code null} / id-less owner never match.
   *
   * @param owner the row's per-user owner ({@code inventory_item.user}, {@code ship.owner}, {@code
   *     refinery_order.owner}); may be {@code null}, which never matches.
   * @return {@code true} iff the caller is that owner.
   */
  private boolean isCurrentUserOwner(@Nullable User owner) {
    return owner != null
        && owner.getId() != null
        && authHelper.currentUserId().map(uid -> uid.equals(owner.getId())).orElse(false);
  }

  /**
   * Shared owner-escape triad (S6, #912) behind {@code Ship}/{@code InventoryItem}/{@code
   * RefineryOrder} — the three "personal aggregates" of REQ-ORG-003 that each carry a per-user
   * owner escape (REQ-ORG-011) on top of the strict owning-org-unit scope. Resolution order,
   * identical across all six callers this replaces:
   *
   * <ul>
   *   <li>the per-user owner (from {@code owner.apply(row)}) may always see/edit the row,
   *       regardless of its org-unit stamp ({@link #isCurrentUserOwner(User)});
   *   <li>otherwise, for an ownerless personal row ({@code orgUnit.apply(row) == null}) it defers
   *       to {@link #canAccessOwnerlessPersonalRow(User)} (admins in all-scopes mode);
   *   <li>otherwise the strict owning-org-unit scope check — {@link #canSeeSquadron(UUID)} when
   *       {@code edit} is {@code false}, {@link #canEditSquadron(UUID)} when {@code true}.
   * </ul>
   *
   * <p>{@code row} being empty (a non-existent id) returns {@code false}.
   *
   * @param row the row to inspect, already resolved (typically via a {@code findById}); empty
   *     denies access
   * @param owner extracts the row's per-user owner field ({@code ship.owner} / {@code
   *     inventory_item.user} / {@code refinery_order.owner})
   * @param orgUnit extracts the row's {@code owningOrgUnit} association; {@code null} marks an
   *     ownerless personal row
   * @param edit {@code true} to apply the edit-scope check, {@code false} for the read-scope check
   * @param <T> the row's entity type
   * @return {@code true} iff the current caller may see/edit the row per the rules above
   */
  private <T> boolean permitsRow(
      Optional<T> row, Function<T, User> owner, Function<T, OrgUnit> orgUnit, boolean edit) {
    return row.map(
            r -> {
              User rowOwner = owner.apply(r);
              OrgUnit rowOrgUnit = orgUnit.apply(r);
              return isCurrentUserOwner(rowOwner)
                  || (rowOrgUnit == null
                      ? canAccessOwnerlessPersonalRow(rowOwner)
                      : (edit
                          ? canEditSquadron(rowOrgUnit.getId())
                          : canSeeSquadron(rowOrgUnit.getId())));
            })
        .orElse(false);
  }

  /**
   * {@code true} iff the current principal may read inventory item {@code itemId} directly (the
   * Lager-direct path — NOT the Job-Order-Kontext path, which is ungated by design). Resolution
   * order:
   *
   * <ul>
   *   <li><b>Owner escape (REQ-ORG-011)</b>: the item's per-user owner ({@code
   *       inventory_item.user}) may always read it, regardless of the org-unit stamp — even after
   *       they switch org units or lose their last membership while the row is still stamped to an
   *       org unit. This mirrors the service layer, which gates every owner action on {@code
   *       item.user == currentUser} with no org-unit narrowing, so the gate never denies what the
   *       service would allow.
   *   <li>otherwise, for an ownerless personal item ({@code owningOrgUnit == null}) it defers to
   *       {@link #canAccessOwnerlessPersonalRow(User)} (admins in all-scopes mode);
   *   <li>otherwise the strict owning-org-unit scope check ({@link #canSeeSquadron(UUID)}).
   * </ul>
   *
   * <p>Non-existent ids return {@code false}.
   *
   * @param itemId inventory item to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the item.
   */
  public boolean canSeeInventoryItem(@NotNull UUID itemId) {
    return permitsRow(
        inventoryItemRepository.findById(itemId),
        InventoryItem::getUser,
        InventoryItem::getOwningOrgUnit,
        false);
  }

  /**
   * {@code true} iff the current principal may edit inventory item {@code itemId} directly.
   * Resolution order:
   *
   * <ul>
   *   <li><b>Owner escape (REQ-ORG-011)</b>: the item's per-user owner ({@code
   *       inventory_item.user}) may always edit it, regardless of the org-unit stamp — even after
   *       they switch org units or lose their last membership while the row is still stamped to an
   *       org unit. This mirrors the service-layer owner check ({@code InventoryItemService} gates
   *       owner book-out / note / delivered / association writes on {@code item.user ==
   *       currentUser} with no org-unit narrowing), so the {@code @PreAuthorize} gate never denies
   *       what the service would allow.
   *   <li>otherwise, for an ownerless personal item ({@code owningOrgUnit == null}) it defers to
   *       {@link #canAccessOwnerlessPersonalRow(User)} (admins in all-scopes mode);
   *   <li>otherwise the strict owning-org-unit scope check ({@link #canEditSquadron(UUID)}) —
   *       Job-Order-Kontext handover writes are gated separately by {@code
   *       JobOrderHandoverService}'s {@code item.jobOrderId == currentOrder.id} guard.
   * </ul>
   *
   * <p>Non-existent ids return {@code false}.
   *
   * @param itemId inventory item to inspect; never {@code null}.
   * @return {@code true} iff the caller may edit the item.
   */
  public boolean canEditInventoryItem(@NotNull UUID itemId) {
    return permitsRow(
        inventoryItemRepository.findById(itemId),
        InventoryItem::getUser,
        InventoryItem::getOwningOrgUnit,
        true);
  }

  /**
   * {@code true} iff the current principal may read refinery order {@code orderId}. The per-user
   * owner escape (REQ-ORG-011) applies first — the order's {@code refinery_order.owner} may always
   * read it regardless of the org-unit stamp; otherwise an ownerless personal order ({@code
   * owningOrgUnit == null}) defers to {@link #canAccessOwnerlessPersonalRow(User)} (admins in
   * all-scopes mode) and an org-owned order to the strict owning-org-unit check ({@link
   * #canSeeSquadron(UUID)}; refinery is a strict-staffel aggregate without a public escape).
   * Non-existent ids return {@code false}.
   *
   * @param orderId refinery order to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the order.
   */
  public boolean canSeeRefineryOrder(@NotNull UUID orderId) {
    return refineryOrderRepository.findById(orderId).map(this::canSeeRefineryOrder).orElse(false);
  }

  /**
   * Entity overload of {@link #canSeeRefineryOrder(UUID)} for callers that already hold a managed
   * {@link RefineryOrder} (e.g. the mission-scoped refinery list) — avoids a per-row {@code
   * findById} re-fetch. Same resolution as the id overload: per-user owner escape (REQ-ORG-011)
   * first, then the ownerless ({@link #canAccessOwnerlessPersonalRow(User)}) / strict
   * owning-org-unit ({@link #canSeeSquadron(UUID)}) branches.
   *
   * @param order the refinery order to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the order.
   */
  public boolean canSeeRefineryOrder(@NotNull RefineryOrder order) {
    return permitsRow(
        Optional.of(order), RefineryOrder::getOwner, RefineryOrder::getOwningOrgUnit, false);
  }

  /**
   * {@code true} iff the current principal may edit refinery order {@code orderId}. The per-user
   * owner escape (REQ-ORG-011) applies first — the order's {@code refinery_order.owner} may always
   * edit it regardless of the org-unit stamp, mirroring {@code
   * RefineryOrderService.updateRefineryOrder} / {@code #deleteRefineryOrder} / {@code
   * #storeRefineryOrder}, which authorise the owner with no org-unit narrowing — so the
   * {@code @PreAuthorize} gate never denies a write the service would accept. Otherwise an
   * ownerless personal order ({@code owningOrgUnit == null}) defers to {@link
   * #canAccessOwnerlessPersonalRow(User)} (admins in all-scopes mode) and an org-owned order to the
   * strict owning-org-unit check ({@link #canEditSquadron(UUID)}). Non-existent ids return {@code
   * false}.
   *
   * @param orderId refinery order to inspect; never {@code null}.
   * @return {@code true} iff the caller may edit the order.
   */
  public boolean canEditRefineryOrder(@NotNull UUID orderId) {
    return permitsRow(
        refineryOrderRepository.findById(orderId),
        RefineryOrder::getOwner,
        RefineryOrder::getOwningOrgUnit,
        true);
  }

  /**
   * {@code true} iff the caller may read <em>any</em> of the target user's refinery orders through
   * the per-user list endpoint {@code GET /api/v1/refinery-orders/users/{userId}}. This is a
   * <b>coarse user-level pre-check</b>, not a per-row gate: it passes for an admin, the target user
   * themselves, or a caller whose strict org-unit scope ({@link #canSeeSquadron(UUID)}) covers
   * <em>any one</em> of the target user's memberships. A non-existent / membership-less target
   * yields {@code false} for non-admins.
   *
   * <p><b>Per-row scoping is NOT done here</b> (finding SEC-01). Because a member may belong to up
   * to two Staffeln (REQ-ORG-017), a caller who shares only one of them passes this {@code
   * anyMatch} gate yet must not see the target's orders stamped to the <em>other</em> Staffel —
   * which the per-order {@link #canSeeRefineryOrder(RefineryOrder)} gate would individually deny.
   * That strict-staffel filtering is enforced by the scoped list query {@code
   * RefineryOrderRepository#findByOwnerIdScoped} (via {@code
   * RefineryOrderService#getUserRefineryOrdersScoped}), so the page returned to the caller never
   * contains a row {@code canSeeRefineryOrder} would reject. This gate only decides whether the
   * caller has <em>any</em> legitimate interest in the target user at all (PR #808 / epic #800;
   * per-row leak closed by SEC-01).
   *
   * @param targetUserId the user whose refinery orders the caller wants to read; never {@code
   *     null}.
   * @return {@code true} iff the caller may read the target user's in-scope refinery orders.
   */
  public boolean canViewUserRefineryOrders(@NotNull UUID targetUserId) {
    return canActOnTargetUserScoped(targetUserId, this::canSeeSquadron);
  }

  /**
   * Write analogue of {@link #canViewUserRefineryOrders(UUID)} for the create-on-behalf endpoint
   * {@code POST /api/v1/refinery-orders/users/{userId}}; the same coarse {@code anyMatch}
   * user-level pre-check, scoped on {@link #canEditSquadron(UUID)} instead of {@link
   * #canSeeSquadron(UUID)}. The per-row constraint that actually keeps a write in bounds is the
   * stamp validation in {@link OrgUnitStampingService#resolveOrgUnitForPickerOutputNullable(User,
   * UUID)} → {@link OrgUnitStampingService#resolveStampedOrgUnit(java.util.Set, UUID)}: the new
   * order's {@code owningOrgUnit} must be a direct membership of the target user OR a unit the
   * caller may edit, so this gate passing on a single shared unit can never let the caller stamp a
   * row into a unit it cannot already reach. Unlike the read path (SEC-01), a too-broad gate here
   * is not a disclosure — a row stamped outside the caller's scope is one the caller cannot then
   * read back.
   *
   * @param targetUserId the user the caller wants to create a refinery order for; never {@code
   *     null}.
   * @return {@code true} iff the caller may create a refinery order on that user's behalf.
   */
  public boolean canManageUserRefineryOrders(@NotNull UUID targetUserId) {
    return canActOnTargetUserScoped(targetUserId, this::canEditSquadron);
  }

  /**
   * Write pre-check for every "create stock in another member's name" entry point: {@code POST
   * /api/v1/inventory} (Einbuchen) and the per-item receiver of {@code POST
   * /api/v1/refinery-orders/{id}/store}. Same resolution as {@link
   * #canManageUserRefineryOrders(UUID)} - admin, self, or at least one shared editable org unit -
   * because it is the same question about the same kind of write.
   *
   * <p><strong>It replaces a flat {@code ROLE_LOGISTICIAN} boolean, and that is the whole
   * point.</strong> Both call sites used to authorise the receiver with {@code
   * AuthHelperService#isLogisticianOrAbove()}, which is the OR-union over <em>all</em> of the
   * caller's memberships and carries no org-unit context whatsoever. A logistician of any Staffel
   * could therefore fabricate stock - shared, or with {@code personal} private - in the ledger of a
   * member of any other Staffel, which REQ-SEC-005 forbids and which the sibling endpoint {@code
   * POST /api/v1/refinery-orders/users/&#123;userId&#125;} had already closed with {@link
   * #canManageUserRefineryOrders(UUID)}. A role that says "may act on behalf of somebody" is not an
   * answer to "may act on behalf of <em>this</em> somebody".
   *
   * <p>Like its sibling this is a coarse {@code anyMatch} pre-check, not a per-row gate; the
   * per-row bound remains the stamp validation in {@code
   * OrgUnitStampingService#resolveOrgUnitForPickerOutputNullable}.
   *
   * @param targetUserId the member whose inventory would receive the row; never {@code null}.
   * @return {@code true} iff the caller may create inventory rows in that member's name.
   */
  public boolean canManageUserInventory(@NotNull UUID targetUserId) {
    return canActOnTargetUserScoped(targetUserId, this::canEditSquadron);
  }

  /**
   * Shared resolution for every per-user on-behalf endpoint - refinery orders and inventory
   * creation alike: admin all-access, then the self escape, then a coarse strict org-unit scope
   * check against the target user's memberships (read straight from {@code org_unit_membership},
   * never a lazy association). The {@code unitScope} predicate is {@link #canSeeSquadron(UUID)} for
   * reads / {@link #canEditSquadron(UUID)} for writes.
   *
   * <p><b>This is an {@code anyMatch} pre-check, not a per-row gate.</b> A non-admin passes as soon
   * as the caller shares <em>one</em> of the target's (up to two, REQ-ORG-017) org units, so the
   * caller may be in scope for some of the target's rows but not others. Callers MUST therefore
   * apply per-row scoping themselves: reads through the scoped query {@code findByOwnerIdScoped}
   * (SEC-01), writes through the {@link OrgUnitStampingService#resolveStampedOrgUnit(java.util.Set,
   * UUID)} stamp validation. This method alone does not bound which individual rows the caller may
   * touch.
   *
   * @param targetUserId the user being acted upon; never {@code null}.
   * @param unitScope the per-unit scope check to apply; never {@code null}.
   * @return {@code true} iff the caller shares at least one in-scope org unit with the target user.
   */
  private boolean canActOnTargetUserScoped(
      @NotNull UUID targetUserId, @NotNull java.util.function.Predicate<UUID> unitScope) {
    if (authHelper.isAdmin()) {
      return true;
    }
    if (authHelper.currentUserId().map(targetUserId::equals).orElse(false)) {
      return true;
    }
    return orgUnitMembershipRepository.findAllByIdUserId(targetUserId).stream()
        .map(m -> m.getId().getOrgUnitId())
        .anyMatch(unitScope);
  }

  /**
   * {@code true} iff the current principal may read operation {@code operationId}. Visible when
   * <em>any</em> of these holds:
   *
   * <ul>
   *   <li>the owning-squadron scope check passes (org-owned operation in the caller's scope);
   *   <li>the operation is an <em>ownerless leadership operation</em> ({@code owningOrgUnit ==
   *       null}, V145) and the caller is a member-or-above — operations have no public escape, so
   *       an ownerless operation is the org-wide analogue of a Staffel-internal operation, hidden
   *       from guests/anonymous (REQ-ORG-009);
   *   <li>the caller <em>participated</em> in one of the operation's linked missions (#500) — any
   *       authenticated participant may view the operation and their payout regardless of its
   *       owning OrgUnit. Anonymous callers never match (no {@code currentUserId}).
   * </ul>
   *
   * <p>Non-existent ids return {@code false}.
   *
   * @param operationId operation to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the operation.
   */
  public boolean canSeeOperation(@NotNull UUID operationId) {
    return operationRepository
        .findById(operationId)
        .map(
            o -> {
              boolean scopeVisible =
                  o.getOwningOrgUnit() == null
                      ? authHelper.isMemberOrAbove()
                      : canSeeSquadron(o.getOwningOrgUnit().getId());
              return scopeVisible || participatedInOperation(operationId);
            })
        .orElse(false);
  }

  /**
   * {@link #canSeeOperation(UUID)} <em>without</em> the participant escape: the caller must reach
   * the operation through org-unit scope (or the ownerless-leadership rule) alone.
   *
   * <p><strong>Why the money endpoints need a second, narrower predicate.</strong> The participant
   * escape is <em>self-issuable</em>. {@code POST /api/v1/missions/&#123;id&#125;/join} is gated
   * only on {@code isAuthenticated() and canSeeMission(#id)}, and {@code canSeeMission} is true for
   * any non-internal mission of any org unit - so one request enrols a member of Staffel A in a
   * public mission of Staffel B, and {@code participatedInOperation} then unlocks that operation.
   * ADR-0006 granted the escape on the reasoning that "participation is the right, minimal key",
   * which assumes participation is <em>granted</em>; here it is merely <em>claimed</em>. The escape
   * is therefore fine for seeing that an operation exists and which missions it links, and is not a
   * sufficient key to a foreign unit's finance ledger.
   *
   * @param operationId operation to inspect; never {@code null}.
   * @return {@code true} iff the caller reaches the operation without the participant escape.
   */
  public boolean canSeeOperationLedger(@NotNull UUID operationId) {
    return operationRepository
        .findById(operationId)
        .map(
            o ->
                o.getOwningOrgUnit() == null
                    ? authHelper.isMemberOrAbove()
                    : canSeeSquadron(o.getOwningOrgUnit().getId()))
        .orElse(false);
  }

  /**
   * {@code true} iff the current (authenticated) caller participated in one of the operation's
   * linked missions (#500). Backs the participant-visibility escape of {@link
   * #canSeeOperation(UUID)}; an anonymous caller (no {@code currentUserId}) never participates.
   *
   * @param operationId the operation to test; never {@code null}.
   * @return {@code true} iff the caller is a participant of one of the operation's missions.
   */
  private boolean participatedInOperation(@NotNull UUID operationId) {
    return authHelper
        .currentUserId()
        .map(uid -> operationRepository.existsParticipantUserInOperation(operationId, uid))
        .orElse(false);
  }

  /**
   * {@code true} iff the current principal may edit operation {@code operationId}. Strict
   * owning-squadron check for org-owned operations; for an <em>ownerless leadership operation</em>
   * ({@code owningOrgUnit == null}, V145) the per-row check is a no-op (returns {@code true}) — the
   * real write restriction is the controller's role gate ({@code hasRole('MISSION_MANAGER')} on
   * update, {@code hasRole('ADMIN')} on delete), so an org-wide leadership operation is editable by
   * any mission manager and deletable by any admin (REQ-ORG-009). Non-existent ids return {@code
   * false}.
   *
   * @param operationId operation to inspect; never {@code null}.
   * @return {@code true} iff the caller may edit the operation.
   */
  public boolean canEditOperation(@NotNull UUID operationId) {
    return operationRepository
        .findById(operationId)
        .map(o -> o.getOwningOrgUnit() == null || canEditSquadron(o.getOwningOrgUnit().getId()))
        .orElse(false);
  }

  /**
   * {@code true} iff the current principal may read ship {@code shipId}. The per-user owner escape
   * (REQ-ORG-011) applies first — the ship's {@code ship.owner} may always read it regardless of
   * the org-unit stamp; otherwise an ownerless personal ship ({@code owningOrgUnit == null}) defers
   * to {@link #canAccessOwnerlessPersonalRow(User)} (admins in all-scopes mode) and an org-owned
   * ship to the strict owning-org-unit check ({@link #canSeeSquadron(UUID)}; Hangar = strict eigene
   * Staffel). Non-existent ids return {@code false}.
   *
   * @param shipId ship to inspect; never {@code null}.
   * @return {@code true} iff the caller may read the ship.
   */
  public boolean canSeeShip(@NotNull UUID shipId) {
    return permitsRow(
        shipRepository.findById(shipId), Ship::getOwner, Ship::getOwningOrgUnit, false);
  }

  /**
   * {@code true} iff the current principal may edit ship {@code shipId}. The per-user owner escape
   * (REQ-ORG-011) applies first — the ship's {@code ship.owner} may always edit it regardless of
   * the org-unit stamp, mirroring {@code HangarService.updateShip} / {@code #deleteShip}, which
   * reject any non-owner caller, so the {@code @PreAuthorize} gate never denies a write the service
   * would accept. Otherwise an ownerless personal ship ({@code owningOrgUnit == null}) defers to
   * {@link #canAccessOwnerlessPersonalRow(User)} (admins in all-scopes mode) and an org-owned ship
   * to the strict owning-org-unit check ({@link #canEditSquadron(UUID)}). Non-existent ids return
   * {@code false}.
   *
   * @param shipId ship to inspect; never {@code null}.
   * @return {@code true} iff the caller may edit the ship.
   */
  public boolean canEditShip(@NotNull UUID shipId) {
    return permitsRow(
        shipRepository.findById(shipId), Ship::getOwner, Ship::getOwningOrgUnit, true);
  }
}
