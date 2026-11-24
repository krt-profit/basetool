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

import static de.greluc.krt.profit.basetool.backend.support.StatementCounter.measured;
import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.OrgUnitFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Verifies against real Postgres that the visible-accounts read orders by each account's org unit
 * without a statement per account (REQ-BANK-064, REQ-DATA-003).
 */
@SpringBootTest
class OrgUnitBankVisibleAccountsReadNoNPlusOneTest {

  /** Org-unit accounts in the seed, well past a handful so a per-account read stands out. */
  private static final int ACCOUNTS = 40;

  /** Upper bound on statements per read; a per-account lazy load would need at least ACCOUNTS. */
  private static final int STATEMENT_BOUND = 15;

  @Autowired private OrgUnitBankVisibleAccountsQuery query;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void orderingByOrgUnitStaysStatementBounded_independentOfAccountCount() throws Exception {
    String tag = UUID.randomUUID().toString().substring(0, 6);
    for (int i = 0; i < ACCOUNTS; i++) {
      BankAccount account = new BankAccount();
      account.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
      account.setName("Visible Konto " + tag + " " + i);
      account.setType(BankAccountType.ORG_UNIT);
      account.setStatus(BankAccountStatus.ACTIVE);
      account.setOrgUnit(
          OrgUnitFixtures.persistedSquadron(
              squadronRepository, "Visible Staffel " + tag + " " + i, "V" + tag + i, null));
      accountRepository.save(account);
    }
    User admin = new User();
    admin.setId(UUID.randomUUID());
    admin.setUsername("visible-admin-" + tag);
    admin.setApprovalStatus(ApprovalStatus.ACTIVE);
    admin.getRoles().add(roleRepository.findByCode(Roles.ADMIN).orElseThrow());
    userRepository.save(admin);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("visible-accounts")
                    .header("alg", "none")
                    .subject(admin.getId().toString())
                    .build(),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

    var page = measured(() -> query.listVisibleAccounts(PageRequest.of(0, 1000)));

    assertThat(page.result().getContent())
        .filteredOn(row -> row.accountName().startsWith("Visible Konto " + tag))
        .hasSize(ACCOUNTS)
        .allSatisfy(row -> assertThat(row.orgUnitName()).startsWith("Visible Staffel " + tag));
    assertThat(page.statements())
        .as("statements for %d accounts", ACCOUNTS)
        .isPositive()
        .isLessThanOrEqualTo(STATEMENT_BOUND);
  }
}
