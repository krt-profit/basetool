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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientRevocation;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeInstallation;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRevocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeInstallationRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
class AdminExchangeClientUsageTest {

  private static final String USAGE = "/api/v1/admin/exchange-clients/usage";
  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeInstallationRepository installationRepository;
  @Autowired private ExchangeClientRevocationRepository revocationRepository;

  private MockMvc mockMvc;
  private ExchangeClient used;
  private ExchangeClient unused;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    used = client("versekit-usage");
    unused = client("unused-usage");
    User twoDevices = user("usage-two-devices");
    User revokedDevice = user("usage-revoked-device");
    User disconnectedBefore = user("usage-disconnected-before");
    User reconnectedAfter = user("usage-reconnected-after");

    installation(twoDevices, "a", NOW.minusSeconds(600), null);
    installation(twoDevices, "b", NOW.minusSeconds(300), null);
    installation(revokedDevice, "c", NOW.minusSeconds(10), NOW.minusSeconds(5));
    installation(disconnectedBefore, "d", NOW.minusSeconds(3600), null);
    revocation(disconnectedBefore, NOW.minusSeconds(1800));
    installation(reconnectedAfter, "e", NOW.minusSeconds(60), null);
    revocation(reconnectedAfter, NOW.minusSeconds(120));
  }

  @Test
  void onlyLiveInstallationsCountAndAnUnusedClientHasNoRow() throws Exception {
    mockMvc
        .perform(get(USAGE).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(used.getId().toString()))
        .andExpect(jsonPath("$[0].connectedMembers").value(2))
        .andExpect(jsonPath("$[0].lastSeenAt").value(NOW.minusSeconds(60).toString()))
        .andExpect(jsonPath("$[?(@.id == '" + unused.getId() + "')]").isEmpty());
  }

  @Test
  void anOfficerIsRefused() throws Exception {
    mockMvc
        .perform(get(USAGE).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());
  }

  /**
   * Seeds an active client.
   *
   * @param clientId the client id
   * @return the client
   */
  private @NotNull ExchangeClient client(@NotNull String clientId) {
    ExchangeClient client = new ExchangeClient();
    client.setClientId(clientId);
    client.setDisplayName(clientId);
    client.setStatus(ExchangeClientStatus.ACTIVE);
    client.setCapabilities(EnumSet.of(ExchangeCapability.CONNECT));
    return clientRepository.saveAndFlush(client);
  }

  /**
   * Seeds a member.
   *
   * @param username the username
   * @return the member
   */
  private @NotNull User user(@NotNull String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    return userRepository.saveAndFlush(user);
  }

  /**
   * Seeds an installation of the used client.
   *
   * @param user the member
   * @param key a letter the key thumbprint is made of
   * @param lastSeenAt when it was last seen
   * @param revokedAt when it was revoked, or {@code null}
   */
  private void installation(
      @NotNull User user,
      @NotNull String key,
      @NotNull Instant lastSeenAt,
      @Nullable Instant revokedAt) {
    ExchangeInstallation installation = new ExchangeInstallation();
    installation.setClient(used);
    installation.setUser(user);
    installation.setKeyThumbprint(key.repeat(43));
    installation.setFirstSeenAt(lastSeenAt);
    installation.setLastSeenAt(lastSeenAt);
    installation.setRevokedAt(revokedAt);
    installationRepository.saveAndFlush(installation);
  }

  /**
   * Seeds a member's revocation of the used client.
   *
   * @param user the member
   * @param revokedAt when they disconnected it
   */
  private void revocation(@NotNull User user, @NotNull Instant revokedAt) {
    revocationRepository.saveAndFlush(
        new ExchangeClientRevocation(
            new ExchangeClientRevocation.Key(used.getId(), user.getId()), revokedAt));
  }

  /**
   * Authenticates as an admin.
   *
   * @return the post-processor
   */
  private static @NotNull org.springframework.security.test.web.servlet.request
          .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
      admin() {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }
}
