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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDomain;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.KeycloakUserDto;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.KeycloakService;
import de.greluc.krt.profit.basetool.backend.service.UserReconciliationService;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class ExchangeDepartureIntegrationTest {

  private static final UUID MEMBER = UUID.fromString("44444444-4444-4444-4444-4444444440f1");

  @Autowired private UserReconciliationService reconciliationService;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeClientRevocationRepository revocationRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @MockitoBean private KeycloakService keycloakService;

  private ExchangeClient client;

  @BeforeEach
  void setUp() {
    User member = new User();
    member.setId(MEMBER);
    member.setUsername("departing-member");
    member.setApprovalStatus(ApprovalStatus.ACTIVE);
    member.setInKeycloak(true);
    member.setEnabledInKeycloak(true);
    member.setRoles(
        new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    userRepository.saveAndFlush(member);
    client = new ExchangeClient();
    client.setClientId("versekit-dep");
    client.setDisplayName("VerseKit");
    client.setStatus(ExchangeClientStatus.ACTIVE);
    client.setCapabilities(EnumSet.of(ExchangeCapability.CONNECT));
    client = clientRepository.saveAndFlush(client);
  }

  @AfterEach
  void cleanUp() {
    auditEventRepository.deleteAll(
        auditEventRepository.findAll().stream()
            .filter(e -> MEMBER.equals(e.getTargetUserId()))
            .toList());
    clientRepository.delete(client);
    userRepository.deleteById(MEMBER);
  }

  @Test
  void aMemberDisabledAtTheRosterSyncLosesExchangeAccessAtOnce() {
    reconciliationService.syncUser(
        new KeycloakUserDto(MEMBER, "departing-member", null, false, Set.of(), null));

    assertThat(
            revocationRepository.findById(new ExchangeClientRevocation.Key(client.getId(), MEMBER)))
        .isPresent();
    verify(keycloakService).revokeConsent(MEMBER, "versekit-dep");
    verify(keycloakService).logoutUser(MEMBER);
    assertThat(auditEventRepository.findAll())
        .anyMatch(
            e -> e.getDomain() == AuditDomain.CONNECTED_APPS && MEMBER.equals(e.getTargetUserId()));
  }

  @Test
  void aMemberWhoStaysActiveKeepsExchangeAccess() {
    reconciliationService.syncUser(
        new KeycloakUserDto(
            MEMBER,
            "departing-member",
            null,
            true,
            Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow().getName()),
            null));

    verify(keycloakService, never()).logoutUser(MEMBER);
  }
}
