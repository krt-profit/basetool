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

import com.jayway.jsonpath.JsonPath;
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
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The org stock relayed from the ingest gateway: the shared lots of the member's direct units, one
 * pool per unit, with the units read and the earmarks per dimension, gated by its capability
 * (REQ-XCH-042).
 */
@SpringBootTest
class ExchangeOrgStockControllerTest {

  private static final String PATH = "/api/v1/exchange/org-stock";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "g".repeat(39);
  private static final String SCOPE = "exchange.orgstock.read";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meterRegistry;

  private final List<UUID> users = new ArrayList<>();
  private final List<UUID> orgUnits = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();
  private final List<UUID> missions = new ArrayList<>();
  private final List<UUID> jobOrders = new ArrayList<>();

  private MockMvc mockMvc;
  private UUID member;
  private UUID other;
  private String client;
  private boolean wasEnabled;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    member = user("orgstock-member", Roles.KRT_MEMBER);
    other = user("orgstock-other", Roles.KRT_MEMBER);
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.ORGSTOCK_READ));
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
    for (UUID id : users) {
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", id);
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
    }
    jobOrders.forEach(id -> jdbc.update("DELETE FROM job_order WHERE id = ?", id));
    missions.forEach(id -> jdbc.update("DELETE FROM mission WHERE id = ?", id));
    users.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
    orgUnits.reversed().forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    locations.forEach(id -> jdbc.update("DELETE FROM location WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aMemberOfNoUnitReadsNothing() throws Exception {
    read(relayed(get(PATH)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.removed.length()").value(0))
        .andExpect(jsonPath("$.units.length()").value(0))
        .andExpect(jsonPath("$.hasMore").value(false))
        .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.startsWith("f1.")));
  }

  @Test
  void onlyTheSharedRowsOfTheMembersOwnUnitsAreServedSummedOverEveryHolder() throws Exception {
    UUID staffel = orgUnit("SQUADRON", null);
    UUID foreign = orgUnit("SQUADRON", null);
    joins(member, staffel, "MEMBER");
    joins(other, staffel, "MEMBER");
    UUID titanium = material();
    UUID area18 = location();
    stock(member, titanium, area18, 500, 2, false, staffel);
    stock(other, titanium, area18, 500, 3.5, false, staffel);
    stock(other, titanium, area18, 500, 7, true, staffel);
    stock(member, titanium, area18, 500, 11, true, null);
    stock(other, titanium, area18, 500, 13, false, null);
    stock(other, titanium, area18, 500, 17, false, foreign);

    read(relayed(get(PATH)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].unit.id").value(staffel.toString()))
        .andExpect(jsonPath("$.items[0].unit.name").value(nameOf(staffel)))
        .andExpect(jsonPath("$.items[0].material.bt").value(titanium.toString()))
        .andExpect(jsonPath("$.items[0].quality").value(500))
        .andExpect(jsonPath("$.items[0].stolen").value(false))
        .andExpect(jsonPath("$.items[0].quantity.amount").value(5.5))
        .andExpect(jsonPath("$.items[0].quantity.unit").value("SCU"))
        .andExpect(
            jsonPath("$.items[0].key")
                .value("u:" + staffel + "|m:" + titanium + "|l:" + area18 + "|q:500|s:0"))
        .andExpect(jsonPath("$.units.length()").value(1))
        .andExpect(jsonPath("$.units[0].id").value(staffel.toString()))
        .andExpect(jsonPath("$.units[0].kind").value("SQUADRON"));
  }

  @Test
  void aUnitTheMemberOnlyOverseesIsNotServed() throws Exception {
    UUID bereich = orgUnit("BEREICH", null);
    UUID child = orgUnit("SQUADRON", bereich);
    joins(member, bereich, "BEREICHSLEITER");
    joins(other, child, "MEMBER");
    UUID titanium = material();
    UUID area18 = location();
    stock(other, titanium, area18, 1, 4, false, child);
    stock(other, titanium, area18, 1, 6, false, bereich);

    read(relayed(get(PATH)))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].unit.id").value(bereich.toString()))
        .andExpect(jsonPath("$.items[0].quantity.amount").value(6))
        .andExpect(jsonPath("$.units.length()").value(1))
        .andExpect(jsonPath("$.units[0].id").value(bereich.toString()));
  }

  @Test
  void theSameMaterialAtTheSamePlaceInTwoUnitsIsTwoLots() throws Exception {
    UUID staffel = orgUnit("SQUADRON", null);
    UUID kommando = orgUnit("SPECIAL_COMMAND", null);
    joins(member, staffel, "MEMBER");
    joins(member, kommando, "MEMBER");
    UUID titanium = material();
    UUID area18 = location();
    stock(member, titanium, area18, 1, 4, false, staffel);
    stock(member, titanium, area18, 1, 6, false, kommando);

    read(relayed(get(PATH)))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[?(@.unit.id == '" + staffel + "')].quantity.amount").value(4))
        .andExpect(jsonPath("$.items[?(@.unit.id == '" + kommando + "')].quantity.amount").value(6))
        .andExpect(jsonPath("$.units.length()").value(2));
  }

  @Test
  void aLotCarriesItsEarmarksPerDimension() throws Exception {
    UUID staffel = orgUnit("SQUADRON", null);
    joins(member, staffel, "MEMBER");
    UUID titanium = material();
    UUID area18 = location();
    UUID first = stock(member, titanium, area18, 1, 10, false, staffel);
    UUID second = stock(other, titanium, area18, 1, 10, false, staffel);
    earmarkToMission(first, 2);
    earmarkToMission(second, 1.5);
    earmarkToJobOrder(first, 4, staffel);
    stock(member, material(), area18, 1, 3, false, staffel);

    read(relayed(get(PATH)))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(
            jsonPath("$.items[?(@.material.bt == '" + titanium + "')].earmarked.missions.amount")
                .value(3.5))
        .andExpect(
            jsonPath("$.items[?(@.material.bt == '" + titanium + "')].earmarked.jobOrders.amount")
                .value(4))
        .andExpect(
            jsonPath("$.items[?(@.material.bt == '" + titanium + "')].earmarked.jobOrders.unit")
                .value("SCU"))
        .andExpect(
            jsonPath("$.items[?(@.material.bt != '" + titanium + "')].earmarked.missions.amount")
                .value(0));
  }

  @Test
  void anAdminReadsOnlyThePoolsOfTheirOwnMembershipsWithOrWithoutAPin() throws Exception {
    UUID admin = user("orgstock-admin", Roles.ADMIN);
    UUID own = orgUnit("SQUADRON", null);
    UUID foreign = orgUnit("SQUADRON", null);
    joins(admin, own, "MEMBER");
    UUID titanium = material();
    UUID area18 = location();
    stock(other, titanium, area18, 1, 4, false, own);
    stock(other, titanium, area18, 1, 6, false, foreign);
    member = admin;

    read(relayed(get(PATH)))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].unit.id").value(own.toString()));
    read(relayed(get(PATH)).header(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, foreign.toString()))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].unit.id").value(own.toString()));
  }

  @Test
  void aSnapshotPagesByLotListsTheUnitsOnEveryPageAndEndsAtTheFeed() throws Exception {
    UUID staffel = orgUnit("SQUADRON", null);
    joins(member, staffel, "MEMBER");
    UUID titanium = material();
    for (int i = 0; i < 3; i++) {
      stock(member, titanium, location(), 1, 1, false, staffel);
    }

    String first =
        read(relayed(get(PATH).param("limit", "2")))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.units.length()").value(1))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String next = JsonPath.read(first, "$.nextCursor");
    assertThat(next).startsWith("s1.");

    read(relayed(get(PATH).param("limit", "2").param("cursor", next)))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.units.length()").value(1))
        .andExpect(jsonPath("$.hasMore").value(false))
        .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.startsWith("f1.")));
    assertThat(
            meterRegistry
                .counter(MetricNames.EXCHANGE_ORG_STOCK_PAGES, MetricNames.TAG_CLIENT_ID, client)
                .count())
        .as("one page counted per answer")
        .isEqualTo(2.0);
  }

  @Test
  void theFeedCursorASnapshotEndsWithIsAnsweredAsExpired() throws Exception {
    String body = read(relayed(get(PATH))).andReturn().getResponse().getContentAsString();
    String feed = JsonPath.read(body, "$.nextCursor");

    read(relayed(get(PATH).param("cursor", feed)))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
  }

  @Test
  void aUnitTheMemberLeftIsGoneWithItsLotsOnTheNextPage() throws Exception {
    UUID staffel = orgUnit("SQUADRON", null);
    UUID kommando = orgUnit("SPECIAL_COMMAND", null);
    joins(member, staffel, "MEMBER");
    joins(member, kommando, "MEMBER");
    UUID titanium = material();
    UUID area18 = location();
    stock(other, titanium, area18, 1, 4, false, staffel);
    stock(other, titanium, area18, 1, 6, false, kommando);
    read(relayed(get(PATH))).andExpect(jsonPath("$.units.length()").value(2));

    jdbc.update(
        "DELETE FROM org_unit_membership WHERE user_id = ? AND org_unit_id = ?", member, kommando);

    read(relayed(get(PATH)))
        .andExpect(jsonPath("$.units.length()").value(1))
        .andExpect(jsonPath("$.units[0].id").value(staffel.toString()))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].unit.id").value(staffel.toString()));
  }

  @Test
  void withoutTheCapabilityTheOrgStockIsRefused() throws Exception {
    read(relayedWith(get(PATH), "exchange.stock.read"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  /**
   * Sends a request through the backend's security filter chain.
   *
   * @param request the relayed request
   * @return the answer to assert on
   * @throws Exception if the request fails
   */
  private ResultActions read(@NotNull MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request);
  }

  /**
   * Inserts and commits a material stock row.
   *
   * @param owner the holder
   * @param material the material
   * @param location the location
   * @param quality the quality
   * @param amount the amount
   * @param personal whether it is the holder's personal stock
   * @param pool the owning org unit, or {@code null}
   * @return the row id
   */
  private @NotNull UUID stock(
      @NotNull UUID owner,
      @NotNull UUID material,
      @NotNull UUID location,
      int quality,
      double amount,
      boolean personal,
      @Nullable UUID pool) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, owning_org_unit_id, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, false, ?, 0)
        """,
        id,
        owner,
        material,
        location,
        quality,
        amount,
        personal,
        pool);
    return id;
  }

  /**
   * Earmarks part of a row to a new mission.
   *
   * @param inventoryItem the row
   * @param amount the earmarked amount
   */
  private void earmarkToMission(@NotNull UUID inventoryItem, double amount) {
    UUID mission = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission (id, name, version) VALUES (?, ?, 0)", mission, "Op " + mission);
    missions.add(mission);
    jdbc.update(
        """
        INSERT INTO inventory_item_mission_allocation (id, inventory_item_id, mission_id, amount,
                                                       version, created_at, updated_at)
        VALUES (?, ?, ?, ?, 0, now(), now())
        """,
        UUID.randomUUID(),
        inventoryItem,
        mission,
        amount);
  }

  /**
   * Earmarks part of a row to a new job order of the unit.
   *
   * @param inventoryItem the row
   * @param amount the earmarked amount
   * @param unit the requesting and responsible unit
   */
  private void earmarkToJobOrder(@NotNull UUID inventoryItem, double amount, @NotNull UUID unit) {
    UUID order = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order (id, priority, created_at, updated_at, version, status,"
            + " requesting_org_unit_id, responsible_org_unit_id, type)"
            + " VALUES (?, 2, now(), now(), 0, 'OPEN', ?, ?, 'MATERIAL')",
        order,
        unit,
        unit);
    jobOrders.add(order);
    jdbc.update(
        """
        INSERT INTO inventory_item_job_order_allocation (id, inventory_item_id, job_order_id,
                                                         amount, delivered, version, created_at,
                                                         updated_at)
        VALUES (?, ?, ?, ?, false, 0, now(), now())
        """,
        UUID.randomUUID(),
        inventoryItem,
        order,
        amount);
  }

  /**
   * Seeds an SCU material.
   *
   * @return its id
   */
  private @NotNull UUID material() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'RAW', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        id,
        "OrgStock " + id);
    materials.add(id);
    return id;
  }

  /**
   * Seeds a location.
   *
   * @return its id
   */
  private @NotNull UUID location() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO location (id, name, hidden) VALUES (?, ?, false)", id, "OrgStock " + id);
    locations.add(id);
    return id;
  }

  /**
   * Seeds an active org unit.
   *
   * @param kind the discriminator value
   * @param parent the parent unit, or {@code null}
   * @return its id
   */
  private @NotNull UUID orgUnit(@NotNull String kind, @Nullable UUID parent) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible, parent_org_unit_id) VALUES (?, ?, ?, ?, TRUE, FALSE, FALSE,"
            + " ?)",
        id,
        kind,
        "Pool " + id,
        "P" + id.toString().substring(0, 8),
        parent);
    orgUnits.add(id);
    return id;
  }

  /**
   * Makes a user a direct member of an org unit.
   *
   * @param user the user
   * @param orgUnit the org unit
   * @param role the membership role
   */
  private void joins(@NotNull UUID user, @NotNull UUID orgUnit, @NotNull String role) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role) VALUES (?, ?, ?)",
        user,
        orgUnit,
        role);
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
   * Seeds a member with one role.
   *
   * @param username the username prefix
   * @param role the role code
   * @return the member's id
   */
  private @NotNull UUID user(@NotNull String username, @NotNull String role) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(role).orElseThrow())));
    UUID id = userRepository.saveAndFlush(user).getId();
    users.add(id);
    return id;
  }

  /**
   * Adds the gateway's identity and the relay headers with the read capability.
   *
   * @param request the request
   * @return the request
   */
  private @NotNull MockHttpServletRequestBuilder relayed(
      @NotNull MockHttpServletRequestBuilder request) {
    return relayedWith(request, SCOPE);
  }

  /**
   * Adds the gateway's identity and the relay headers.
   *
   * @param request the request
   * @param capabilities the relayed capabilities
   * @return the request
   */
  private @NotNull MockHttpServletRequestBuilder relayedWith(
      @NotNull MockHttpServletRequestBuilder request, @NotNull String capabilities) {
    return request
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capabilities)
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY);
  }
}
