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
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The member's own refinery orders as a snapshot of the open and running orders and a change feed
 * of every order, relayed from the ingest gateway (REQ-XCH-051). Writes commit, because the feed
 * reads only finished transactions.
 */
@SpringBootTest
class ExchangeRefineryOrderControllerTest {

  private static final String PATH = "/api/v1/exchange/me/refinery-orders";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "r".repeat(39);

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private JdbcTemplate jdbc;

  private MockMvc mockMvc;
  private UUID member;
  private UUID other;
  private String client;
  private boolean wasEnabled;
  private UUID refinery;
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> methods = new ArrayList<>();
  private final List<UUID> orgUnits = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    member = user("refinery-member").getId();
    other = user("refinery-other").getId();
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.REFINERY_READ));
    clientRepository.saveAndFlush(registered);
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
    refinery = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, refinery, "ARC-L1 Refinery " + refinery);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    for (UUID id : List.of(member, other)) {
      jdbc.update(
          "DELETE FROM refinery_good WHERE refinery_order_id IN"
              + " (SELECT id FROM refinery_order WHERE owner_id = ?)",
          id);
      jdbc.update("DELETE FROM refinery_order WHERE owner_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }
    jdbc.update("DELETE FROM location WHERE id = ?", refinery);
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    methods.forEach(id -> jdbc.update("DELETE FROM refining_method WHERE id = ?", id));
    orgUnits.forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void theSnapshotHoldsTheMembersOpenAndRunningOrdersInTheContractShape() throws Exception {
    UUID method = method("DINYX", "Dinyx Solventation");
    UUID unit = orgUnit();
    UUID quantainium = material("Quantainium");
    UUID refined = material("Quantainium (Refined)");
    UUID open = order(member, "OPEN");
    jdbc.update(
        """
        UPDATE refinery_order SET refining_method_id = ?, owning_org_unit_id = ?,
               started_at = ?, duration_minutes = 90, expenses = 1250.5, ore_sales = 400,
               game_data_version = 3, finances_version = 1
        WHERE id = ?
        """,
        method,
        unit,
        Timestamp.from(Instant.parse("2026-11-08T10:00:00Z")),
        open);
    UUID good = good(open, quantainium, refined);
    UUID running = order(member, "IN_PROGRESS");
    jdbc.update(
        "UPDATE refinery_order SET created_source = 'CLIENT', created_client = ? WHERE id = ?",
        client,
        running);
    order(member, "COMPLETED");
    order(member, "CANCELED");
    order(other, "OPEN");

    String body =
        read(relayed(get(PATH)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.removed.length()").value(0))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String first = "$.items[?(@.id == '" + open + "')]";
    assertThat(JsonPath.<List<String>>read(body, first + ".status")).containsExactly("OPEN");
    assertThat(JsonPath.<List<Integer>>read(body, first + ".versions.gameData")).containsExactly(3);
    assertThat(JsonPath.<List<Integer>>read(body, first + ".versions.finances")).containsExactly(1);
    assertThat(JsonPath.<List<String>>read(body, first + ".location.name"))
        .containsExactly("ARC-L1 Refinery " + refinery);
    assertThat(JsonPath.<List<String>>read(body, first + ".method.code")).containsExactly("DINYX");
    assertThat(JsonPath.<List<String>>read(body, first + ".method.name"))
        .containsExactly("Dinyx Solventation " + method);
    assertThat(JsonPath.<List<String>>read(body, first + ".startedAt"))
        .containsExactly("2026-11-08T10:00:00Z");
    assertThat(JsonPath.<List<Integer>>read(body, first + ".durationMinutes")).containsExactly(90);
    assertThat(JsonPath.<List<String>>read(body, first + ".endsAt"))
        .containsExactly("2026-11-08T11:30:00Z");
    assertThat(JsonPath.<List<Double>>read(body, first + ".expenses")).containsExactly(1250.5);
    assertThat(JsonPath.<List<Double>>read(body, first + ".oreSales")).containsExactly(400.0);
    assertThat(JsonPath.<List<Object>>read(body, first + ".otherExpenses")).isEmpty();
    assertThat(JsonPath.<List<String>>read(body, first + ".orgUnitId"))
        .containsExactly(unit.toString());
    assertThat(JsonPath.<List<Object>>read(body, first + ".owner")).isEmpty();
    assertThat(JsonPath.<List<Object>>read(body, first + ".createdBy")).isEmpty();
    assertThat(JsonPath.<List<Object>>read(body, first + ".pickedUpAt")).isEmpty();
    assertThat(JsonPath.<List<String>>read(body, first + ".goods[0].id"))
        .containsExactly(good.toString());
    assertThat(JsonPath.<List<String>>read(body, first + ".goods[0].input.bt"))
        .containsExactly(quantainium.toString());
    assertThat(JsonPath.<List<String>>read(body, first + ".goods[0].output.bt"))
        .containsExactly(refined.toString());
    assertThat(JsonPath.<List<Integer>>read(body, first + ".goods[0].inputQuantity"))
        .containsExactly(1200);
    assertThat(JsonPath.<List<Integer>>read(body, first + ".goods[0].outputQuantity"))
        .containsExactly(900);
    assertThat(JsonPath.<List<Integer>>read(body, first + ".goods[0].quality"))
        .containsExactly(650);

    String second = "$.items[?(@.id == '" + running + "')]";
    assertThat(JsonPath.<List<String>>read(body, second + ".status"))
        .containsExactly("IN_PROGRESS");
    assertThat(JsonPath.<List<Object>>read(body, second + ".method")).isEmpty();
    assertThat(JsonPath.<List<String>>read(body, second + ".createdBy.clientId"))
        .containsExactly(client);
    assertThat(JsonPath.<List<String>>read(body, second + ".createdBy.name"))
        .containsExactly("VerseKit");
    assertThat(JsonPath.<List<Object>>read(body, second + ".endsAt")).isEmpty();
    assertThat(JsonPath.<List<List<Object>>>read(body, second + ".goods"))
        .containsExactly(List.of());
  }

  @Test
  void aSnapshotPagesByOrderAndEndsAtTheFeed() throws Exception {
    for (int i = 0; i < 3; i++) {
      order(member, "OPEN");
    }

    String first =
        read(relayed(get(PATH).param("limit", "2")))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String next = JsonPath.read(first, "$.nextCursor");

    String second =
        read(relayed(get(PATH).param("limit", "2").param("cursor", next)))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat((String) JsonPath.read(second, "$.nextCursor")).startsWith("f1.");
  }

  @Test
  void theFeedCarriesACompletedACancelledAndAReopenedOrderAsChanges() throws Exception {
    UUID completed = order(member, "OPEN");
    UUID cancelled = order(member, "IN_PROGRESS");
    UUID reopened = order(member, "COMPLETED");
    order(member, "OPEN");
    String cursor = snapshotEnd();

    jdbc.update(
        """
        UPDATE refinery_order SET status = 'COMPLETED', picked_up_at = ?, picked_up_client = ?,
               created_source = 'CLIENT', created_client = 'retired-client'
        WHERE id = ?
        """,
        Timestamp.from(Instant.parse("2026-11-08T09:12:00Z")),
        client,
        completed);
    jdbc.update("UPDATE refinery_order SET status = 'CANCELED' WHERE id = ?", cancelled);
    jdbc.update("UPDATE refinery_order SET status = 'OPEN' WHERE id = ?", reopened);

    String body =
        read(relayed(get(PATH).param("cursor", cursor)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.removed.length()").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<List<String>>read(body, "$.items[?(@.id == '" + completed + "')].status"))
        .containsExactly("COMPLETED");
    String done = "$.items[?(@.id == '" + completed + "')]";
    assertThat(JsonPath.<List<String>>read(body, done + ".pickedUpAt"))
        .containsExactly("2026-11-08T09:12:00Z");
    assertThat(JsonPath.<List<String>>read(body, done + ".createdBy.name"))
        .containsExactly("retired-client");
    assertThat(JsonPath.<List<String>>read(body, "$.items[?(@.id == '" + cancelled + "')].status"))
        .containsExactly("CANCELED");
    assertThat(JsonPath.<List<String>>read(body, "$.items[?(@.id == '" + reopened + "')].status"))
        .containsExactly("OPEN");
  }

  @Test
  void aHardDeleteAndAMoveToAnotherOwnerLeaveTombstones() throws Exception {
    UUID deleted = order(member, "OPEN");
    good(deleted, material("Bexalite"), material("Bexalite (Refined)"));
    UUID moved = order(member, "IN_PROGRESS");
    String cursor = snapshotEnd();

    jdbc.update("DELETE FROM refinery_good WHERE refinery_order_id = ?", deleted);
    jdbc.update("DELETE FROM refinery_order WHERE id = ?", deleted);
    jdbc.update("UPDATE refinery_order SET owner_id = ? WHERE id = ?", other, moved);

    String body =
        read(relayed(get(PATH).param("cursor", cursor)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.removed.length()").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<List<String>>read(body, "$.removed[*].key"))
        .containsExactlyInAnyOrder(deleted.toString(), moved.toString());
    assertThat(JsonPath.<List<String>>read(body, "$.removed[*].removedBy.channel"))
        .containsOnly("system");
  }

  @Test
  void aGoodsChangeAnnouncesItsOrderOnceAndLeavesTheOthersOut() throws Exception {
    UUID changed = order(member, "OPEN");
    UUID good = good(changed, material("Taranite"), material("Taranite (Refined)"));
    good(changed, material("Agricium"), material("Agricium (Refined)"));
    order(member, "OPEN");
    String cursor = snapshotEnd();

    jdbc.update("UPDATE refinery_good SET quality = 800 WHERE refinery_order_id = ?", changed);

    read(relayed(get(PATH).param("cursor", cursor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(changed.toString()))
        .andExpect(jsonPath("$.items[0].goods.length()").value(2))
        .andExpect(jsonPath("$.items[0].goods[?(@.id == '" + good + "')].quality").value(800));
  }

  @Test
  void renamingTheRefineryReannouncesItsOrders() throws Exception {
    UUID order = order(member, "OPEN");
    String cursor = snapshotEnd();

    jdbc.update("UPDATE location SET name = ? WHERE id = ?", "Renamed " + refinery, refinery);

    read(relayed(get(PATH).param("cursor", cursor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(order.toString()))
        .andExpect(jsonPath("$.items[0].location.name").value("Renamed " + refinery));
  }

  @Test
  void withoutTheReadCapabilityTheFeedIsRefused() throws Exception {
    read(relayedWith(get(PATH), "exchange.connect")).andExpect(status().isForbidden());
  }

  /**
   * Reads the snapshot to its end.
   *
   * @return the feed cursor it ends with
   * @throws Exception if the request fails
   */
  private @NotNull String snapshotEnd() throws Exception {
    String body = read(relayed(get(PATH))).andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.nextCursor");
  }

  /**
   * Performs a request.
   *
   * @param request the request
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions read(@NotNull MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request);
  }

  /**
   * Inserts and commits a refinery order at the test refinery.
   *
   * @param owner the owner
   * @param status the order's status
   * @return its id
   */
  private @NotNull UUID order(@NotNull UUID owner, @NotNull String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO refinery_order (id, version, owner_id, location_id, status, last_changed_at)"
            + " VALUES (?, 0, ?, ?, ?, now())",
        id,
        owner,
        refinery,
        status);
    return id;
  }

  /**
   * Inserts and commits a good of 1200 units at quality 650 that yields 900 units.
   *
   * @param order the order
   * @param input the raw input material
   * @param output the refined output material
   * @return its id
   */
  private @NotNull UUID good(@NotNull UUID order, @NotNull UUID input, @NotNull UUID output) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO refinery_good (id, version, input_material_id, input_quantity,
                                   output_material_id, output_quantity, quality, refinery_order_id)
        VALUES (?, 0, ?, 1200, ?, 900, 650, ?)
        """,
        id,
        input,
        output,
        order);
    return id;
  }

  /**
   * Seeds a material.
   *
   * @param name the name prefix
   * @return its id
   */
  private @NotNull UUID material(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'RAW', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        id,
        name + " " + id);
    materials.add(id);
    return id;
  }

  /**
   * Seeds a refining method named {@code <name> <id>}.
   *
   * @param code the method's code
   * @param name the name prefix
   * @return its id
   */
  private @NotNull UUID method(@NotNull String code, @NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO refining_method (id, name, code) VALUES (?, ?, ?)", id, name + " " + id, code);
    methods.add(id);
    return id;
  }

  /**
   * Seeds an org unit.
   *
   * @return its id
   */
  private @NotNull UUID orgUnit() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active,"
            + " is_profit_eligible) VALUES (?, 'SQUADRON', ?, ?, TRUE, FALSE)",
        id,
        "Unit " + id,
        "U" + id.toString().substring(0, 8));
    orgUnits.add(id);
    return id;
  }

  /**
   * Seeds a member.
   *
   * @param username the username prefix
   * @return the member
   */
  private @NotNull User user(@NotNull String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    return userRepository.saveAndFlush(user);
  }

  /**
   * Adds the gateway's identity and the relay headers with the read capability.
   *
   * @param request the request
   * @return the request
   */
  private @NotNull MockHttpServletRequestBuilder relayed(
      @NotNull MockHttpServletRequestBuilder request) {
    return relayedWith(request, "exchange.refinery.read");
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
