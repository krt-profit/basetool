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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.internal.AssignableRole;
import de.greluc.krt.profit.basetool.backend.identity.internal.UserRoleAssignmentService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies against PostgreSQL that the bank-holder registry follows a committed role change at
 * once, without waiting for the daily roster sync (REQ-BANK-029), and that a rolled-back change
 * leaves it alone.
 */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class BankHolderRoleChangeIntegrationTest {

  @Autowired private UserRoleAssignmentService roleAssignmentService;
  @Autowired private UserRepository userRepository;
  @Autowired private BankHolderRepository holderRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  void aCommittedBankRoleMakesTheMemberAHolderAndItsWithdrawalDeactivatesIt() {
    User member = member();

    roleAssignmentService.change(member.getId(), AssignableRole.BANK_EMPLOYEE, true, 0);

    BankHolder holder = holderRepository.findByUserId(member.getId()).orElseThrow();
    assertThat(holder.isActive()).isTrue();
    assertThat(holder.isRoleManaged()).isTrue();

    roleAssignmentService.change(member.getId(), AssignableRole.BANK_EMPLOYEE, false, 1);

    assertThat(holderRepository.findByUserId(member.getId()).orElseThrow().isActive()).isFalse();
  }

  @Test
  void aRolledBackBankRoleRegistersNoHolder() {
    User member = member();

    assertThatThrownBy(() ->
            transactionTemplate.executeWithoutResult(_ -> {
              roleAssignmentService.change(member.getId(), AssignableRole.BANK_EMPLOYEE, true, 0);
              throw new IllegalStateException("roll back");
            }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(holderRepository.findByUserId(member.getId())).isEmpty();
  }

  private User member() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("u-" + UUID.randomUUID());
    user.setDisplayName("Banker");
    return userRepository.save(user);
  }
}
