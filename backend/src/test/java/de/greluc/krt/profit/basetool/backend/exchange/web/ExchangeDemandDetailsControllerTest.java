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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The demand details under {@code exchange.demand.details}: the orders of the member's Logistician
 * units, after the job-order gate, with no assignee (REQ-XCH-047).
 */
@SpringBootTest
class ExchangeDemandDetailsControllerTest {

  private static final String PATH = "/api/v1/exchange/me/org-demand";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "d".repeat(39);
  private static final String READ = "exchange.demand.read";
  private static final String DETAILS = READ + ",exchange.demand.details";

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
  @Autowired private ObjectMapper objectMapper;

  private final List<UUID> orders = new ArrayList<>();
  private final List<UUID> orgUnits = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> users = new ArrayList<>();

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
    member = user("demand-details");
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(
            ExchangeCapability.CONNECT,
            ExchangeCapability.DEMAND_READ,
            ExchangeCapability.DEMAND_DETAILS));
    clientRepository.saveAndFlush(registered);
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    orders.forEach(id -> {
      jdbc.update("DELETE FROM job_order_assignees WHERE job_order_id = ?", id);
      jdbc.update("DELETE FROM job_order_material WHERE job_order_id = ?", id);
      jdbc.update("DELETE FROM job_order WHERE id = ?", id);
    });
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    users.forEach(id -> {
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    });
    orgUnits.forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aLogisticianGetsTheOrdersOfTheirUnitWithNumberAuftraggeberAndCommentButNoAssignee()
      throws Exception {
    UUID unit = orgUnit(true);
    UUID requesting = orgUnit(false);
    joins(unit, "MEMBER", true);
    UUID order = openOrder(unit, requesting, material("Agricium"), 10.0, "Bring it to Lorville");
    UUID assignee = user("assignee");
    jdbc.update(
        "INSERT INTO job_order_assignees (id, job_order_id, user_id, note, version)"
            + " VALUES (?, ?, ?, 'secret note', 0)",
        UUID.randomUUID(),
        order,
        assignee);
    Integer number =
        jdbc.queryForObject("SELECT display_id FROM job_order WHERE id = ?", Integer.class, order);

    String body =
        mockMvc
            .perform(relayed(get(PATH), DETAILS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.materials.length()").value(1))
            .andExpect(jsonPath("$.orders.length()").value(1))
            .andExpect(jsonPath("$.orders[0].id").value(order.toString()))
            .andExpect(jsonPath("$.orders[0].number").value(number))
            .andExpect(jsonPath("$.orders[0].type").value("MATERIAL"))
            .andExpect(jsonPath("$.orders[0].status").value("OPEN"))
            .andExpect(jsonPath("$.orders[0].unit.id").value(unit.toString()))
            .andExpect(jsonPath("$.orders[0].requestingUnit.id").value(requesting.toString()))
            .andExpect(jsonPath("$.orders[0].handle").value("demand-requester"))
            .andExpect(jsonPath("$.orders[0].comment").value("Bring it to Lorville"))
            .andExpect(jsonPath("$.orders[0].materials[0].openQuantity.amount").value(10))
            .andExpect(jsonPath("$.orders[0].items.length()").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode first = objectMapper.readTree(body).get("orders").get(0);
    assertThat(first.propertyNames())
        .isSubsetOf(
            Set.of(
                "id",
                "number",
                "type",
                "status",
                "priority",
                "unit",
                "requestingUnit",
                "handle",
                "comment",
                "materials",
                "items"));
    assertThat(body).doesNotContain(assignee.toString()).doesNotContain("secret note");
  }

  @Test
  void aStaffelleiterIsALogisticianOfTheUnit() throws Exception {
    UUID unit = orgUnit(true);
    joins(unit, "STAFFELLEITER", false);
    openOrder(unit, unit, material("Laranite"), 4.0, null);

    mockMvc
        .perform(relayed(get(PATH), DETAILS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orders.length()").value(1));
  }

  @Test
  void aPlainMemberGetsTheAnswerWithoutOrders() throws Exception {
    UUID unit = orgUnit(true);
    joins(unit, "MEMBER", false);
    openOrder(unit, unit, material("Beryl"), 5.0, "comment");

    mockMvc
        .perform(relayed(get(PATH), DETAILS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.materials.length()").value(1))
        .andExpect(jsonPath("$.orders").doesNotExist())
        .andExpect(jsonPath("$.reason").doesNotExist());
  }

  @Test
  void theOrdersOfTheMembersOtherUnitsStaySummedOnly() throws Exception {
    UUID mine = orgUnit(true);
    UUID other = orgUnit(true);
    joins(mine, "MEMBER", true);
    joins(other, "MEMBER", false);
    UUID visible = openOrder(mine, mine, material("Gold"), 3.0, null);
    openOrder(other, other, material("Tin"), 2.0, null);

    mockMvc
        .perform(relayed(get(PATH), DETAILS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.materials.length()").value(2))
        .andExpect(jsonPath("$.orders.length()").value(1))
        .andExpect(jsonPath("$.orders[0].id").value(visible.toString()));
  }

  @Test
  void withoutTheRelayedCapabilityALogisticianGetsNoOrders() throws Exception {
    UUID unit = orgUnit(true);
    joins(unit, "MEMBER", true);
    openOrder(unit, unit, material("Iron"), 1.0, null);

    mockMvc
        .perform(relayed(get(PATH), READ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.materials.length()").value(1))
        .andExpect(jsonPath("$.orders").doesNotExist());
  }

  @Test
  void aClientNotApprovedForTheDetailsGetsNoOrdersEvenWhenTheScopeIsRelayed() throws Exception {
    ExchangeClient registered =
        clientRepository.findWithCapabilitiesByClientId(client).orElseThrow();
    registered.setCapabilities(
        EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.DEMAND_READ));
    clientRepository.saveAndFlush(registered);
    UUID unit = orgUnit(true);
    joins(unit, "MEMBER", true);
    openOrder(unit, unit, material("Copper"), 1.0, null);

    mockMvc
        .perform(relayed(get(PATH), DETAILS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orders").doesNotExist());
  }

  @Test
  void theJobOrderGateAppliesFirst() throws Exception {
    UUID unit = orgUnit(false);
    joins(unit, "MEMBER", true);
    openOrder(unit, unit, material("Quartz"), 1.0, "comment");

    mockMvc
        .perform(relayed(get(PATH), DETAILS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reason").value("NOT_PERMITTED"))
        .andExpect(jsonPath("$.orders").doesNotExist());
  }

  @Test
  void theDetailsCapabilityAloneDoesNotOpenTheRoute() throws Exception {
    mockMvc
        .perform(relayed(get(PATH), "exchange.demand.details"))
        .andExpect(status().isForbidden());
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
   * Seeds a member.
   *
   * @param username the username prefix
   * @return the member's id
   */
  private @NotNull UUID user(@NotNull String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    UUID id = userRepository.saveAndFlush(user).getId();
    users.add(id);
    return id;
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
   * @param role the membership role
   * @param logistician whether the membership carries the Logistician flag
   */
  private void joins(@NotNull UUID orgUnit, @NotNull String role, boolean logistician) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, ?, ?)",
        member,
        orgUnit,
        role,
        logistician);
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
   * Seeds an open material order with one requirement and no quality floor.
   *
   * @param unit the responsible unit
   * @param requesting the requesting unit
   * @param material the required material
   * @param amount the required amount
   * @param comment the order's comment, or {@code null}
   * @return the order's id
   */
  private @NotNull UUID openOrder(
      @NotNull UUID unit,
      @NotNull UUID requesting,
      @NotNull UUID material,
      double amount,
      @Nullable String comment) {
    UUID id =
        transactionTemplate.execute(status -> {
          JobOrder order =
              JobOrder.builder()
                  .responsibleOrgUnit(orgUnitRepository.getReferenceById(unit))
                  .requestingOrgUnit(orgUnitRepository.getReferenceById(requesting))
                  .handle("demand-requester")
                  .comment(comment)
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
    return id;
  }
}
