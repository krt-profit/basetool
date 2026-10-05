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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.greluc.krt.profit.basetool.backend.exchange.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintNameNormalizer;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * An {@code ADMIN} acting through the exchange reads and writes only their own blueprints, stock
 * and ships, even with an admin pin on the request (REQ-XCH-009).
 */
@SpringBootTest
class ExchangeAdminActingMemberTest {

  private static final String BASE = "/api/v1/exchange/me";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "a".repeat(39);

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private BlueprintNameNormalizer normalizer;
  @Autowired private KnownExchangeClients knownClients;
  @Autowired private JdbcTemplate jdbc;

  private MockMvc mockMvc;
  private UUID admin;
  private UUID other;
  private String client;
  private boolean wasEnabled;
  private final List<UUID> blueprints = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();
  private final List<UUID> shipTypes = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    admin = user("xch-admin", Roles.ADMIN, Roles.KRT_MEMBER);
    other = user("xch-other", Roles.KRT_MEMBER);
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(
            ExchangeCapability.CONNECT,
            ExchangeCapability.BLUEPRINTS_READ,
            ExchangeCapability.BLUEPRINTS_WRITE,
            ExchangeCapability.STOCK_READ,
            ExchangeCapability.STOCK_WRITE,
            ExchangeCapability.HANGAR_READ,
            ExchangeCapability.HANGAR_WRITE));
    clientRepository.saveAndFlush(registered);
    knownClients.invalidate();
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    for (UUID id : List.of(admin, other)) {
      jdbc.update("DELETE FROM personal_blueprint WHERE owner_user_id = ?", id);
      jdbc.update("DELETE FROM material_exchange_offer WHERE owner_id = ?", id);
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", id);
      jdbc.update("DELETE FROM ship WHERE owner_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }
    blueprints.forEach(id -> jdbc.update("DELETE FROM blueprint WHERE id = ?", id));
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    locations.forEach(id -> jdbc.update("DELETE FROM location WHERE id = ?", id));
    shipTypes.forEach(id -> jdbc.update("DELETE FROM ship_type WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void anAdminReadsOnlyOwnBlueprintsStockAndShips() throws Exception {
    String mine = product("Admin Rifle");
    String theirs = product("Other Pistol");
    owns(admin, mine);
    owns(other, theirs);
    UUID laranite = material();
    UUID area18 = location();
    stock(admin, laranite, area18, 500, 2);
    stock(other, laranite, area18, 500, 9);
    stock(other, laranite, area18, 400, 4);
    UUID cutlass = shipType();
    UUID myShip = ship(admin, "Mine", cutlass);
    ship(other, "Theirs", cutlass);

    String blueprintPage = body(read("/blueprints", "exchange.blueprints.read"));
    assertThat(JsonPath.<List<String>>read(blueprintPage, "$.items[*].key")).containsExactly(mine);

    read("/stock", "exchange.stock.read")
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].quality").value(500))
        .andExpect(jsonPath("$.items[0].quantity.amount").value(2));

    read("/ships", "exchange.hangar.read")
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].shipId").value(myShip.toString()));
  }

  @Test
  void anAdminWritesOnlyOwnBlueprintsStockAndShips() throws Exception {
    String theirs = product("Other Pistol");
    owns(other, theirs);
    UUID laranite = material();
    UUID area18 = location();
    String place =
        jdbc.queryForObject("SELECT name FROM location WHERE id = ?", String.class, area18);
    stock(admin, laranite, area18, 500, 2);
    stock(other, laranite, area18, 500, 9);
    stock(other, laranite, area18, 400, 4);
    UUID cutlass = shipType();
    UUID theirShip = ship(other, "Theirs", cutlass);

    write("/blueprints/changes", "exchange.blueprints.write", ops(remove(theirs)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.unchanged").value(1));
    assertThat(owned(other)).containsExactly(theirs);

    write(
            "/stock/changes",
            "exchange.stock.write",
            ops(
                setQuantity(laranite, place, 500, "3", "2"),
                setQuantity(laranite, place, 400, "1", "4")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.results[0].index").value(1))
        .andExpect(jsonPath("$.results[0].reason").value("VERSION_CONFLICT"));
    assertThat(lot(admin, laranite, 500)).isEqualTo(3.0);
    assertThat(lot(other, laranite, 500)).isEqualTo(9.0);
    assertThat(lot(other, laranite, 400)).isEqualTo(4.0);

    write(
            "/ships/changes",
            "exchange.hangar.write",
            ops(
                "{\"op\":\"remove\",\"shipId\":\"%s\",\"version\":0}".formatted(theirShip),
                ("{\"op\":\"upsert\",\"externalId\":\"vk-1\",\"shipId\":\"%s\",\"version\":0,"
                        + "\"shipType\":{\"bt\":\"%s\"},\"name\":\"Taken\","
                        + "\"insurance\":{\"kind\":\"LTI\"}}")
                    .formatted(theirShip, cutlass)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].result").value("unmatched"))
        .andExpect(jsonPath("$.results[1].result").value("unmatched"));
    assertThat(
            jdbc.queryForObject(
                "SELECT name || ':' || owner_id FROM ship WHERE id = ?", String.class, theirShip))
        .isEqualTo("Theirs:" + other);
  }

  /**
   * Reads one exchange resource as the admin, with an admin pin on the request.
   *
   * @param resource the path below {@code /api/v1/exchange/me}
   * @param capability the relayed read capability
   * @return the answer, already checked for {@code 200}
   * @throws Exception if the request fails
   */
  private ResultActions read(@NotNull String resource, @NotNull String capability)
      throws Exception {
    return mockMvc.perform(relayed(get(BASE + resource), capability)).andExpect(status().isOk());
  }

  /**
   * Sends one change set as the admin, with an admin pin on the request.
   *
   * @param resource the path below {@code /api/v1/exchange/me}
   * @param capability the relayed write capability
   * @param json the change set
   * @return the answer
   * @throws Exception if the request fails
   */
  private ResultActions write(
      @NotNull String resource, @NotNull String capability, @NotNull String json) throws Exception {
    return mockMvc.perform(
        relayed(post(BASE + resource), capability)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  /**
   * Relays a request from the gateway for the admin, pinned to the IRIDIUM Squadron as a web
   * session of an admin could be.
   *
   * @param request the request
   * @param capability the relayed capability
   * @return the relayed request
   */
  private @NotNull MockHttpServletRequestBuilder relayed(
      @NotNull MockHttpServletRequestBuilder request, @NotNull String capability) {
    return request
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, admin.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capability)
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY)
        .header(OwnerScopeService.ACTIVE_ORG_UNIT_HEADER, Squadron.IRIDIUM_ID.toString());
  }

  /**
   * Returns the body of an answer.
   *
   * @param answer the answer
   * @return the body
   * @throws Exception if the body cannot be read
   */
  private static @NotNull String body(@NotNull ResultActions answer) throws Exception {
    return answer.andReturn().getResponse().getContentAsString();
  }

  /**
   * Joins ops into a change set.
   *
   * @param ops the ops as JSON
   * @return the change set
   */
  private static @NotNull String ops(@NotNull String... ops) {
    return "{\"ops\":[" + String.join(",", ops) + "]}";
  }

  /**
   * Builds a blueprint {@code remove} op.
   *
   * @param key the product key
   * @return the op
   */
  private static @NotNull String remove(@NotNull String key) {
    return "{\"op\":\"remove\",\"key\":\"%s\"}".formatted(key);
  }

  /**
   * Builds a stock {@code set-quantity} op in SCU.
   *
   * @param material the material id
   * @param place the location name
   * @param quality the quality
   * @param quantity the new amount
   * @param expected the amount the client believes the lot holds
   * @return the op
   */
  private static @NotNull String setQuantity(
      @NotNull UUID material,
      @NotNull String place,
      int quality,
      @NotNull String quantity,
      @NotNull String expected) {
    return ("{\"op\":\"set-quantity\",\"material\":{\"bt\":\"%s\"},\"location\":{\"name\":\"%s\"},"
            + "\"quality\":%d,\"stolen\":false,\"quantity\":{\"amount\":%s,\"unit\":\"SCU\"},"
            + "\"expectedQuantity\":{\"amount\":%s,\"unit\":\"SCU\"}}")
        .formatted(material, place, quality, quantity, expected);
  }

  /**
   * Seeds a catalogue blueprint and returns its product key.
   *
   * @param name the output name, made unique
   * @return the normalised product key
   */
  private @NotNull String product(@NotNull String name) {
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    blueprint.setScwikiKey("bp_" + UUID.randomUUID());
    blueprint.setOutputName(name + " " + UUID.randomUUID().toString().substring(0, 8));
    blueprint.setIsAvailableByDefault(false);
    Blueprint saved = blueprintRepository.saveAndFlush(blueprint);
    blueprints.add(saved.getId());
    return normalizer.normalize(saved.getOutputName());
  }

  /**
   * Gives a member a personal blueprint.
   *
   * @param owner the member
   * @param productKey the product key
   */
  private void owns(@NotNull UUID owner, @NotNull String productKey) {
    jdbc.update(
        """
        INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name)
        VALUES (?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        owner,
        productKey,
        productKey);
  }

  /**
   * Lists a member's personal blueprints.
   *
   * @param owner the member
   * @return the product keys
   */
  private @NotNull List<String> owned(@NotNull UUID owner) {
    return jdbc.queryForList(
        "SELECT product_key FROM personal_blueprint WHERE owner_user_id = ?", String.class, owner);
  }

  /**
   * Seeds a personal Lager row of a member.
   *
   * @param owner the member
   * @param material the material
   * @param location the location
   * @param quality the quality
   * @param amount the amount in SCU
   * @return the row id
   */
  private @NotNull UUID stock(
      @NotNull UUID owner,
      @NotNull UUID material,
      @NotNull UUID location,
      int quality,
      double amount) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, owning_org_unit_id, created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, true, false, NULL, ?, 0)
        """,
        id,
        owner,
        material,
        location,
        quality,
        amount,
        Timestamp.from(Instant.now().minusSeconds(10)));
    return id;
  }

  /**
   * Sums a member's personal rows of one material at one quality.
   *
   * @param owner the member
   * @param material the material
   * @param quality the quality
   * @return the lot's amount
   */
  private double lot(@NotNull UUID owner, @NotNull UUID material, int quality) {
    Double sum =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM inventory_item WHERE user_id = ?"
                + " AND material_id = ? AND quality = ?",
            Double.class,
            owner,
            material,
            quality);
    return sum == null ? 0 : sum;
  }

  /**
   * Seeds a refined SCU material.
   *
   * @return its id
   */
  private @NotNull UUID material() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, id_commodity, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'REFINED', 'SCU', NULL, false, false, true, 'UEX_ONLY')
        """,
        id,
        "Admin acting " + id);
    materials.add(id);
    return id;
  }

  /**
   * Seeds a visible location.
   *
   * @return its id
   */
  private @NotNull UUID location() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO location (id, name, hidden) VALUES (?, ?, false)", id, "Place " + id);
    locations.add(id);
    return id;
  }

  /**
   * Seeds a ship type.
   *
   * @return its id
   */
  private @NotNull UUID shipType() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO ship_type (id, name) VALUES (?, ?)", id, "Cutlass " + id);
    shipTypes.add(id);
    return id;
  }

  /**
   * Seeds a member's ship.
   *
   * @param owner the member
   * @param name the ship's name
   * @param type the ship type
   * @return its id
   */
  private @NotNull UUID ship(@NotNull UUID owner, @NotNull String name, @NotNull UUID type) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO ship (id, version, name, ship_type_id, insurance, location_id, fitted, owner_id)
        VALUES (?, 0, ?, ?, 'LTI', NULL, false, ?)
        """,
        id,
        name,
        type,
        owner);
    return id;
  }

  /**
   * Seeds an active member with the given roles.
   *
   * @param username the username prefix
   * @param roles the role codes
   * @return the member's id
   */
  private @NotNull UUID user(@NotNull String username, @NotNull String... roles) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    Set<Role> assigned = new HashSet<>();
    for (String role : roles) {
      assigned.add(roleRepository.findByCode(role).orElseThrow());
    }
    user.setRoles(assigned);
    return userRepository.saveAndFlush(user).getId();
  }
}
