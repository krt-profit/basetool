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
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeJournalRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.test.web.servlet.result.JsonPathResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A client takes part or all of the member's own shared stock in a pool back to personal, relayed
 * from the ingest gateway (REQ-XCH-016, ADR-0278). Writes commit.
 */
@SpringBootTest
class ExchangeStockUnshareControllerTest {

  private static final String PATH = "/api/v1/exchange/me/stock";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Sx9_" + "u".repeat(39);

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private ExchangeJournalRepository journalRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private KnownExchangeClients knownClients;
  @Autowired private MeterRegistry meterRegistry;

  private MockMvc mockMvc;
  private UUID member;
  private String client;
  private boolean wasEnabled;
  private final List<UUID> users = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> items = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();
  private final List<UUID> orgUnits = new ArrayList<>();
  private final List<UUID> missions = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    member = user("stock-unsharer-");
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
    for (UUID user : users) {
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", user);
    }
    missions.forEach(id -> jdbc.update("DELETE FROM mission WHERE id = ?", id));
    for (UUID user : users) {
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", user);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", user);
      jdbc.update("DELETE FROM app_user WHERE id = ?", user);
    }
    orgUnits.forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    items.forEach(id -> jdbc.update("DELETE FROM game_item WHERE id = ?", id));
    locations.forEach(id -> jdbc.update("DELETE FROM location WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void anUnshareMovesExactlyTheQuantityBackToPersonalAndKeepsTheLot() throws Exception {
    UUID laranite = material();
    UUID area18 = location();
    UUID squadron = orgUnit();
    joins(squadron);
    UUID shared = sharedRow(member, laranite, area18, 10, squadron);
    String key = "m:" + laranite + "|l:" + area18 + "|q:500|s:0";
    String cursor = snapshotEnd();

    change(ops(unshare(laranite, locationName(area18), squadron, "4", "10")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.notApplied").value(0));

    assertThat(amount(shared)).isEqualTo(6.0);
    assertThat(personal(laranite)).isEqualTo(4.0);
    read(get(PATH).param("cursor", cursor))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].key").value(key))
        .andExpect(jsonPath("$.items[0].quantity.amount").value(10))
        .andExpect(jsonPath("$.items[0].split.personal.amount").value(4))
        .andExpect(pool(squadron).value(6));
    assertThat(
            jdbc.queryForList(
                "SELECT client_id FROM audit_event WHERE subject_id = ?"
                    + " AND event_type = 'INVENTORY_ITEM_PERSONALIZED'",
                String.class,
                shared))
        .containsExactly(client);
    assertThat(journalRepository.findAllByUserIdOrderByRecordedAtAsc(member))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.getAction().name()).isEqualTo("STOCK_UNSHARE");
              assertThat(e.getEntityKey()).isEqualTo(key);
              assertThat(e.isRemoval()).isFalse();
            });
    assertThat(writes("applied")).isEqualTo(1.0);
  }

  @Test
  void anUnshareFromThePoolOfAUnitTheMemberHasLeftSucceeds() throws Exception {
    UUID laranite = material();
    UUID area18 = location();
    UUID left = orgUnit();
    sharedRow(member, laranite, area18, 5, left);

    change(ops(unshare(laranite, locationName(area18), left, "5", "5")))
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(pooled(member, left)).isZero();
    assertThat(personal(laranite)).isEqualTo(5.0);
  }

  @Test
  void anotherMembersRowsInThePoolAreNeverTouched() throws Exception {
    UUID laranite = material();
    UUID area18 = location();
    UUID squadron = orgUnit();
    joins(squadron);
    UUID peer = user("stock-peer-");
    UUID theirs = sharedRow(peer, laranite, area18, 20, squadron);
    sharedRow(member, laranite, area18, 3, squadron);
    String place = locationName(area18);

    change(ops(unshare(laranite, place, squadron, "5", "3")))
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].reason").value("QUANTITY_ABOVE_EXPECTED"));
    change(ops(unshare(laranite, place, squadron, "5", "23")))
        .andExpect(jsonPath("$.results[0].reason").value("VERSION_CONFLICT"));
    change(ops(unshare(laranite, place, squadron, "3", "3")))
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(amount(theirs)).isEqualTo(20.0);
    assertThat(pooled(member, squadron)).isZero();
    assertThat(personal(laranite)).isEqualTo(3.0);
  }

  @Test
  void aStaleExpectationAndAQuantityAboveItAreRefusedWithNothingWritten() throws Exception {
    UUID laranite = material();
    UUID area18 = location();
    UUID squadron = orgUnit();
    joins(squadron);
    sharedRow(member, laranite, area18, 10, squadron);
    String place = locationName(area18);

    change(ops(unshare(laranite, place, squadron, "4", "12")))
        .andExpect(jsonPath("$.results[0].result").value("rejected"))
        .andExpect(jsonPath("$.results[0].reason").value("VERSION_CONFLICT"));
    change(ops(unshare(laranite, place, squadron, "11", "10")))
        .andExpect(jsonPath("$.results[0].reason").value("QUANTITY_ABOVE_EXPECTED"));

    assertThat(pooled(member, squadron)).isEqualTo(10.0);
    assertThat(personal(laranite)).isZero();
    assertThat(journalRepository.findAllByUserIdOrderByRecordedAtAsc(member)).isEmpty();
  }

  @Test
  void earmarkedSharedStockStaysInThePool() throws Exception {
    UUID laranite = material();
    UUID area18 = location();
    UUID squadron = orgUnit();
    joins(squadron);
    UUID shared = sharedRow(member, laranite, area18, 10, squadron);
    reserve(shared, 2);

    change(ops(unshare(laranite, locationName(area18), squadron, "4", "10")))
        .andExpect(jsonPath("$.results[0].reason").value("STOCK_EARMARKED"));

    assertThat(amount(shared)).isEqualTo(10.0);
    assertThat(personal(laranite)).isZero();
    assertThat(journalRepository.findAllByUserIdOrderByRecordedAtAsc(member)).isEmpty();
  }

  @Test
  void aRebookingAfterOneTheLagerRefusedOnTheSameLotIsAVersionConflict() throws Exception {
    UUID laranite = material();
    UUID area18 = location();
    UUID squadron = orgUnit();
    joins(squadron);
    personalRow(laranite, area18, 10);
    UUID shared = sharedRow(member, laranite, area18, 5, squadron);
    reserve(shared, 2);
    String place = locationName(area18);

    change(
            ops(
                unshare(laranite, place, squadron, "5", "5"),
                share(laranite, place, squadron, "3", "15")))
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].reason").value("STOCK_EARMARKED"))
        .andExpect(jsonPath("$.results[1].reason").value("VERSION_CONFLICT"));

    assertThat(personal(laranite)).isEqualTo(10.0);
    assertThat(pooled(member, squadron)).isEqualTo(5.0);
    assertThat(journalRepository.findAllByUserIdOrderByRecordedAtAsc(member)).isEmpty();
  }

  @Test
  void anItemMovesBackInWholePiecesOnly() throws Exception {
    UUID helmet = item();
    UUID orison = location();
    UUID squadron = orgUnit();
    joins(squadron);
    UUID pieces = sharedItemRow(helmet, orison, 5, squadron);
    String ops =
        """
        {"ops":[{"op":"unshare","material":{"bt":"%1$s"},"location":{"name":"%2$s"},
                 "quality":0,"stolen":false,"orgUnit":{"id":"%3$s"},
                 "quantity":{"amount":1.5,"unit":"PIECE"},
                 "expectedQuantity":{"amount":5,"unit":"PIECE"}},
                {"op":"unshare","material":{"bt":"%1$s"},"location":{"name":"%2$s"},
                 "quality":0,"stolen":false,"orgUnit":{"id":"%3$s"},
                 "quantity":{"amount":2,"unit":"PIECE"},
                 "expectedQuantity":{"amount":5,"unit":"PIECE"}}]}
        """
            .formatted(helmet, locationName(orison), squadron);

    change(ops)
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.results[0].index").value(0))
        .andExpect(jsonPath("$.results[0].reason").value("UNIT_MISMATCH"));

    assertThat(amount(pieces)).isEqualTo(3.0);
  }

  @Test
  void aShareAndAnUnshareOfOneLotAreDecidedInOrder() throws Exception {
    UUID laranite = material();
    UUID area18 = location();
    UUID squadron = orgUnit();
    joins(squadron);
    personalRow(laranite, area18, 10);
    String place = locationName(area18);

    change(
            ops(
                share(laranite, place, squadron, "6", "10"),
                unshare(laranite, place, squadron, "2", "0"),
                unshare(laranite, place, squadron, "2", "6"),
                set(laranite, place, "11", "10")))
        .andExpect(jsonPath("$.applied").value(3))
        .andExpect(jsonPath("$.results[0].index").value(1))
        .andExpect(jsonPath("$.results[0].reason").value("VERSION_CONFLICT"));

    assertThat(pooled(member, squadron)).isEqualTo(4.0);
    assertThat(personal(laranite)).isEqualTo(7.0);
  }

  @Test
  void aDryRunDecidesTheUnshareAndWritesNothing() throws Exception {
    UUID laranite = material();
    UUID area18 = location();
    UUID squadron = orgUnit();
    joins(squadron);
    sharedRow(member, laranite, area18, 10, squadron);
    String place = locationName(area18);

    change(
            "{\"dryRun\":true,\"ops\":["
                + unshare(laranite, place, squadron, "4", "10")
                + ","
                + unshare(laranite, place, squadron, "4", "10")
                + "]}")
        .andExpect(jsonPath("$.dryRun").value(true))
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.results[0].index").value(1))
        .andExpect(jsonPath("$.results[0].reason").value("VERSION_CONFLICT"));

    assertThat(pooled(member, squadron)).isEqualTo(10.0);
    assertThat(personal(laranite)).isZero();
    assertThat(journalRepository.findAllByUserIdOrderByRecordedAtAsc(member)).isEmpty();
    assertThat(writes("applied")).isZero();
  }

  @Test
  void unsharingEveryLotWhollyIsNeverHeldBackAsAMassChange() throws Exception {
    UUID laranite = material();
    UUID squadron = orgUnit();
    joins(squadron);
    List<String> all = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      UUID place = location();
      sharedRow(member, laranite, place, 10, squadron);
      all.add(unshare(laranite, locationName(place), squadron, "10", "10"));
    }

    change(ops(all.toArray(String[]::new)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(5));

    assertThat(pooled(member, squadron)).isZero();
    assertThat(personal(laranite)).isEqualTo(50.0);
  }

  @Test
  void anUnshareWithoutAUnitIsRefusedAsInvalid() throws Exception {
    UUID laranite = material();
    UUID area18 = location();
    UUID squadron = orgUnit();
    sharedRow(member, laranite, area18, 10, squadron);

    change(
            """
            {"ops":[{"op":"unshare","material":{"bt":"%s"},"location":{"name":"%s"},
                     "quality":500,"stolen":false,"quantity":{"amount":4,"unit":"SCU"},
                     "expectedQuantity":{"amount":10,"unit":"SCU"}}]}
            """
                .formatted(laranite, locationName(area18)))
        .andExpect(status().isBadRequest());

    assertThat(pooled(member, squadron)).isEqualTo(10.0);
  }

  /**
   * Reads how many of the test client's stock writes ended with an outcome.
   *
   * @param outcome the outcome tag
   * @return the count, 0 when none was recorded
   */
  private double writes(@NotNull String outcome) {
    Counter counter =
        meterRegistry
            .find(MetricNames.EXCHANGE_WRITES)
            .tag(MetricNames.TAG_CLIENT_ID, client)
            .tag(MetricNames.TAG_RESOURCE, "stock")
            .tag(MetricNames.TAG_OUTCOME, outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  /**
   * Wraps ops into a change set.
   *
   * @param ops the ops as JSON
   * @return the change set as JSON
   */
  private static @NotNull String ops(@NotNull String... ops) {
    return "{\"ops\":[" + String.join(",", ops) + "]}";
  }

  /**
   * Builds one unshare op of a not-stolen SCU lot at quality 500.
   *
   * @param material the material
   * @param place the location name
   * @param unit the pool's unit
   * @param quantity the amount to move
   * @param expected the pool's amount the client last saw
   * @return the op as JSON
   */
  private static @NotNull String unshare(
      @NotNull UUID material,
      @NotNull String place,
      @NotNull UUID unit,
      @NotNull String quantity,
      @NotNull String expected) {
    return op("unshare", material, place, unit, quantity, expected);
  }

  /**
   * Builds one share op of a not-stolen SCU lot at quality 500.
   *
   * @param material the material
   * @param place the location name
   * @param unit the receiving unit
   * @param quantity the amount to move
   * @param expected the personal amount the client last saw
   * @return the op as JSON
   */
  private static @NotNull String share(
      @NotNull UUID material,
      @NotNull String place,
      @NotNull UUID unit,
      @NotNull String quantity,
      @NotNull String expected) {
    return op("share", material, place, unit, quantity, expected);
  }

  /**
   * Builds one rebooking op of a not-stolen SCU lot at quality 500.
   *
   * @param op the op's name
   * @param material the material
   * @param place the location name
   * @param unit the pool's unit
   * @param quantity the amount to move
   * @param expected the amount the client last saw
   * @return the op as JSON
   */
  private static @NotNull String op(
      @NotNull String op,
      @NotNull UUID material,
      @NotNull String place,
      @NotNull UUID unit,
      @NotNull String quantity,
      @NotNull String expected) {
    return """
    {"op":"%s","material":{"bt":"%s"},"location":{"name":"%s"},"quality":500,"stolen":false,
     "orgUnit":{"id":"%s"},"quantity":{"amount":%s,"unit":"SCU"},
     "expectedQuantity":{"amount":%s,"unit":"SCU"}}\
    """
        .formatted(op, material, place, unit, quantity, expected);
  }

  /**
   * Builds one set-quantity op of a not-stolen SCU lot at quality 500.
   *
   * @param material the material
   * @param place the location name
   * @param quantity the new quantity
   * @param expected the quantity the client last saw
   * @return the op as JSON
   */
  private static @NotNull String set(
      @NotNull UUID material,
      @NotNull String place,
      @NotNull String quantity,
      @NotNull String expected) {
    return """
    {"op":"set-quantity","material":{"bt":"%s"},"location":{"name":"%s"},"quality":500,
     "stolen":false,"quantity":{"amount":%s,"unit":"SCU"},
     "expectedQuantity":{"amount":%s,"unit":"SCU"}}\
    """
        .formatted(material, place, quantity, expected);
  }

  /**
   * Posts a change set as the test client.
   *
   * @param json the change set
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions change(@NotNull String json) throws Exception {
    return relay(
        post(PATH + "/changes").contentType(MediaType.APPLICATION_JSON).content(json),
        "exchange.stock.write");
  }

  /**
   * Reads the member's lots as the test client.
   *
   * @param request the read
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions read(@NotNull MockHttpServletRequestBuilder request) throws Exception {
    return relay(request, "exchange.stock.read");
  }

  /**
   * Sends a request as the ingest gateway acting for the member through the test client.
   *
   * @param request the request
   * @param capability the relayed capability
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions relay(
      @NotNull MockHttpServletRequestBuilder request, @NotNull String capability) throws Exception {
    return mockMvc.perform(
        request
            .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
            .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
            .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
            .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capability)
            .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY));
  }

  /**
   * Reads the snapshot to its end.
   *
   * @return the feed cursor it ends with
   * @throws Exception if the request fails
   */
  private @NotNull String snapshotEnd() throws Exception {
    String body = read(get(PATH)).andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.nextCursor");
  }

  /**
   * Selects the amount of the first lot's split entry for one pool.
   *
   * @param pool the pool's unit
   * @return the matcher
   */
  private static @NotNull JsonPathResultMatchers pool(@NotNull UUID pool) {
    return jsonPath("$.items[0].split.pools[?(@.orgUnit.id == '" + pool + "')].quantity.amount");
  }

  /**
   * Seeds an active member.
   *
   * @param prefix the username's prefix
   * @return the member's id
   */
  private @NotNull UUID user(@NotNull String prefix) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    UUID id = userRepository.saveAndFlush(user).getId();
    users.add(id);
    return id;
  }

  /**
   * Seeds a squadron.
   *
   * @return its id
   */
  private @NotNull UUID orgUnit() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible) VALUES (?, 'SQUADRON', ?, ?, TRUE, FALSE, FALSE)",
        id,
        "Unit " + id,
        "U" + id.toString().substring(0, 6));
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
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role) VALUES (?, ?, 'MEMBER')",
        member,
        orgUnit);
  }

  /**
   * Seeds a personal Lager row of the member without an org unit at quality 500.
   *
   * @param material the material
   * @param location the location
   * @param amount the amount
   * @return the row
   */
  private @NotNull UUID personalRow(@NotNull UUID material, @NotNull UUID location, double amount) {
    return insert(member, material, location, amount, true, null);
  }

  /**
   * Seeds a shared Lager row at quality 500.
   *
   * @param holder the member who holds it
   * @param material the material
   * @param location the location
   * @param amount the amount
   * @param unit the pool's unit
   * @return the row
   */
  private @NotNull UUID sharedRow(
      @NotNull UUID holder,
      @NotNull UUID material,
      @NotNull UUID location,
      double amount,
      @NotNull UUID unit) {
    return insert(holder, material, location, amount, false, unit);
  }

  private @NotNull UUID insert(
      @NotNull UUID holder,
      @NotNull UUID material,
      @NotNull UUID location,
      double amount,
      boolean personal,
      @Nullable UUID unit) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, owning_org_unit_id, created_at, version)
        VALUES (?, ?, ?, ?, 500, ?, ?, false, ?, ?, 0)
        """,
        id,
        holder,
        material,
        location,
        amount,
        personal,
        unit,
        Timestamp.from(Instant.now().minusSeconds(3600)));
    return id;
  }

  /**
   * Seeds a shared item row of the member.
   *
   * @param item the item
   * @param location the location
   * @param amount the pieces
   * @param unit the pool's unit
   * @return the row
   */
  private @NotNull UUID sharedItemRow(
      @NotNull UUID item, @NotNull UUID location, double amount, @NotNull UUID unit) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, game_item_id, location_id, amount, personal,
                                    stolen, owning_org_unit_id, created_at, version)
        VALUES (?, ?, ?, ?, ?, false, false, ?, now(), 0)
        """,
        id,
        member,
        item,
        location,
        amount,
        unit);
    return id;
  }

  /**
   * Reserves part of a row for a new mission.
   *
   * @param inventoryItem the row
   * @param amount the reserved amount
   */
  private void reserve(@NotNull UUID inventoryItem, double amount) {
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

  private @NotNull UUID material() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'REFINED', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        id,
        "Stock unshare " + id);
    materials.add(id);
    return id;
  }

  private @NotNull UUID item() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO game_item (id, name, kind, source_systems) VALUES (?, ?, 'GENERIC',"
            + " 'UEX_ONLY')",
        id,
        "Helmet " + id);
    items.add(id);
    return id;
  }

  private @NotNull UUID location() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO location (id, name, hidden) VALUES (?, ?, false)", id, "Place " + id);
    locations.add(id);
    return id;
  }

  private @NotNull String locationName(@NotNull UUID location) {
    return jdbc.queryForObject("SELECT name FROM location WHERE id = ?", String.class, location);
  }

  private double amount(@NotNull UUID row) {
    return jdbc.queryForObject("SELECT amount FROM inventory_item WHERE id = ?", Double.class, row);
  }

  private double pooled(@NotNull UUID holder, @NotNull UUID unit) {
    Double sum =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM inventory_item WHERE user_id = ?"
                + " AND NOT personal AND owning_org_unit_id = ?",
            Double.class,
            holder,
            unit);
    return sum == null ? 0 : sum;
  }

  private double personal(@NotNull UUID material) {
    Double sum =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM inventory_item WHERE user_id = ?"
                + " AND material_id = ? AND personal",
            Double.class,
            member,
            material);
    return sum == null ? 0 : sum;
  }
}
