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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.app.api.SecurityConfig;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link DatabaseBankActors} reads the actor from the stored roles with the hierarchy applied; the
 * exchange path drops the Admin role before the hierarchy (REQ-XCH-009, ADR-0352).
 */
@ExtendWith(MockitoExtension.class)
class DatabaseBankActorsTest {

  @Mock private UserRepository userRepository;
  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private OrgUnitCascadeService orgUnitCascadeService;
  @Mock private BankAccountGrantRepository grantRepository;

  private DatabaseBankActors actors;
  private final UUID userId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    actors =
        new DatabaseBankActors(
            userRepository,
            membershipRepository,
            orgUnitCascadeService,
            grantRepository,
            SecurityConfig.roleHierarchy());
    lenient().when(membershipRepository.findAllByIdUserId(userId)).thenReturn(List.of());
    lenient().when(orgUnitCascadeService.officerReach(anyCollection())).thenReturn(Set.of());
    lenient().when(grantRepository.findByUserId(userId)).thenReturn(List.of());
  }

  @Test
  void webActor_ofAnAdmin_reachesTheBankRolesThroughTheHierarchy() {
    storeUser(ApprovalStatus.ACTIVE, Roles.ADMIN, Roles.KRT_MEMBER);

    BankActor actor = actors.of(userId);

    assertThat(actor.roles())
        .contains(Roles.ADMIN, Roles.BANK_MANAGEMENT, Roles.BANK_EMPLOYEE, Roles.KRT_MEMBER);
  }

  @Test
  void exchangeActor_ofAnAdmin_holdsNoAdminAndNoBankRoleItImplied() {
    storeUser(ApprovalStatus.ACTIVE, Roles.ADMIN, Roles.KRT_MEMBER);

    BankActor actor = actors.forExchange(userId);

    assertThat(actor.roles()).containsExactly(Roles.KRT_MEMBER);
  }

  @Test
  void exchangeActor_ofAnAdminWhoIsBankleitung_keepsTheStoredBankRole() {
    storeUser(ApprovalStatus.ACTIVE, Roles.ADMIN, Roles.BANK_MANAGEMENT);

    BankActor actor = actors.forExchange(userId);

    assertThat(actor.roles()).containsExactlyInAnyOrder(Roles.BANK_MANAGEMENT, Roles.BANK_EMPLOYEE);
  }

  @Test
  void actor_ofAPendingRegistration_holdsNoRole() {
    storeUser(ApprovalStatus.PENDING, Roles.BANK_EMPLOYEE);

    assertThat(actors.of(userId).roles()).isEmpty();
  }

  @Test
  void actor_ofNoMember_isNobodyWithoutAnyRead() {
    assertThat(actors.of(null)).isSameAs(BankActor.NOBODY);
    verifyNoInteractions(userRepository, membershipRepository, grantRepository);
  }

  private void storeUser(ApprovalStatus status, String... roleCodes) {
    User user = new User();
    user.setId(userId);
    user.setApprovalStatus(status);
    for (String code : roleCodes) {
      Role role = new Role();
      role.setId((long) user.getRoles().size() + 1);
      role.setCode(code);
      role.setName(code);
      user.getRoles().add(role);
    }
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
  }
}
