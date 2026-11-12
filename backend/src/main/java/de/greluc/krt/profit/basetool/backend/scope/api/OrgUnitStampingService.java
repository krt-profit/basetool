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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitSeatEligibility;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create-time owner stamping of the scope kernel: resolves the {@link OrgUnit} a new aggregate is
 * stamped on and validates an explicit owning-org-unit reassignment (REQ-ORG-018). Read-only; the
 * caller persists the stamped entity in its own transaction.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgUnitStampingService {

  private final RequestScopeResolver requestScopeResolver;
  private final AccessGateService accessGateService;
  private final AuthHelperService authHelper;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final OrgUnitSeatEligibility seatEligibility;

  /**
   * Resolves the {@link Squadron} a newly created aggregate is stamped on from the optional
   * owner-picker output.
   *
   * <p>Without a pick, a single membership is auto-stamped and several memberships require a pick.
   * A pick must be one of the target user's memberships and must not be a Spezialkommando.
   *
   * @param targetUser the user the new aggregate belongs to; never {@code null}.
   * @param owningOrgUnitId the picker output from the form, or {@code null} when the picker was not
   *     used.
   * @return the Squadron whose stock / aggregate list this row should join; never {@code null}.
   * @throws BadRequestException when the user has no membership, the pick is foreign, several
   *     memberships lack a pick, or the resolved org unit is a Spezialkommando.
   */
  public Squadron resolveSquadronForPickerOutput(
      @NotNull User targetUser, @Nullable UUID owningOrgUnitId) {
    Set<UUID> memberOrgUnitIds = new LinkedHashSet<>();
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
        Optional<UUID> pinned = requestScopeResolver.currentPin();
        if (pinned.isPresent() && memberOrgUnitIds.contains(pinned.get())) {
          stampedOrgUnitId = pinned.get();
        } else {
          throw new OwnerOrgUnitRequiredException(
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

    return orgUnitRepository
        .findById(stampedOrgUnitId)
        .map(ou -> Hibernate.unproxy(ou, OrgUnit.class))
        .filter(Squadron.class::isInstance)
        .map(Squadron.class::cast)
        .orElseThrow(() ->
            new BadRequestException(
                "Spezialkommando ownership of this aggregate is not yet supported"));
  }

  /**
   * Resolves the {@link OrgUnit} a newly created aggregate is stamped on from the optional
   * owner-picker output, accepting every org-unit kind.
   *
   * <p>Without a pick, a single direct membership is auto-stamped. An explicit pick is honoured
   * when it is a direct membership of the target user or an org unit the caller may edit ({@link
   * AccessGateService#canEditSquadron(UUID)}, REQ-ORG-016).
   *
   * @param targetUser the user whose memberships gate the picker output validation; never {@code
   *     null}.
   * @param owningOrgUnitId the picker-supplied org unit id; {@code null} triggers the auto-stamp
   *     path when the user has exactly one membership.
   * @return the resolved {@link OrgUnit}; never {@code null}.
   * @throws BadRequestException on 0 memberships, or a pick that is neither a direct membership of
   *     the target user nor within the caller's editable scope.
   * @throws OwnerOrgUnitRequiredException when several memberships lack both a pick and an
   *     honourable active-context pin (REQ-ORG-023).
   */
  public OrgUnit resolveOrgUnitForPickerOutput(
      @NotNull User targetUser, @Nullable UUID owningOrgUnitId) {
    Set<UUID> memberOrgUnitIds = collectMemberOrgUnitIds(targetUser);
    if (memberOrgUnitIds.isEmpty()) {
      throw new BadRequestException(
          "User has no org-unit membership — cannot stamp an aggregate owner");
    }
    return resolveStampedOrgUnit(memberOrgUnitIds, owningOrgUnitId, pinAmong(memberOrgUnitIds));
  }

  /**
   * Resolves the owning org unit of an aggregate a connected application creates for a member
   * (REQ-XCH-017): the member's only stampable unit ({@link #collectStampableOrgUnitIds(User)}), or
   * none when the member has none or several, because a client names no unit and the member assigns
   * it later in the web.
   *
   * @param targetUser the member the aggregate is created for
   * @return the member's only stampable org unit, or {@code null}
   */
  @Nullable
  public OrgUnit resolveOrgUnitForClientCreate(@NotNull User targetUser) {
    Set<UUID> stampableOrgUnitIds = collectStampableOrgUnitIds(targetUser);
    return stampableOrgUnitIds.size() == 1 ? load(stampableOrgUnitIds.iterator().next()) : null;
  }

  /**
   * Resolves the owning org unit of the ships a hangar import creates (REQ-HANGAR-014): the
   * active-context pin when it is one of the member's stampable units ({@link
   * #collectStampableOrgUnitIds(User)}), else the member's only stampable unit, else none, because
   * an import names no unit and the member assigns it later with „Einheit ändern".
   *
   * @param targetUser the member the ships are imported for
   * @return the resolved org unit, or {@code null} when none applies
   */
  @Nullable
  public OrgUnit resolveOrgUnitForImport(@NotNull User targetUser) {
    Set<UUID> stampableOrgUnitIds = collectStampableOrgUnitIds(targetUser);
    UUID pinnedMembership = pinAmong(stampableOrgUnitIds);
    if (pinnedMembership != null) {
      return load(pinnedMembership);
    }
    return stampableOrgUnitIds.size() == 1 ? load(stampableOrgUnitIds.iterator().next()) : null;
  }

  /**
   * Resolves the owning org unit of a personal row an admin creates on a member's behalf
   * (REQ-HANGAR-011): a pick must be one of the member's direct memberships; without one, the
   * member's only stampable unit ({@link #collectStampableOrgUnitIds(User)}) is stamped and a
   * member without one keeps the row without a unit. The admin's own active context never decides
   * it.
   *
   * @param member the member the row is created for
   * @param owningOrgUnitId the picked org unit, or {@code null}
   * @return the resolved org unit, or {@code null} for a member without a stampable unit
   * @throws BadRequestException when the pick is not a direct membership of the member
   * @throws OwnerOrgUnitRequiredException when the member has several stampable units and nothing
   *     is picked
   */
  @Nullable
  public OrgUnit resolveOrgUnitForAdminOnBehalf(
      @NotNull User member, @Nullable UUID owningOrgUnitId) {
    if (owningOrgUnitId != null) {
      return resolveOwnerMembershipTarget(member, owningOrgUnitId);
    }
    return resolveStampedOrgUnitOrNone(collectStampableOrgUnitIds(member), null, null);
  }

  /**
   * Resolves the target of a later org-unit change of an owner's aggregate (REQ-HANGAR-009): one of
   * the owner's direct memberships of any kind on an active unit, or none; a deactivated unit takes
   * no row (REQ-ORG-032). Neither the caller's scope nor an active-context pin widens it.
   *
   * @param owner the aggregate's owner
   * @param targetOrgUnitId the chosen org unit, or {@code null} for none
   * @return the chosen org unit as its concrete subtype, or {@code null} for none
   * @throws BadRequestException when the target is not a direct membership of the owner or is
   *     deactivated
   */
  @Nullable
  public OrgUnit resolveOwnerMembershipTarget(@NotNull User owner, @Nullable UUID targetOrgUnitId) {
    if (targetOrgUnitId == null) {
      return null;
    }
    if (!collectMemberOrgUnitIds(owner).contains(targetOrgUnitId)) {
      throw new BadRequestException("Target org unit is not a direct membership of the owner");
    }
    OrgUnit target =
        orgUnitRepository
            .findById(targetOrgUnitId)
            .map(ou -> Hibernate.unproxy(ou, OrgUnit.class))
            .orElseThrow(() -> new BadRequestException("Target org unit does not exist"));
    if (!target.isActive()) {
      throw new BadRequestException("Target org unit is deactivated");
    }
    return target;
  }

  /**
   * Variant of {@link #resolveOrgUnitForPickerOutput(User, UUID)} for the personal aggregates that
   * may stay without an owning unit (ship, refinery order, inventory item), over the owner's
   * stampable units ({@link #collectStampableOrgUnitIds(User)}): without a pick, the honoured pin
   * is stamped only when it is one of them (REQ-ORG-032), so a personal row never lands in a unit
   * its owner does not belong to. An owner without a stampable unit and without a pick resolves to
   * {@code null} instead of a 400.
   *
   * @param targetUser the user the personal row belongs to; never {@code null}.
   * @param owningOrgUnitId the picker-supplied org unit id, or {@code null} when the picker was not
   *     used.
   * @return the resolved {@link OrgUnit}, or {@code null} when {@code targetUser} has no stampable
   *     unit and no pick names a unit.
   * @throws BadRequestException for every other rejection
   */
  @Nullable
  public OrgUnit resolveOrgUnitForPickerOutputNullable(
      @NotNull User targetUser, @Nullable UUID owningOrgUnitId) {
    Set<UUID> stampableOrgUnitIds = collectStampableOrgUnitIds(targetUser);
    return resolveStampedOrgUnitOrNone(
        stampableOrgUnitIds, owningOrgUnitId, pinAmong(stampableOrgUnitIds));
  }

  /**
   * Resolves the owning org unit of a mission or operation a caller creates (REQ-ORG-032): without
   * a pick, the honoured pin is stamped, since every honoured pin is a unit the caller may edit, so
   * the new row lands in the selected unit; without a pin, the creator's only stampable unit
   * ({@link #collectStampableOrgUnitIds(User)}).
   *
   * @param creator the creating user, whose stampable units gate the picker output; never {@code
   *     null}
   * @param owningOrgUnitId the picker-supplied org unit id, or {@code null} when the picker was not
   *     used
   * @return the resolved {@link OrgUnit}, or {@code null} when the creator has no stampable unit
   *     and neither a pick nor a pin names a unit
   * @throws BadRequestException on a pick that is neither a stampable unit of the creator nor
   *     within the caller's editable scope
   * @throws OwnerOrgUnitRequiredException when several stampable units lack both a pick and a pin
   *     (REQ-ORG-023)
   */
  @Nullable
  public OrgUnit resolveOrgUnitForUnitOwnedAggregate(
      @NotNull User creator, @Nullable UUID owningOrgUnitId) {
    return resolveStampedOrgUnitOrNone(
        collectStampableOrgUnitIds(creator),
        owningOrgUnitId,
        requestScopeResolver.currentPin().orElse(null));
  }

  /**
   * Validates and resolves the target of an explicit reassignment of an aggregate's owning org unit
   * (REQ-ORG-018), without any auto-stamp fallback.
   *
   * <p>An admin may pick any org unit or {@code null}. A non-admin may pick a direct membership or
   * an org unit they may edit ({@link AccessGateService#canEditSquadron(UUID)}), and {@code null}
   * only as a membershipless leadership caller.
   *
   * @param targetOrgUnitId the picker-supplied target org-unit id, or {@code null} for ownerless.
   * @return the resolved managed {@link OrgUnit}, or {@code null} for an ownerless target.
   * @throws org.springframework.security.access.AccessDeniedException when the caller may not
   *     assign to the requested target.
   * @throws BadRequestException when a non-null target id does not resolve to a known org unit.
   */
  @Nullable
  public OrgUnit resolveReassignTargetOrgUnit(@Nullable UUID targetOrgUnitId) {
    boolean admin = authHelper.isAdmin();
    if (targetOrgUnitId == null) {
      if (admin || requestScopeResolver.currentMemberOrgUnitIds().isEmpty()) {
        return null;
      }
      throw new AccessDeniedException(
          "Only an admin or a membershipless leadership user may make an aggregate ownerless");
    }
    if (!admin
        && !requestScopeResolver.currentMemberOrgUnitIds().contains(targetOrgUnitId)
        && !accessGateService.canEditSquadron(targetOrgUnitId)) {
      throw new AccessDeniedException(
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
   * Collects the units a row of {@code user} is stamped on without a pick (REQ-ORG-032,
   * REQ-SEC-015): their direct memberships on active units, and none while they lack the stored
   * {@code KRT Member} role. These are the direct memberships the owner picker offers.
   *
   * @param user the owner or creator of the new row; never {@code null}
   * @return the stampable org-unit ids, unordered; never {@code null}, possibly empty
   */
  @NotNull
  private Set<UUID> collectStampableOrgUnitIds(@NotNull User user) {
    Set<UUID> activeOrgUnitIds =
        orgUnitMembershipRepository.findActiveOrgUnitIdsByUserId(user.getId());
    if (activeOrgUnitIds.isEmpty() || !seatEligibility.holdsKrtMember(user.getId())) {
      return Set.of();
    }
    return activeOrgUnitIds;
  }

  /**
   * The honoured active-context pin when it is one of the given units of the target user.
   *
   * @param memberOrgUnitIds the target user's units a row may be stamped on
   * @return the pin, or {@code null} when none is honoured or it is not among the units
   */
  @Nullable
  private UUID pinAmong(@NotNull Set<UUID> memberOrgUnitIds) {
    return requestScopeResolver.currentPin().filter(memberOrgUnitIds::contains).orElse(null);
  }

  /**
   * {@link #resolveStampedOrgUnit} for the aggregates that may stay without an owning unit: {@code
   * null} when the user has no unit to stamp on and neither a pick nor a stampable pin names one.
   *
   * @param memberOrgUnitIds the target user's units a row may be stamped on
   * @param owningOrgUnitId the picker-supplied org unit id, or {@code null}
   * @param stampablePin the pin the caller's aggregate may be stamped on without a pick, or {@code
   *     null}
   * @return the resolved {@link OrgUnit}, or {@code null} for an ownerless row
   */
  @Nullable
  private OrgUnit resolveStampedOrgUnitOrNone(
      @NotNull Set<UUID> memberOrgUnitIds,
      @Nullable UUID owningOrgUnitId,
      @Nullable UUID stampablePin) {
    if (memberOrgUnitIds.isEmpty() && owningOrgUnitId == null && stampablePin == null) {
      return null;
    }
    return resolveStampedOrgUnit(memberOrgUnitIds, owningOrgUnitId, stampablePin);
  }

  /**
   * Validates the picker output and loads the chosen id as its concrete, unproxied {@link OrgUnit}
   * subtype.
   *
   * <p>An explicit pick is accepted when it is one of {@code memberOrgUnitIds} or an org unit the
   * caller may edit ({@link AccessGateService#canEditSquadron(UUID)}, REQ-ORG-016). Without a pick,
   * the stampable pin wins (REQ-ORG-032), else the only unit of {@code memberOrgUnitIds}.
   *
   * @param memberOrgUnitIds the target user's units a row may be stamped on: their stampable units
   *     ({@link #collectStampableOrgUnitIds(User)}), or their direct memberships for the strict
   *     resolvers.
   * @param owningOrgUnitId the picker-supplied org unit id, or {@code null} for the auto-stamp
   *     path.
   * @param stampablePin the pin a pick-less create is stamped on, or {@code null}
   * @return the resolved {@link OrgUnit}; never {@code null}.
   * @throws BadRequestException on a pick that is neither one of {@code memberOrgUnitIds} nor
   *     within the caller's editable scope, or a resolved id that no longer exists.
   * @throws OwnerOrgUnitRequiredException on a {@code null} choice with neither a stampable pin nor
   *     exactly one unit in {@code memberOrgUnitIds} (REQ-ORG-023).
   */
  @NotNull
  private OrgUnit resolveStampedOrgUnit(
      @NotNull Set<UUID> memberOrgUnitIds,
      @Nullable UUID owningOrgUnitId,
      @Nullable UUID stampablePin) {
    if (owningOrgUnitId != null) {
      if (!memberOrgUnitIds.contains(owningOrgUnitId)
          && !accessGateService.canEditSquadron(owningOrgUnitId)) {
        throw new BadRequestException(
            "Selected owner org unit is neither a membership of the target user nor within the"
                + " caller's editable scope");
      }
      return load(owningOrgUnitId);
    }
    if (stampablePin != null) {
      return load(stampablePin);
    }
    if (memberOrgUnitIds.size() == 1) {
      return load(memberOrgUnitIds.iterator().next());
    }
    throw new OwnerOrgUnitRequiredException(
        "User belongs to multiple org units; owningOrgUnitId is required");
  }

  /**
   * Loads a stamp target as its concrete, unproxied {@link OrgUnit} subtype.
   *
   * @param orgUnitId the stamp target
   * @return the org unit; never {@code null}
   * @throws BadRequestException when the id no longer resolves
   */
  @NotNull
  private OrgUnit load(@NotNull UUID orgUnitId) {
    return orgUnitRepository
        .findById(orgUnitId)
        .map(ou -> Hibernate.unproxy(ou, OrgUnit.class))
        .orElseThrow(() ->
            new BadRequestException("Picked owner org unit no longer resolves — repository miss"));
  }
}
