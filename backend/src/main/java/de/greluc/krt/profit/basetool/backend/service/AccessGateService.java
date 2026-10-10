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

import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code can*} authorization gates behind every {@code @PreAuthorize} on the org-unit-scoped
 * aggregates, evaluated against the same scope as {@link RequestScopeResolver} so per-row checks
 * match the scoped lists.
 *
 * <p>Invoked from SpEL through the {@link OwnerScopeService} facade. Read-only transactional.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccessGateService {

  private final RequestScopeResolver requestScopeResolver;
  private final AuthHelperService authHelper;
  private final ShipRepository shipRepository;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;

  /**
   * Checks whether the caller may see data owned by the org unit {@code squadronId} (Staffel or
   * Spezialkommando), via {@link RequestScopeResolver#currentScopePredicate()}.
   *
   * <ul>
   *   <li>Admin without a pin: every org unit.
   *   <li>Pinned caller: only the pinned org unit.
   *   <li>Non-admin without a pin: every org unit they are a member of.
   * </ul>
   *
   * @param squadronId the org-unit id whose data the caller wants to read; never {@code null}
   * @return {@code true} iff the caller may see the org unit's data
   */
  public boolean canSeeSquadron(@NotNull UUID squadronId) {
    return requestScopeResolver.currentScopePredicate().permits(squadronId);
  }

  /**
   * Alias for {@link #canSeeSquadron(UUID)} with an org-unit name.
   *
   * @param orgUnitId the org-unit id whose data the caller wants to read; never {@code null}
   * @return {@code true} iff the caller may see the org unit's data
   */
  public boolean canSeeOrgUnit(@NotNull UUID orgUnitId) {
    return canSeeSquadron(orgUnitId);
  }

  /**
   * Checks whether the caller may write data owned by {@code squadronId}; currently the same rule
   * as {@link #canSeeSquadron(UUID)}.
   *
   * @param squadronId the org unit whose data the caller wants to write; never {@code null}
   * @return {@code true} iff the caller may write the org unit's data
   */
  public boolean canEditSquadron(@NotNull UUID squadronId) {
    return canSeeSquadron(squadronId);
  }

  /**
   * Alias for {@link #canEditSquadron(UUID)} with an org-unit name.
   *
   * @param orgUnitId the org-unit id whose data the caller wants to write; never {@code null}
   * @return {@code true} iff the caller may write the org unit's data
   */
  public boolean canEditOrgUnit(@NotNull UUID orgUnitId) {
    return canEditSquadron(orgUnitId);
  }

  /**
   * Checks whether the caller holds the contextual authority {@code (roleName, orgUnitId)} ({@link
   * de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority}); admins always
   * pass.
   *
   * @param orgUnitId the org unit the caller wants to act on; never {@code null}
   * @param roleName the role to check (e.g. {@code "LOGISTICIAN"}); never {@code null}
   * @return {@code true} iff the caller is an admin or holds the contextual authority
   */
  public boolean hasRoleInOrgUnit(@NotNull UUID orgUnitId, @NotNull String roleName) {
    if (authHelper.isAdmin()) {
      return true;
    }
    Optional<Authentication> authentication = authHelper.currentAuthentication();
    if (authentication.isEmpty()
        || !authentication.get().isAuthenticated()
        || authentication.get().getAuthorities() == null) {
      return false;
    }
    OrgUnitContextualAuthority target = new OrgUnitContextualAuthority(roleName, orgUnitId);
    for (GrantedAuthority a : authentication.get().getAuthorities()) {
      if (a instanceof OrgUnitContextualAuthority ctx && ctx.equals(target)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Access check for a personal row without an owning org unit: allowed for an admin without an
   * active pin and for the row's own owner.
   *
   * @param owner the row's per-user owner; {@code null} denies all non-admin access
   * @return {@code true} iff the caller may see or edit the row
   */
  private boolean canAccessOwnerlessPersonalRow(@Nullable User owner) {
    if (authHelper.isAdmin() && requestScopeResolver.readActiveSquadronFromHeader().isEmpty()) {
      return true;
    }
    return isCurrentUserOwner(owner);
  }

  /**
   * Checks whether the caller is the row's per-user owner, comparing {@link
   * AuthHelperService#currentUserId()} with {@code owner.getId()}.
   *
   * @param owner the row's per-user owner; {@code null} never matches
   * @return {@code true} iff the caller is that owner
   */
  private boolean isCurrentUserOwner(@Nullable User owner) {
    return owner != null
        && owner.getId() != null
        && authHelper.currentUserId().map(uid -> uid.equals(owner.getId())).orElse(false);
  }

  /**
   * Shared owner-escape check for the personal aggregates (REQ-ORG-011), in order.
   *
   * <ul>
   *   <li>the per-user owner may always access the row ({@link #isCurrentUserOwner(User)});
   *   <li>an ownerless row defers to {@link #canAccessOwnerlessPersonalRow(User)};
   *   <li>otherwise {@link #canSeeSquadron(UUID)} or, with {@code edit}, {@link
   *       #canEditSquadron(UUID)}.
   * </ul>
   *
   * @param row the resolved row; empty denies access
   * @param owner extracts the row's per-user owner
   * @param orgUnit extracts the row's owning org unit; {@code null} marks an ownerless row
   * @param edit {@code true} for the edit check, {@code false} for the read check
   * @param <T> the row's entity type
   * @return {@code true} iff the caller may access the row
   */
  private <T> boolean permitsRow(
      @NotNull Optional<T> row,
      Function<T, User> owner,
      Function<T, OrgUnit> orgUnit,
      boolean edit) {
    return row.map(r -> permitsOwnedRow(owner.apply(r), orgUnit.apply(r), edit)).orElse(false);
  }

  /**
   * Checks whether the caller may access a row with the given per-user owner and owning org unit:
   * the owner escape (REQ-ORG-011), then the ownerless rule, then {@link #canSeeSquadron(UUID)} or,
   * with {@code edit}, {@link #canEditSquadron(UUID)}.
   *
   * @param owner the row's per-user owner, or {@code null}
   * @param orgUnit the row's owning org unit; {@code null} marks an ownerless row
   * @param edit {@code true} for the edit check, {@code false} for the read check
   * @return {@code true} iff the caller may access the row
   */
  public boolean permitsOwnedRow(@Nullable User owner, @Nullable OrgUnit orgUnit, boolean edit) {
    return isCurrentUserOwner(owner)
        || (orgUnit == null
            ? canAccessOwnerlessPersonalRow(owner)
            : (edit ? canEditSquadron(orgUnit.getId()) : canSeeSquadron(orgUnit.getId())));
  }

  /**
   * Coarse pre-check for acting on another member's rows: admin, then self, then whether {@link
   * #canSeeSquadron(UUID)} or, with {@code edit}, {@link #canEditSquadron(UUID)} accepts any of the
   * member's memberships. It does not bound individual rows.
   *
   * @param targetUserId the member being acted upon; never {@code null}
   * @param edit {@code true} for the write check, {@code false} for the read check
   * @return {@code true} iff the caller shares at least one in-scope org unit with the member
   */
  public boolean canActOnTargetUser(@NotNull UUID targetUserId, boolean edit) {
    return canActOnTargetUserScoped(
        targetUserId, edit ? this::canEditSquadron : this::canSeeSquadron);
  }

  /**
   * Shared on-behalf check: admin, then self, then whether {@code unitScope} accepts any of the
   * target user's memberships. It does not bound individual rows; callers must scope per row.
   *
   * @param targetUserId the user being acted upon; never {@code null}
   * @param unitScope the per-unit scope check to apply; never {@code null}
   * @return {@code true} iff the caller shares at least one in-scope org unit with the target
   */
  private boolean canActOnTargetUserScoped(
      @NotNull UUID targetUserId, @NotNull Predicate<UUID> unitScope) {
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
   * Checks whether the caller may read ship {@code shipId}, applying the owner escape
   * (REQ-ORG-011), then the ownerless rule, then {@link #canSeeSquadron(UUID)}. Unknown ids return
   * {@code false}.
   *
   * @param shipId ship to inspect; never {@code null}
   * @return {@code true} iff the caller may read the ship
   */
  public boolean canSeeShip(@NotNull UUID shipId) {
    return permitsRow(
        shipRepository.findById(shipId), Ship::getOwner, Ship::getOwningOrgUnit, false);
  }

  /**
   * Checks whether the caller may edit ship {@code shipId}, applying the owner escape
   * (REQ-ORG-011), then the ownerless rule, then {@link #canEditSquadron(UUID)}. Unknown ids return
   * {@code false}.
   *
   * @param shipId ship to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the ship
   */
  public boolean canEditShip(@NotNull UUID shipId) {
    return permitsRow(
        shipRepository.findById(shipId), Ship::getOwner, Ship::getOwningOrgUnit, true);
  }
}
