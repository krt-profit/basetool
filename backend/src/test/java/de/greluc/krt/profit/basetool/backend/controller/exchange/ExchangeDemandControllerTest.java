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

package de.greluc.krt.profit.basetool.backend.controller.exchange;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.exchange.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * The anonymised org demand relayed from the ingest gateway: gated by its capability, and withheld
 * with {@code NOT_PERMITTED} from a member who fails the web's job-order gate (REQ-XCH-018).
 */
@SpringBootTest
class ExchangeDemandControllerTest {

  private static final String PATH = "/api/v1/exchange/me/org-demand";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "d".repeat(39);

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private OrgUnitRepository orgUnitRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private QualityTierRepository qualityTierRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> orders = new ArrayList<>();
  private final List<UUID> orgUnits = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();

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
    user.setUsername("demand-" + UUID.randomUUID());
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
        EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.DEMAND_READ));
    clientRepository.saveAndFlush(registered);
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    orders.forEach(
        id -> {
          jdbc.update("DELETE FROM job_order_material WHERE job_order_id = ?", id);
          jdbc.update("DELETE FROM job_order WHERE id = ?", id);
        });
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", member);
    jdbc.update("DELETE FROM user_roles WHERE user_id = ?", member);
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
    orgUnits.forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aMemberOfNoUnitIsNotPermittedAndGetsAnEmptyDemand() throws Exception {
    mockMvc
        .perform(relayed(get(PATH), "exchange.demand.read"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.materials.length()").value(0))
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.updatedAt").exists())
        .andExpect(jsonPath("$.reason").value("NOT_PERMITTED"));
  }

  @Test
  void aMemberOfAProfitEligibleUnitGetsItsDemandWithoutAReason() throws Exception {
    UUID unit = orgUnit(true);
    joins(unit);
    openOrder(unit, material("Agricium"), 10.0);

    mockMvc
        .perform(relayed(get(PATH), "exchange.demand.read"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.materials.length()").value(1))
        .andExpect(jsonPath("$.materials[0].openQuantity.amount").value(10))
        .andExpect(jsonPath("$.reason").doesNotExist());
  }

  @Test
  void aUnitThatLosesProfitEligibilityStopsShowingItsOpenDemand() throws Exception {
    UUID unit = orgUnit(true);
    joins(unit);
    openOrder(unit, material("Laranite"), 6.0);
    mockMvc
        .perform(relayed(get(PATH), "exchange.demand.read"))
        .andExpect(jsonPath("$.materials.length()").value(1));

    jdbc.update("UPDATE org_unit SET is_profit_eligible = FALSE WHERE id = ?", unit);

    mockMvc
        .perform(relayed(get(PATH), "exchange.demand.read"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.materials.length()").value(0))
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.reason").value("NOT_PERMITTED"));
  }

  @Test
  void withoutTheReadCapabilityTheDemandIsRefused() throws Exception {
    mockMvc.perform(relayed(get(PATH), "exchange.connect")).andExpect(status().isForbidden());
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
   * Seeds an active Staffel.
   *
   * @param profitEligible whether it is profit-eligible
   * @return its id
   */
  private @NotNull UUID orgUnit(boolean profitEligible) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible) VALUES (?, 'SQUADRON', ?, ?, TRUE, FALSE, ?)",
        id,
        "Unit " + id,
        "U" + id.toString().substring(0, 6),
        profitEligible);
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
   * Seeds a refined SCU material.
   *
   * @param name its name prefix
   * @return its id
   */
  private @NotNull UUID material(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,"
            + " is_job_order, is_visible, source_systems)"
            + " VALUES (?, ?, 'REFINED', 'SCU', false, false, true, 'UEX_ONLY')",
        id,
        name + " " + id);
    materials.add(id);
    return id;
  }

  /**
   * Seeds an open material order the unit is responsible for, with one requirement and no quality
   * floor.
   *
   * @param unit the responsible and requesting unit
   * @param material the required material
   * @param amount the required amount
   */
  private void openOrder(@NotNull UUID unit, @NotNull UUID material, double amount) {
    UUID id =
        transactionTemplate.execute(
            status -> {
              OrgUnit responsible = orgUnitRepository.getReferenceById(unit);
              JobOrder order =
                  JobOrder.builder()
                      .responsibleOrgUnit(responsible)
                      .requestingOrgUnit(responsible)
                      .handle("demand-test")
                      .status(JobOrderStatus.OPEN)
                      .build();
              order.addMaterial(
                  JobOrderMaterial.builder()
                      .material(materialRepository.getReferenceById(material))
                      .qualityTier(qualityTierRepository.findByCode("NONE").orElseThrow())
                      .amount(amount)
                      .build());
              return jobOrderRepository.save(order).getId();
            });
    orders.add(Objects.requireNonNull(id));
  }
}
