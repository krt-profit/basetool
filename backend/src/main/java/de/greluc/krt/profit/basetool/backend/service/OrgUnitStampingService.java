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
import de.greluc.krt.profit.basetool.backend.exception.OwnerOrgUnitRequiredException;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
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
 * Create-time owner stamping behind {@link OwnerScopeService}: resolves the {@link OrgUnit} a new
 * aggregate is stamped on and validates an explicit owning-org-unit reassignment (REQ-ORG-018).
 * Read-only; the caller persists the stamped entity in its own transaction.
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
  public Squadron resolveSquadronForPickerOutput(@NotNull User targetUser, UUID owningOrgUnitId) {
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
        Optional<UUID> pinned = requestScopeResolver.readActiveSquadronFromHeader();
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
        .orElseThrow(
            () ->
                new BadRequestException(
                    "Spezialkommando ownership of this aggregate is not yet supported"));
  }

  /**
   * Resolves the {@link OrgUnit} a newly created aggregate is stamped on from the optional
   * owner-picker output, accepting every org-unit kind.
   *
   * <p>Without a pick, a single direct membership is auto-stamped. An explicit pick is honoured
   * when it is a direct membership of the target user or an org unit the caller may edit ({@link
   * AccessGateService#canEditOrgUnit(UUID)}, REQ-ORG-016).
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
  public OrgUnit resolveOrgUnitForPickerOutput(@NotNull User targetUser, UUID owningOrgUnitId) {
    Set<UUID> memberOrgUnitIds = collectMemberOrgUnitIds(targetUser);
    if (memberOrgUnitIds.isEmpty()) {
      throw new BadRequestException(
          "User has no org-unit membership — cannot stamp an aggregate owner");
    }
    return resolveStampedOrgUnit(memberOrgUnitIds, owningOrgUnitId);
  }

  /**
   * Variant of {@link #resolveOrgUnitForPickerOutput(User, UUID)} for the ownerless-personal
   * aggregates (ship, refinery order, inventory item): a membershipless user without a pick
   * resolves to {@code null} instead of a 400. Every other case behaves identically.
   *
   * @param targetUser the user whose memberships gate the picker output; never {@code null}.
   * @param owningOrgUnitId the picker-supplied org unit id, or {@code null} when the picker was not
   *     used.
   * @return the resolved {@link OrgUnit}, or {@code null} when {@code targetUser} has no membership
   *     and supplied no explicit choice.
   * @throws BadRequestException for every other rejection, including a membershipless user who
   *     supplied a pick.
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
   * Validates and resolves the target of an explicit reassignment of an aggregate's owning org unit
   * (REQ-ORG-018), without any auto-stamp fallback.
   *
   * <p>An admin may pick any org unit or {@code null}. A non-admin may pick a direct membership or
   * an org unit they may edit ({@link AccessGateService#canEditOrgUnit(UUID)}), and {@code null}
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
        && !accessGateService.canEditOrgUnit(targetOrgUnitId)) {
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
   * Validates the picker output for a user with at least one membership and loads the chosen id as
   * its concrete, unproxied {@link OrgUnit} subtype.
   *
   * <p>Without a pick, a single direct membership is auto-stamped. An explicit pick is accepted
   * when it is a direct membership or an org unit the caller may edit ({@link
   * AccessGateService#canEditOrgUnit(UUID)}).
   *
   * @param memberOrgUnitIds the target user's non-empty DIRECT membership set.
   * @param owningOrgUnitId the picker-supplied org unit id, or {@code null} for the auto-stamp
   *     path.
   * @return the resolved {@link OrgUnit}; never {@code null}.
   * @throws BadRequestException on a pick that is neither a direct membership nor within the
   *     caller's editable scope, or a resolved id that no longer exists / is not an ownable kind.
   * @throws OwnerOrgUnitRequiredException on a &gt;1-membership {@code null} choice with no
   *     honourable active-context pin (REQ-ORG-023).
   */
  @NotNull
  private OrgUnit resolveStampedOrgUnit(@NotNull Set<UUID> memberOrgUnitIds, UUID owningOrgUnitId) {
    UUID stampedOrgUnitId;
    if (owningOrgUnitId == null) {
      if (memberOrgUnitIds.size() == 1) {
        stampedOrgUnitId = memberOrgUnitIds.iterator().next();
      } else {
        Optional<UUID> pinned = requestScopeResolver.readActiveSquadronFromHeader();
        if (pinned.isPresent() && memberOrgUnitIds.contains(pinned.get())) {
          stampedOrgUnitId = pinned.get();
        } else {
          throw new OwnerOrgUnitRequiredException(
              "User belongs to multiple org units; owningOrgUnitId is required");
        }
      }
    } else {
      if (!memberOrgUnitIds.contains(owningOrgUnitId)
          && !accessGateService.canEditOrgUnit(owningOrgUnitId)) {
        throw new BadRequestException(
            "Selected owner org unit is neither a membership of the target user nor within the"
                + " caller's editable scope");
      }
      stampedOrgUnitId = owningOrgUnitId;
    }

    return orgUnitRepository
        .findById(stampedOrgUnitId)
        .map(ou -> Hibernate.unproxy(ou, OrgUnit.class))
        .orElseThrow(
            () ->
                new BadRequestException(
                    "Picked owner org unit no longer resolves — repository miss"));
  }
}
