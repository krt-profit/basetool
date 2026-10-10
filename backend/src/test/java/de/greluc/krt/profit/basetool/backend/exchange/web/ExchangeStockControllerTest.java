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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The member's stock lots, personal and shared, as a snapshot and a change feed, relayed from the
 * ingest gateway (REQ-XCH-013, REQ-XCH-016). Writes commit, because the feed reads only finished
 * transactions.
 */
@SpringBootTest
class ExchangeStockControllerTest {

  private static final String PATH = "/api/v1/exchange/me/stock";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "s".repeat(39);

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
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> items = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    member = user("stock-member").getId();
    other = user("stock-other").getId();
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(
            ExchangeCapability.CONNECT,
            ExchangeCapability.STOCK_READ,
            ExchangeCapability.STOCK_WRITE));
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
    for (UUID id : List.of(member, other)) {
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    items.forEach(id -> jdbc.update("DELETE FROM game_item WHERE id = ?", id));
    locations.forEach(id -> jdbc.update("DELETE FROM location WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aLotSumsTheMembersPersonalAndSharedRowsAcrossPoolsAndLeavesOtherMembersOut()
      throws Exception {
    UUID titanium = material("RAW", "SCU", 42);
    UUID area18 = location("Area18 " + UUID.randomUUID());
    List<UUID> pools = jdbc.queryForList("SELECT id FROM org_unit LIMIT 1", UUID.class);
    UUID pool = pools.isEmpty() ? null : pools.getFirst();
    stock(member, titanium, null, area18, 500, 1.25, true, false, null);
    stock(member, titanium, null, area18, 500, 2.0004, true, false, pool);
    stock(member, titanium, null, area18, 500, 7, false, false, pool);
    stock(member, titanium, null, area18, 500, 3, true, true, null);
    stock(other, titanium, null, area18, 500, 9, true, false, null);
    stock(other, titanium, null, area18, 500, 11, false, false, pool);

    read(relayed(get(PATH)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[?(@.stolen == false)].quantity.amount").value(10.25))
        .andExpect(jsonPath("$.items[?(@.stolen == false)].quantity.unit").value("SCU"))
        .andExpect(jsonPath("$.items[?(@.stolen == false)].quality").value(500))
        .andExpect(jsonPath("$.items[?(@.stolen == false)].material.bt").value(titanium.toString()))
        .andExpect(jsonPath("$.items[?(@.stolen == false)].materialKind.type").value("RAW"))
        .andExpect(jsonPath("$.items[?(@.stolen == false)].materialKind.commodity").value(true))
        .andExpect(jsonPath("$.items[?(@.stolen == true)].quantity.amount").value(3))
        .andExpect(jsonPath("$.items[0].location.name").exists())
        .andExpect(jsonPath("$.removed.length()").value(0))
        .andExpect(jsonPath("$.hasMore").value(false));
  }

  @Test
  void aMaterialKindCarriesTheUexFlagsItKnowsAndLeavesTheUnknownOut() throws Exception {
    UUID ore = material("RAW", "SCU", 43);
    jdbc.update(
        """
        UPDATE material SET is_mineral = 1, is_harvestable = 0, is_raw = 1, is_refined = 0,
                            is_buyable = 0, is_sellable = NULL
        WHERE id = ?
        """,
        ore);
    stock(member, ore, null, location("Daymar " + UUID.randomUUID()), 561, 4, true, false, null);

    read(relayed(get(PATH)))
        .andExpect(jsonPath("$.items[0].quality").value(561))
        .andExpect(jsonPath("$.items[0].materialKind.commodity").value(true))
        .andExpect(jsonPath("$.items[0].materialKind.mineral").value(true))
        .andExpect(jsonPath("$.items[0].materialKind.harvestable").value(false))
        .andExpect(jsonPath("$.items[0].materialKind.raw").value(true))
        .andExpect(jsonPath("$.items[0].materialKind.refined").value(false))
        .andExpect(jsonPath("$.items[0].materialKind.buyable").value(false))
        .andExpect(jsonPath("$.items[0].materialKind.sellable").doesNotExist());
  }

  @Test
  void anItemLotCountsWholePiecesAtQualityZeroWithoutAMaterialKind() throws Exception {
    UUID helmet = item();
    UUID orison = location("Orison " + UUID.randomUUID());
    stock(member, null, helmet, orison, null, 2, true, false, null);

    read(relayed(get(PATH)))
        .andExpect(jsonPath("$.items[0].material.bt").value(helmet.toString()))
        .andExpect(jsonPath("$.items[0].quality").value(0))
        .andExpect(jsonPath("$.items[0].quantity.unit").value("PIECE"))
        .andExpect(jsonPath("$.items[0].quantity.amount").value(2))
        .andExpect(jsonPath("$.items[0].materialKind").doesNotExist());
  }

  @Test
  void aSnapshotPagesByLotAndEndsAtTheFeed() throws Exception {
    UUID titanium = material("RAW", "SCU", null);
    for (int i = 0; i < 3; i++) {
      stock(
          member, titanium, null, location("Lager " + UUID.randomUUID()), 1, 1, true, false, null);
    }

    String first =
        read(relayed(get(PATH).param("limit", "2")))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String next = JsonPath.read(first, "$.nextCursor");
    assertThat(next).startsWith("s1.");

    read(relayed(get(PATH).param("limit", "2").param("cursor", next)))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.hasMore").value(false))
        .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.startsWith("f1.")));
  }

  @Test
  void theFeedAnswersAChangedAmountAndATombstoneForAnEmptiedLot() throws Exception {
    UUID titanium = material("RAW", "SCU", null);
    UUID area18 = location("Area18 " + UUID.randomUUID());
    UUID lorville = location("Lorville " + UUID.randomUUID());
    UUID kept = stock(member, titanium, null, area18, 1, 4, true, false, null);
    UUID gone = stock(member, titanium, null, lorville, 1, 5, true, false, null);
    String cursor = snapshotEnd();

    jdbc.update("UPDATE inventory_item SET amount = 6 WHERE id = ?", kept);
    jdbc.update("DELETE FROM inventory_item WHERE id = ?", gone);

    read(relayed(get(PATH).param("cursor", cursor)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].quantity.amount").value(6))
        .andExpect(jsonPath("$.removed.length()").value(1))
        .andExpect(
            jsonPath("$.removed[0].key").value("m:" + titanium + "|l:" + lorville + "|q:1|s:0"))
        .andExpect(jsonPath("$.removed[0].removedBy.channel").value("system"));
  }

  @Test
  void aRowRebookedToTheSharedPoolStaysInItsLotAndChangesNothingInTheFeed() throws Exception {
    UUID titanium = material("RAW", "SCU", null);
    UUID area18 = location("Area18 " + UUID.randomUUID());
    UUID row = stock(member, titanium, null, area18, 1, 4, true, false, null);
    String cursor = snapshotEnd();

    jdbc.update("UPDATE inventory_item SET personal = false WHERE id = ?", row);

    read(relayed(get(PATH).param("cursor", cursor)))
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.removed.length()").value(0));
    read(relayed(get(PATH)))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].quantity.amount").value(4));
  }

  @Test
  void aChangedSharedRowReachesTheFeed() throws Exception {
    UUID titanium = material("RAW", "SCU", null);
    UUID area18 = location("Area18 " + UUID.randomUUID());
    UUID row = stock(member, titanium, null, area18, 1, 4, false, false, null);
    String cursor = snapshotEnd();

    jdbc.update("UPDATE inventory_item SET amount = 9 WHERE id = ?", row);

    read(relayed(get(PATH).param("cursor", cursor)))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].quantity.amount").value(9));
  }

  @Test
  void withTheMarkingSwitchedOffAStolenLotIsRefusedAndNothingIsMarked() throws Exception {
    UUID titanium = material("RAW", "SCU", null);
    UUID place = location("Stolen off " + UUID.randomUUID());
    UUID row = stock(member, titanium, null, place, 500, 10, true, false, null);
    String name =
        jdbc.queryForObject("SELECT name FROM location WHERE id = ?", String.class, place);

    read(relayedWith(
            post(PATH + "/changes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"ops":[
                      {"op":"set-quantity","material":{"bt":"%1$s"},
                       "location":{"name":"%2$s"},"quality":500,"stolen":false,
                       "quantity":{"amount":6,"unit":"SCU"},
                       "expectedQuantity":{"amount":10,"unit":"SCU"}},
                      {"op":"set-quantity","material":{"bt":"%1$s"},
                       "location":{"name":"%2$s"},"quality":500,"stolen":true,
                       "quantity":{"amount":4,"unit":"SCU"},
                       "expectedQuantity":{"amount":0,"unit":"SCU"}}]}
                    """
                        .formatted(titanium, name)),
            "exchange.stock.write"))
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.results[0].index").value(1))
        .andExpect(jsonPath("$.results[0].reason").value("STOLEN_MARKING_DISABLED"));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_item WHERE user_id = ? AND stolen",
                Integer.class,
                member))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT amount FROM inventory_item WHERE id = ?", Double.class, row))
        .isEqualTo(6.0);
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
   * Inserts and commits a stock row.
   *
   * @param owner the owner
   * @param material the material, or {@code null} for an item row
   * @param item the item, or {@code null} for a material row
   * @param location the location
   * @param quality the quality, {@code null} for an item row
   * @param amount the amount
   * @param personal whether it is the owner's personal stock
   * @param stolen whether it is stolen
   * @param pool the owning org unit, or {@code null}
   * @return the row id
   */
  private @NotNull UUID stock(
      @NotNull UUID owner,
      @Nullable UUID material,
      @Nullable UUID item,
      @NotNull UUID location,
      @Nullable Integer quality,
      double amount,
      boolean personal,
      boolean stolen,
      @Nullable UUID pool) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, game_item_id, location_id, quality,
                                    amount, personal, stolen, owning_org_unit_id, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """,
        id,
        owner,
        material,
        item,
        location,
        quality,
        amount,
        personal,
        stolen,
        pool);
    return id;
  }

  /**
   * Seeds a material.
   *
   * @param type the refining type
   * @param unit the unit
   * @param commodity the UEX commodity id, or {@code null}
   * @return its id
   */
  private @NotNull UUID material(
      @NotNull String type, @NotNull String unit, @Nullable Integer commodity) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, id_commodity, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, ?, ?, ?, false, false, true, 'UEX_ONLY')
        """,
        id,
        "Stock " + id,
        type,
        unit,
        commodity == null ? null : commodity + Math.abs(id.hashCode() % 100000) * 10);
    materials.add(id);
    return id;
  }

  /**
   * Seeds a game item.
   *
   * @return its id
   */
  private @NotNull UUID item() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO game_item (id, name, kind, source_systems)
        VALUES (?, ?, 'GENERIC', 'UEX_ONLY')
        """,
        id,
        "Helmet " + id);
    items.add(id);
    return id;
  }

  /**
   * Seeds a location.
   *
   * @param name the unique name
   * @return its id
   */
  private @NotNull UUID location(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO location (id, name, hidden) VALUES (?, ?, false)", id, name);
    locations.add(id);
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
    return relayedWith(request, "exchange.stock.read");
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
