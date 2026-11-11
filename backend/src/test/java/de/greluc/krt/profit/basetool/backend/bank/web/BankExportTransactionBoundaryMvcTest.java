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

package de.greluc.krt.profit.basetool.backend.bank.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccount;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountType;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankReportRenderer;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.testcontext.LeafServiceMockTest;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * The real org-unit export route releases authorization and snapshot connections before rendering.
 */
@LeafServiceMockTest
class BankExportTransactionBoundaryMvcTest {
  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private BankReportRenderer renderer;
  @Autowired private ObjectMapper mapper;
  @Autowired private MessageSource messages;
  @Autowired private DataSource dataSource;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private BankReportCleanupFilter cleanup;

  @Test
  void orgUnitStatementRendersWithNoTransactionOrPinnedPersistenceResource() throws Exception {
    User actor = new User();
    actor.setId(UUID.randomUUID());
    actor.setUsername("export-boundary-" + actor.getId());
    actor.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.ADMIN).orElseThrow())));
    userRepository.save(actor);
    BankAccount account = new BankAccount();
    account.setAccountNo("KB-" + accountRepository.nextAccountNoValue());
    account.setName("Synthetic transaction-boundary account");
    account.setType(BankAccountType.AREA);
    account.setAreaName("Synthetic transaction-boundary area");
    account.setStatus(BankAccountStatus.ACTIVE);
    accountRepository.save(account);
    BankReportRenderer realRenderer = new BankReportRenderer(mapper, messages);
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
              assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory))
                  .isFalse();
              realRenderer.statement(
                  invocation.getArgument(0),
                  invocation.getArgument(1),
                  invocation.getArgument(2),
                  invocation.getArgument(3),
                  invocation.getArgument(4),
                  invocation.getArgument(5));
              return null;
            })
        .when(renderer)
        .statement(any(), any(), any(), any(), any(), eq(true));
    var mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .addFilters(cleanup)
            .build();
    var result =
        mockMvc
            .perform(
                get("/api/v1/bank/org-units/accounts/{id}/statement", account.getId())
                    .param("from", Instant.now().minusSeconds(3600).toString())
                    .param("to", Instant.now().plusSeconds(3600).toString())
                    .with(
                        jwt()
                            .jwt(token -> token.subject(actor.getId().toString()))
                            .authorities(new SimpleGrantedAuthority(Roles.authority(Roles.ADMIN)))))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(result.getResponse().getContentAsByteArray()).hasSizeGreaterThan(1000);
    verify(renderer).statement(any(), any(), any(), any(), any(), eq(true));
  }
}
