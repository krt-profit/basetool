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
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitSeatEligibility;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
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
 * caller persists the stamped entity in its own transaction, which an {@link
 * OwnerOrgUnitRequiredException} leaves committable, so a caller may catch it and go on.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = OwnerOrgUnitRequiredException.class)
public class OrgUnitStampingService {

  private final RequestScopeResolver requestScopeResolver;
  private final AccessGateService accessGateService;
  private final AuthHelperService authHelper;
  private final OrgUnitRepository orgUnitRepository;
  private final OrgUnitSeatEligibility seatEligibility;

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
    return stampableOrgUnitIds.size() == 1
        ? loadActive(stampableOrgUnitIds.iterator().next())
        : null;
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
      return loadActive(pinnedMembership);
    }
    return stampableOrgUnitIds.size() == 1
        ? loadActive(stampableOrgUnitIds.iterator().next())
        : null;
  }

  /**
   * Resolves the owning org unit of a personal row an admin creates on a member's behalf
   * (REQ-HANGAR-011): a pick must be one of the member's stampable units; without one, the member's
   * only stampable unit ({@link #collectStampableOrgUnitIds(User)}) is stamped and a member without
   * one keeps the row without a unit. The admin's own active context never decides it.
   *
   * @param member the member the row is created for
   * @param owningOrgUnitId the picked org unit, or {@code null}
   * @return the resolved org unit, or {@code null} for a member without a stampable unit
   * @throws BadRequestException when the pick is outside the member's stampable units
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
   * the owner's stampable units of any kind, or none (REQ-ORG-032).
   *
   * @param owner the aggregate's owner
   * @param targetOrgUnitId the chosen org unit, or {@code null} for none
   * @return the chosen org unit as its concrete subtype, or {@code null} for none
   * @throws BadRequestException when the target is outside the owner's stampable units
   */
  @Nullable
  public OrgUnit resolveOwnerMembershipTarget(@NotNull User owner, @Nullable UUID targetOrgUnitId) {
    if (targetOrgUnitId == null) {
      return null;
    }
    if (!collectStampableOrgUnitIds(owner).contains(targetOrgUnitId)) {
      throw new BadRequestException("error.org_unit.owner_target_not_allowed");
    }
    return loadActive(targetOrgUnitId);
  }

  /**
   * Resolves the owning unit of a ship, refinery order or inventory item from the owner picker.
   * Without a pick, an eligible pin or the owner's only stampable unit supplies the default.
   *
   * @param targetUser the user the personal row belongs to; never {@code null}.
   * @param owningOrgUnitId the picker-supplied org unit id, or {@code null} when the picker was not
   *     used.
   * @return the resolved {@link OrgUnit}, or {@code null} when {@code targetUser} has no stampable
   *     unit and no pick names a unit.
   * @throws BadRequestException when the pick is outside the owner's stampable units and the
   *     caller's editable scope, or the target is unknown or inactive
   * @throws OwnerOrgUnitRequiredException when several stampable units lack a pick or eligible pin
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
   *     within the caller's editable scope, or the target is unknown or inactive
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
   * <p>An admin may pick any active org unit or {@code null}. A non-admin may pick an active unit
   * in their membership or editable scope ({@link AccessGateService#canEditSquadron(UUID)}), and
   * {@code null} only as a membershipless leadership caller.
   *
   * @param targetOrgUnitId the picker-supplied target org-unit id, or {@code null} for ownerless.
   * @return the resolved managed {@link OrgUnit}, or {@code null} for an ownerless target.
   * @throws org.springframework.security.access.AccessDeniedException when the caller may not
   *     assign to the requested target.
   * @throws BadRequestException when a non-null target id names an unknown or inactive org unit.
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
    return loadActive(targetOrgUnitId);
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
    return seatEligibility.findStampableOrgUnitIds(user.getId());
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
   * @param memberOrgUnitIds the target user's stampable units
   * @param owningOrgUnitId the picker-supplied org unit id, or {@code null} for the auto-stamp
   *     path.
   * @param stampablePin the pin a pick-less create is stamped on, or {@code null}
   * @return the resolved {@link OrgUnit}; never {@code null}.
   * @throws BadRequestException on a pick that is neither one of {@code memberOrgUnitIds} nor
   *     within the caller's editable scope, or the target is unknown or inactive.
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
      return loadActive(owningOrgUnitId);
    }
    if (stampablePin != null) {
      return loadActive(stampablePin);
    }
    if (memberOrgUnitIds.size() == 1) {
      return loadActive(memberOrgUnitIds.iterator().next());
    }
    throw new OwnerOrgUnitRequiredException(
        "User belongs to multiple org units; owningOrgUnitId is required");
  }

  /**
   * Loads an active assignment target as its concrete, unproxied {@link OrgUnit} subtype.
   *
   * @param orgUnitId the assignment target
   * @return the org unit; never {@code null}
   * @throws BadRequestException when the id names an unknown or inactive org unit
   */
  @NotNull
  private OrgUnit loadActive(@NotNull UUID orgUnitId) {
    OrgUnit target =
        orgUnitRepository
            .findById(orgUnitId)
            .map(ou -> Hibernate.unproxy(ou, OrgUnit.class))
            .orElseThrow(() ->
                new BadRequestException("owningOrgUnitId does not resolve to a known org unit"));
    requireActiveAssignmentTarget(target);
    return target;
  }

  /**
   * Rejects an inactive owner when a new row inherits its unit from an existing aggregate.
   *
   * @param target the assigned unit, or {@code null} for ownerless
   * @throws BadRequestException when the assigned unit is inactive
   */
  public static void requireActiveAssignmentTarget(@Nullable OrgUnit target) {
    if (target != null && !target.isActive()) {
      throw new BadRequestException("error.org_unit.inactive_target");
    }
  }
}
