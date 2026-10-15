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

package de.greluc.krt.profit.basetool.backend.exchange.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The member's direct org-unit memberships relayed from the ingest gateway: all four kinds, an
 * empty list without memberships, and gated by its capability (REQ-XCH-048).
 */
@SpringBootTest
class ExchangeOrgUnitControllerTest {

  private static final String PATH = "/api/v1/exchange/me/org-units";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "o".repeat(39);
  private static final String SCOPE = "exchange.orgunits.read";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> orgUnits = new ArrayList<>();

  private MockMvc mockMvc;
  private UUID member;
  private String client;
  private boolean wasEnabled;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("orgunits-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    member = userRepository.saveAndFlush(user).getId();
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.ORGUNITS_READ));
    clientRepository.saveAndFlush(registered);
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", member);
    jdbc.update("DELETE FROM user_roles WHERE user_id = ?", member);
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
    orgUnits.forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aMemberOfNoUnitGetsAnEmptyList() throws Exception {
    mockMvc
        .perform(relayed(get(PATH), SCOPE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void everyDirectMembershipOfAllFourKindsIsListedTopDown() throws Exception {
    UUID squadron = orgUnit("SQUADRON", "Zeta Staffel");
    UUID special = orgUnit("SPECIAL_COMMAND", "Alpha Kommando");
    UUID area = orgUnit("BEREICH", "Logistik Bereich");
    UUID leadership = orgUnit("ORGANISATIONSLEITUNG", "Leitung");
    joins(squadron);
    joins(special);
    joins(area);
    joins(leadership);
    orgUnit("SQUADRON", "Not mine");

    mockMvc
        .perform(relayed(get(PATH), SCOPE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(4))
        .andExpect(jsonPath("$.items[0].id").value(leadership.toString()))
        .andExpect(jsonPath("$.items[0].kind").value("ORGANISATIONSLEITUNG"))
        .andExpect(jsonPath("$.items[1].id").value(area.toString()))
        .andExpect(jsonPath("$.items[1].kind").value("BEREICH"))
        .andExpect(jsonPath("$.items[2].id").value(squadron.toString()))
        .andExpect(jsonPath("$.items[2].kind").value("SQUADRON"))
        .andExpect(jsonPath("$.items[2].name").value(nameOf(squadron)))
        .andExpect(jsonPath("$.items[2].shorthand").value(shorthandOf(squadron)))
        .andExpect(jsonPath("$.items[3].id").value(special.toString()))
        .andExpect(jsonPath("$.items[3].kind").value("SPECIAL_COMMAND"));
  }

  @Test
  void withoutTheCapabilityTheListIsRefused() throws Exception {
    mockMvc
        .perform(relayed(get(PATH), "exchange.connect"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  /**
   * Adds the gateway's identity and the relay headers.
   *
   * @param request the request
   * @param capabilities the relayed capabilities
   * @return the request
   */
  private @NotNull MockHttpServletRequestBuilder relayed(
      @NotNull MockHttpServletRequestBuilder request, @NotNull String capabilities) {
    return request
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capabilities)
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY);
  }

  /**
   * Seeds an active org unit of the given kind.
   *
   * @param kind the discriminator value
   * @param label the name prefix
   * @return its id
   */
  private @NotNull UUID orgUnit(@NotNull String kind, @NotNull String label) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible) VALUES (?, ?, ?, ?, TRUE, FALSE, FALSE)",
        id,
        kind,
        label + " " + id,
        "O" + id.toString().substring(0, 8));
    orgUnits.add(id);
    return id;
  }

  /**
   * Makes the member a direct member of an org unit.
   *
   * @param orgUnit the org unit
   */
  private void joins(@NotNull UUID orgUnit) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id) VALUES (?, ?)", member, orgUnit);
  }

  /**
   * Reads an org unit's stored name.
   *
   * @param orgUnit the org unit
   * @return its name
   */
  private @NotNull String nameOf(@NotNull UUID orgUnit) {
    return jdbc.queryForObject("SELECT name FROM org_unit WHERE id = ?", String.class, orgUnit);
  }

  /**
   * Reads an org unit's stored shorthand.
   *
   * @param orgUnit the org unit
   * @return its shorthand
   */
  private @NotNull String shorthandOf(@NotNull UUID orgUnit) {
    return jdbc.queryForObject(
        "SELECT shorthand FROM org_unit WHERE id = ?", String.class, orgUnit);
  }
}
