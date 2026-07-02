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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create-time owner-stamping slab of {@link OwnerScopeService} (L3 split, #922): resolves the
 * {@link OrgUnit} a newly-created aggregate should be stamped on (the SPEZIALKOMMANDO_PLAN.md
 * §5.5.1 picker-output matrix) and validates an explicit owning-org-unit reassignment
 * (REQ-ORG-018). It reads the caller's active-context pin and membership reach from {@link
 * RequestScopeResolver} and the cascade-aware editable scope from {@link AccessGateService}; {@link
 * OwnerScopeService} is the delegating facade that forwards each {@code resolve*} method here.
 *
 * <p>The class-level {@code @Transactional(readOnly = true)} mirrors {@link OwnerScopeService} —
 * the stamp resolution only reads (membership + org-unit lookups); the caller persists the stamped
 * entity in its own write transaction.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgUnitStampingService {

  private final RequestScopeResolver requestScopeResolver;
  private final AccessGateService accessGateService;
  private final AuthHelperService authHelper;
  private final SquadronRepository squadronRepository;
  private final SpecialCommandRepository specialCommandRepository;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;
  private final OrgUnitRepository orgUnitRepository;

  /**
   * Resolves the {@link Squadron} that a newly-created aggregate should be stamped on, honouring an
   * optional R5.d owner-picker output. Centralises the validation that every aggregate-stamping
   * service path (inventory create, refinery-order create, mission create, …) would otherwise have
   * to duplicate.
   *
   * <p>R6.b tightens the contract to the plan §5.5.1 0/1/&gt;1-membership matrix:
   *
   * <ol>
   *   <li><b>0 memberships</b> — {@link BadRequestException}. Admin / guest principals cannot stamp
   *       aggregates; the caller path should not reach this method with a memberless user.
   *   <li><b>1 membership + {@code owningOrgUnitId == null}</b> — auto-stamp that single membership
   *       (preserves today's single-Staffel default for the 100% of users still on the legacy
   *       {@code app_user.squadron_id} link).
   *   <li><b>1 membership + {@code owningOrgUnitId} matches</b> — auto-stamp; the explicit picker
   *       output agrees with the only option, no-op.
   *   <li><b>1 membership + {@code owningOrgUnitId} mismatch</b> — {@link BadRequestException}
   *       (foreign-org-unit forgery).
   *   <li><b>&gt;1 memberships + {@code owningOrgUnitId == null}</b> — {@link BadRequestException}
   *       ("owningOrgUnitId is required"). Before R6.b this path silently stamped the legacy
   *       Staffel, hiding the SK choice from a multi-membership user — see the audit's regression
   *       #4 against the R5.d frontend contract.
   *   <li><b>&gt;1 memberships + {@code owningOrgUnitId} matches one</b> — picker output honoured;
   *       the matched OrgUnit is returned.
   *   <li><b>&gt;1 memberships + {@code owningOrgUnitId} foreign</b> — {@link BadRequestException}
   *       (foreign-org-unit forgery).
   *   <li><b>Picker selects a Spezialkommando</b> — {@link BadRequestException} ("Spezialkommando
   *       ownership not yet supported"). Soft block until the destructive-cleanup release drops the
   *       {@code owning_squadron_id} NOT NULL constraint; until then the picker UI may offer SK
   *       options but the backend reliably rejects them rather than persisting half-stamped rows.
   * </ol>
   *
   * <p>Membership sources (hybrid until the §5.2 D3 migration replaces {@code User.squadron} with
   * an authoritative membership-table read):
   *
   * <ul>
   *   <li>{@link User#getSquadron()} — the user's home Staffel; non-null today for every user. Read
   *       first because the legacy column is still authoritative for the Staffel link.
   *   <li>{@link OrgUnitMembershipRepository#findAllByIdUserIdAndKind} with kind {@link
   *       OrgUnitKind#SPECIAL_COMMAND} — the SK memberships added via the R5.b endpoints. SK
   *       memberships are never reflected on {@link User} so the repository read is the only
   *       source. The kind=SQUADRON rows backfilled by V95 are not consulted here — the
   *       authoritative Staffel comes from {@link User#getSquadron()}.
   * </ul>
   *
   * <p>Once D3 lands and {@code User.squadron} is removed, this method switches to a single {@link
   * OrgUnitMembershipRepository#findAllByIdUserId} read.
   *
   * @param targetUser the user the new aggregate belongs to (e.g. the inventory item's owner, the
   *     refinery order's owner); never {@code null}.
   * @param owningOrgUnitId the picker output from the form, or {@code null} when the picker was not
   *     used.
   * @return the Squadron whose stock / aggregate list this row should join; never {@code null}.
   * @throws BadRequestException when the picker output references an org unit the target user does
   *     not belong to, the user has zero memberships, the user has multiple memberships and no
   *     explicit choice was supplied, or the resolved org unit is a Spezialkommando.
   */
  public Squadron resolveSquadronForPickerOutput(@NotNull User targetUser, UUID owningOrgUnitId) {
    Set<UUID> memberOrgUnitIds = new LinkedHashSet<>();
    // Post-D3: every membership (Staffel + SK) is sourced from org_unit_membership — the legacy
    // User.squadron column was dropped in R9 Step 5 / V101.
    List<OrgUnitMembership> allMemberships =
        orgUnitMembershipRepository.findAllByIdUserId(targetUser.getId());
    for (OrgUnitMembership m : allMemberships) {
      memberOrgUnitIds.add(m.getId().getOrgUnitId());
    }

    if (memberOrgUnitIds.isEmpty()) {
      throw new BadRequestException(
          "User has no org-unit membership — cannot stamp an aggregate owner");
    }

    UUID stampedOrgUnitId;
    if (owningOrgUnitId == null) {
      if (memberOrgUnitIds.size() == 1) {
        stampedOrgUnitId = memberOrgUnitIds.iterator().next();
      } else {
        // REQ-ORG-017 "pin, else choose": honour an active-context pin onto one of the target's own
        // org units (self-service create) so a pinned member need not re-pick; otherwise force a
        // choice.
        Optional<UUID> pinned = requestScopeResolver.readActiveSquadronFromHeader();
        if (pinned.isPresent() && memberOrgUnitIds.contains(pinned.get())) {
          stampedOrgUnitId = pinned.get();
        } else {
          throw new BadRequestException(
              "User belongs to multiple org units; owningOrgUnitId is required");
        }
      }
    } else {
      if (!memberOrgUnitIds.contains(owningOrgUnitId)) {
        throw new BadRequestException(
            "Selected owner org unit is not a membership of the target user");
      }
      stampedOrgUnitId = owningOrgUnitId;
    }

    return squadronRepository
        .findById(stampedOrgUnitId)
        .orElseThrow(
            () ->
                new BadRequestException(
                    "Spezialkommando ownership of this aggregate is not yet supported"));
  }

  /**
   * V99-aligned successor of {@link #resolveSquadronForPickerOutput(User, UUID)} — applies the same
   * SPEZIALKOMMANDO_PLAN.md §5.5.1 picker-output matrix (0 / 1 / &gt;1 memberships, valid / foreign
   * choice) but returns an {@link OrgUnit} so SK selections are honoured instead of rejected. Use
   * with {@code entity.setOwningOrgUnit(...)}; the existing entity dual-write lifecycle hook
   * mirrors the value onto the legacy {@code owningSquadron} field whenever the resolved OrgUnit
   * happens to be a {@link Squadron}, so the legacy column stays populated for Staffel ownership
   * during the V99-NOT-NULL-relaxed soak. For SpecialCommand ownership the legacy column stays null
   * — which is now valid because V99 dropped the {@code NOT NULL} constraint.
   *
   * <p>If the resolver produces an SK selection (allowed post-V99 with the lifted NOT NULL on the
   * legacy column), the caller writes only the new {@code owningOrgUnitId} via {@code
   * entity.setOwningOrgUnit(...)}. The lifecycle hook leaves the legacy column null for that row,
   * which is now legal.
   *
   * <p>Decision matrix (extends the legacy {@link #resolveSquadronForPickerOutput(User, UUID)}
   * matrix, which stays strict): 0 memberships → 400; 1 + null picker → auto-stamp the sole direct
   * membership; &gt;1 + null picker → 400 (force an explicit choice). An explicit pick is honoured
   * when it is one of the target user's DIRECT memberships <em>or</em> — epic #692 Phase 4 /
   * REQ-ORG-016 — an org unit the current <b>caller</b> may edit ({@link
   * AccessGateService#canEditOrgUnit(UUID)}, cascade-aware), the create-on-behalf widening; a pick
   * that is neither → 400. Because of that widening this resolver and the still-strict
   * (membership-only) {@code resolveSquadronForPickerOutput} no longer agree byte-for-byte: a pick
   * foreign to the target user but within the caller's editable scope is rejected by the latter and
   * honoured here.
   *
   * @param targetUser the user whose memberships gate the picker output validation; never {@code
   *     null}.
   * @param owningOrgUnitId the picker-supplied org unit id; {@code null} triggers the auto-stamp
   *     path when the user has exactly one membership.
   * @return the resolved {@link OrgUnit} — a {@link Squadron}, a {@link
   *     de.greluc.krt.profit.basetool.backend.model.SpecialCommand}, or (Phase 4) a {@link
   *     de.greluc.krt.profit.basetool.backend.model.Bereich} / {@link
   *     de.greluc.krt.profit.basetool.backend.model.Organisationsleitung}; never {@code null}.
   * @throws BadRequestException on 0 memberships, a &gt;1-membership {@code null} picker, or an
   *     explicit pick that is neither a direct membership of the target user nor within the
   *     caller's editable scope.
   */
  public OrgUnit resolveOrgUnitForPickerOutput(@NotNull User targetUser, UUID owningOrgUnitId) {
    Set<UUID> memberOrgUnitIds = collectMemberOrgUnitIds(targetUser);
    if (memberOrgUnitIds.isEmpty()) {
      throw new BadRequestException(
          "User has no org-unit membership — cannot stamp an aggregate owner");
    }
    return resolveStampedOrgUnit(memberOrgUnitIds, owningOrgUnitId);
  }

  /**
   * Nullable-owner variant of {@link #resolveOrgUnitForPickerOutput(User, UUID)} for the three
   * <em>ownerless-personal-aggregate</em> roots (ship, refinery order, inventory item). Behaves
   * identically to the strict resolver, with one carve-out: a {@code targetUser} who belongs to no
   * org unit <em>and</em> supplied no explicit picker output resolves to {@code null} instead of a
   * 400. That {@code null} is a legal owner for these three aggregates — V132 dropped the {@code
   * NOT NULL} on their {@code owning_org_unit_id} column precisely so a membershipless user can
   * still add a ship, raise a refinery order, or record inventory. The row is then attributable
   * through its own per-user owner column ({@code ship.owner} / {@code refinery_order.owner} /
   * {@code inventory_item.user}) and is scoped to that user only — see {@link
   * AccessGateService#canSeeShip(UUID)}, {@link AccessGateService#canSeeRefineryOrder(UUID)},
   * {@link AccessGateService#canSeeInventoryItem(UUID)}.
   *
   * <p>The carve-out is deliberately narrow: a membershipless user who nonetheless supplies a
   * non-null {@code owningOrgUnitId} is still rejected — they cannot claim ownership of an org unit
   * they do not belong to. Every other branch of the SPEZIALKOMMANDO_PLAN.md §5.5.1 matrix (1 /
   * &gt;1 memberships; valid / foreign / multi-membership-null choice) is unchanged from the strict
   * resolver.
   *
   * @param targetUser the user whose memberships gate the picker output; never {@code null}.
   * @param owningOrgUnitId the picker-supplied org unit id, or {@code null} when the picker was not
   *     used.
   * @return the resolved {@link OrgUnit}, or {@code null} when {@code targetUser} has no membership
   *     and supplied no explicit choice (the ownerless-personal-aggregate case).
   * @throws BadRequestException for every non-ownerless rejection branch of the §5.5.1 matrix,
   *     including a membershipless user who supplied a non-null (therefore foreign) choice.
   */
  @Nullable
  public OrgUnit resolveOrgUnitForPickerOutputNullable(
      @NotNull User targetUser, UUID owningOrgUnitId) {
    Set<UUID> memberOrgUnitIds = collectMemberOrgUnitIds(targetUser);
    if (memberOrgUnitIds.isEmpty()) {
      if (owningOrgUnitId == null) {
        return null;
      }
      throw new BadRequestException(
          "Selected owner org unit is not a membership of the target user");
    }
    return resolveStampedOrgUnit(memberOrgUnitIds, owningOrgUnitId);
  }

  /**
   * Validates and resolves the target org unit for an explicit <b>reassignment</b> of an existing
   * aggregate's owning org unit (REQ-ORG-018 / ADR-0050 — the mission Verwaltung "Zugeordnete
   * Einheit" control). Unlike {@link #resolveOrgUnitForPickerOutputNullable(User, UUID)} this
   * carries <em>no</em> auto-stamp or home-Staffel fallback: the caller picks an explicit target
   * and it is accepted only when it lies within their assignable scope.
   *
   * <p>Permission matrix (the orthogonal second gate on top of the per-aggregate write gate the
   * controller already enforces, e.g. {@code MissionSecurityService.canChangeOwner}):
   *
   * <ul>
   *   <li><b>Admin</b> — any existing org unit, or {@code null} (ownerless), in any direction.
   *   <li><b>Non-admin</b> — a non-null target must be one of the caller's DIRECT memberships OR an
   *       org unit they may edit ({@link AccessGateService#canEditOrgUnit(UUID)}, cascade-aware for
   *       a Bereichsleitung/OL); the same accepted set as the create-on-behalf picker ({@code
   *       resolveStampedOrgUnit}). A {@code null} (ownerless) target is allowed only for a
   *       membershipless leadership caller — mirroring who may <em>create</em> an ownerless mission
   *       (ADR-0004) — so a plain member cannot silently widen a mission to public-leadership
   *       scope.
   * </ul>
   *
   * @param targetOrgUnitId the picker-supplied target org-unit id, or {@code null} for ownerless.
   * @return the resolved managed {@link OrgUnit}, or {@code null} for an ownerless target.
   * @throws org.springframework.security.access.AccessDeniedException when the caller may not
   *     assign to the requested target.
   * @throws BadRequestException when a non-null target id does not resolve to a known org unit.
   */
  @org.jetbrains.annotations.Nullable
  public OrgUnit resolveReassignTargetOrgUnit(
      @org.jetbrains.annotations.Nullable UUID targetOrgUnitId) {
    boolean admin = authHelper.isAdmin();
    if (targetOrgUnitId == null) {
      // Ownerless target: an admin always, otherwise only a membershipless leadership caller. The
      // member lookup is short-circuited for admins.
      if (admin || requestScopeResolver.currentMemberOrgUnitIds().isEmpty()) {
        return null;
      }
      throw new org.springframework.security.access.AccessDeniedException(
          "Only an admin or a membershipless leadership user may make an aggregate ownerless");
    }
    // Non-null target: an admin may assign anywhere; a non-admin only to a direct membership or a
    // unit within their editable (cascade-aware) scope. The `!admin` short-circuit keeps the admin
    // path off the member lookup entirely.
    if (!admin
        && !requestScopeResolver.currentMemberOrgUnitIds().contains(targetOrgUnitId)
        && !accessGateService.canEditOrgUnit(targetOrgUnitId)) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Target org unit is neither a membership of the caller nor within their editable scope");
    }
    return orgUnitRepository
        .findById(targetOrgUnitId)
        .orElseThrow(
            () -> new BadRequestException("owningOrgUnitId does not resolve to a known org unit"));
  }

  /**
   * Collects the distinct org-unit ids {@code targetUser} belongs to from the single authoritative
   * {@code org_unit_membership} source (Staffel and SK rows alike). Insertion order is preserved
   * via {@link LinkedHashSet} so the single-membership auto-stamp in {@link #resolveStampedOrgUnit}
   * is deterministic.
   *
   * @param targetUser the user whose memberships to read; never {@code null}.
   * @return the (possibly empty) set of org-unit ids the user is a member of.
   */
  @NotNull
  private Set<UUID> collectMemberOrgUnitIds(@NotNull User targetUser) {
    Set<UUID> memberOrgUnitIds = new LinkedHashSet<>();
    for (OrgUnitMembership m : orgUnitMembershipRepository.findAllByIdUserId(targetUser.getId())) {
      memberOrgUnitIds.add(m.getId().getOrgUnitId());
    }
    return memberOrgUnitIds;
  }

  /**
   * Applies the §5.5.1 picker-output matrix for a user known to have at least one membership, then
   * resolves the chosen id to its concrete {@link OrgUnit} subtype (Staffel via {@link
   * SquadronRepository}, Spezialkommando via {@link SpecialCommandRepository}, and — epic #692
   * Phase 4 / REQ-ORG-016 — a {@code BEREICH} / {@code ORGANISATIONSLEITUNG} owner via the
   * polymorphic {@link OrgUnitRepository}). Shared tail of {@link
   * #resolveOrgUnitForPickerOutput(User, UUID)} and {@link
   * #resolveOrgUnitForPickerOutputNullable(User, UUID)} — the empty-membership branch differs
   * between the two callers and is handled by each before delegating here.
   *
   * <p>The auto-stamp ({@code owningOrgUnitId == null}) and {@code >1 → force a choice} rules stay
   * keyed on the target user's DIRECT memberships, so a leader's default owner is their own
   * Bereich/OL and ordinary-member stamping is unchanged. An explicit pick is accepted when it is a
   * DIRECT membership <em>or</em> an org unit the current caller may edit ({@link
   * AccessGateService#canEditOrgUnit(UUID)}) — the cascade-aware create-on-behalf widening.
   *
   * @param memberOrgUnitIds the target user's non-empty DIRECT membership set.
   * @param owningOrgUnitId the picker-supplied org unit id, or {@code null} for the auto-stamp
   *     path.
   * @return the resolved {@link OrgUnit}; never {@code null}.
   * @throws BadRequestException on a pick that is neither a direct membership nor within the
   *     caller's editable scope, a &gt;1-membership {@code null} choice, or a resolved id that no
   *     longer exists / is not an ownable kind.
   */
  @NotNull
  private OrgUnit resolveStampedOrgUnit(@NotNull Set<UUID> memberOrgUnitIds, UUID owningOrgUnitId) {
    UUID stampedOrgUnitId;
    if (owningOrgUnitId == null) {
      if (memberOrgUnitIds.size() == 1) {
        stampedOrgUnitId = memberOrgUnitIds.iterator().next();
      } else {
        // REQ-ORG-017 "pin, else choose": honour an active-context pin onto one of the TARGET
        // user's
        // own org units (the self-service create path where caller == target) so a member who has
        // already pinned a Staffel via the switcher need not re-pick it on the create form;
        // otherwise force an explicit choice. The pin is only honoured when it is one of the
        // target's
        // memberships, so an admin's foreign pin on an on-behalf create still falls through to 400.
        Optional<UUID> pinned = requestScopeResolver.readActiveSquadronFromHeader();
        if (pinned.isPresent() && memberOrgUnitIds.contains(pinned.get())) {
          stampedOrgUnitId = pinned.get();
        } else {
          throw new BadRequestException(
              "User belongs to multiple org units; owningOrgUnitId is required");
        }
      }
    } else {
      // Epic #692 Phase 4 (REQ-ORG-016): a picker choice is valid when it is one of the TARGET
      // user's DIRECT memberships (the historical contract) OR an org unit the CURRENT CALLER may
      // edit ({@link AccessGateService#canEditOrgUnit(UUID)}, cascade-aware since Phase 3). The
      // create-on-behalf widening: a Bereichsleitung/OL leader may stamp a subordinate Staffel/SK
      // (or its own Bereich/OL) they oversee.
      //
      // Note the gate keys canEditOrgUnit on the CALLER, while memberOrgUnitIds is the TARGET
      // user's
      // set. When caller == targetUser (every self-service create path) the two coincide, so an
      // ordinary member's accepted set is exactly their own memberships and stamping is
      // byte-identical
      // to the pre-Phase-4 gate. They DIVERGE only on the two create-on-behalf paths where a caller
      // stamps another user's row — inventory book-out/transfer and refinery store — and there the
      // accepted set is the union of (target's memberships) and (caller's editable scope), by
      // design:
      // a leader may place the recipient's row in any unit the leader already controls. This never
      // widens what the CALLER can see (canEditOrgUnit only admits units already in the caller's
      // scope)
      // and REQ-ORG-011 owner-escape keeps the recipient's own visibility of the row.
      if (!memberOrgUnitIds.contains(owningOrgUnitId)
          && !accessGateService.canEditOrgUnit(owningOrgUnitId)) {
        throw new BadRequestException(
            "Selected owner org unit is neither a membership of the target user nor within the"
                + " caller's editable scope");
      }
      stampedOrgUnitId = owningOrgUnitId;
    }

    // Resolve to the concrete subtype. Staffel-side: SquadronRepository (discriminator filter
    // matches). SK-side: SpecialCommandRepository. Epic #692 Phase 4 (REQ-ORG-016): a Bereich or
    // Organisationsleitung may own an aggregate directly, so a non-Squadron/non-SK id falls through
    // to the polymorphic OrgUnitRepository and is accepted iff it resolves to a BEREICH / OL row.
    // The picker output was validated above, so any other miss is a hard contract violation (400).
    Optional<Squadron> sq = squadronRepository.findById(stampedOrgUnitId);
    if (sq.isPresent()) {
      return sq.get();
    }
    OrgUnit specialCommand =
        specialCommandRepository.findById(stampedOrgUnitId).map(s -> (OrgUnit) s).orElse(null);
    if (specialCommand != null) {
      return specialCommand;
    }
    return orgUnitRepository
        .findById(stampedOrgUnitId)
        .filter(
            ou ->
                ou.getKind() == OrgUnitKind.BEREICH
                    || ou.getKind() == OrgUnitKind.ORGANISATIONSLEITUNG)
        .orElseThrow(
            () ->
                new BadRequestException(
                    "Picked owner org unit no longer resolves — repository miss"));
  }
}
