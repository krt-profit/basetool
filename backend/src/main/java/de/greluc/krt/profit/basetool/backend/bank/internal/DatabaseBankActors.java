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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActors;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.MemberRoleCodes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the {@link BankActor} from the database (ADR-0352): {@link MemberRoleCodes} with the role
 * hierarchy applied, the membership rows with their officer reach, and the {@code
 * bank_account_grant} rows, in four reads.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DatabaseBankActors implements BankActors {

  private final UserRepository userRepository;
  private final OrgUnitMembershipRepository membershipRepository;
  private final OrgUnitCascadeService orgUnitCascadeService;
  private final BankAccountGrantRepository grantRepository;
  private final RoleHierarchy roleHierarchy;

  @Override
  @NotNull
  public BankActor of(@Nullable UUID userId) {
    return userId == null ? BankActor.NOBODY : load(userId, Set.of());
  }

  @Override
  @NotNull
  public BankActor forExchange(@NotNull UUID userId) {
    return load(userId, Set.of(Roles.ADMIN));
  }

  /**
   * Reads the member and assembles the actor.
   *
   * @param userId the member
   * @param dropped the stored role codes that do not count on this path
   * @return the actor; {@link BankActor#NOBODY} for an unknown member
   */
  @NotNull
  private BankActor load(@NotNull UUID userId, @NotNull Set<String> dropped) {
    Optional<User> user = userRepository.findById(userId);
    if (user.isEmpty()) {
      return BankActor.NOBODY;
    }
    List<OrgUnitMembership> memberships = membershipRepository.findAllByIdUserId(userId);
    Set<String> stored =
        MemberRoleCodes.of(user.get(), memberships).stream()
            .filter(code -> !dropped.contains(code))
            .collect(Collectors.toUnmodifiableSet());
    Map<UUID, BankActor.Grant> grants =
        grantRepository.findByUserId(userId).stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    grant -> grant.getId().getAccountId(),
                    grant ->
                        new BankActor.Grant(
                            grant.isCanDeposit(), grant.isCanWithdraw(), grant.isCanTransfer())));
    return new BankActor(
        userId,
        reachable(stored),
        memberships.stream()
            .map(m -> new BankActor.Seat(m.getId().getOrgUnitId(), m.getRole()))
            .toList(),
        orgUnitCascadeService.officerReach(memberships),
        grants);
  }

  /**
   * Applies the role hierarchy, so a Bankleitung also reaches {@code BANK_EMPLOYEE}.
   *
   * @param stored the stored role codes
   * @return every reachable role code
   */
  @NotNull
  private Set<String> reachable(@NotNull Set<String> stored) {
    List<GrantedAuthority> authorities =
        stored.stream()
            .<GrantedAuthority>map(code -> new SimpleGrantedAuthority(Roles.authority(code)))
            .toList();
    return roleHierarchy.getReachableGrantedAuthorities(authorities).stream()
        .map(GrantedAuthority::getAuthority)
        .filter(authority -> authority.startsWith(Roles.ROLE_PREFIX))
        .map(authority -> authority.substring(Roles.ROLE_PREFIX.length()))
        .collect(Collectors.toUnmodifiableSet());
  }
}
