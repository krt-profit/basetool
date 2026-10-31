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
 * A client shares part of a lot's personal stock into the pool of a unit the member directly
 * belongs to, relayed from the ingest gateway (REQ-XCH-016, ADR-0278). Writes commit.
 */
@SpringBootTest
class ExchangeStockShareControllerTest {

  private static final String PATH = "/api/v1/exchange/me/stock";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Sx9_" + "s".repeat(39);

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private ExchangeJournalRepository journalRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private KnownExchangeClients knownClients;

  private MockMvc mockMvc;
  private UUID member;
  private String client;
  private boolean wasEnabled;
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
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("stock-sharer-" + UUID.randomUUID());
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
    jdbc.update("DELETE FROM material_exchange_offer WHERE owner_id = ?", member);
    jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", member);
    missions.forEach(id -> jdbc.update("DELETE FROM mission WHERE id = ?", id));
    jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", member);
    jdbc.update("DELETE FROM user_roles WHERE user_id = ?", member);
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
    orgUnits.reversed().forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    materials.forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    items.forEach(id -> jdbc.update("DELETE FROM game_item WHERE id = ?", id));
    locations.forEach(id -> jdbc.update("DELETE FROM location WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aShareMovesExactlyTheQuantityIntoTheUnitsPoolAndKeepsTheLot() throws Exception {
    UUID laranite = material("SCU");
    UUID area18 = location();
    UUID squadron = orgUnit("SQUADRON", null);
    joins(squadron, false);
    UUID loose = row(laranite, area18, 500, 10, null);
    String key = "m:" + laranite + "|l:" + area18 + "|q:500|s:0";
    String cursor = snapshotEnd();

    change(share(laranite, area18, 500, squadron, "4", "10", "SCU"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.notApplied").value(0));

    assertThat(amount(loose)).isEqualTo(6.0);
    assertThat(
            jdbc.queryForList(
                "SELECT amount FROM inventory_item WHERE user_id = ? AND NOT personal"
                    + " AND owning_org_unit_id = ?",
                Double.class,
                member,
                squadron))
        .containsExactly(4.0);
    read(get(PATH).param("cursor", cursor))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].key").value(key))
        .andExpect(jsonPath("$.items[0].quantity.amount").value(10))
        .andExpect(jsonPath("$.items[0].split.personal.amount").value(6))
        .andExpect(pool(squadron).value(4));
    assertThat(
            jdbc.queryForList(
                "SELECT client_id FROM audit_event WHERE subject_id = ?"
                    + " AND event_type = 'INVENTORY_ITEM_DEPERSONALIZED'",
                String.class,
                loose))
        .containsExactly(client);
    assertThat(
            jdbc.queryForList(
                "SELECT COALESCE(details, '') FROM audit_event WHERE subject_id = ?"
                    + " AND event_type = 'INVENTORY_ITEM_DEPERSONALIZED'",
                String.class,
                loose))
        .noneMatch(details -> details.contains("stock-sharer-"));
    assertThat(journalRepository.findAllByUserIdOrderByRecordedAtAsc(member))
        .singleElement()
        .satisfies(e -> {
          assertThat(e.getAction().name()).isEqualTo("STOCK_SHARE");
          assertThat(e.getEntityKey()).isEqualTo(key);
          assertThat(e.isRemoval()).isFalse();
        });
  }

  @Test
  void aShareTakesTheTargetUnitsRowsFirstThenRowsWithoutAUnit() throws Exception {
    UUID laranite = material("SCU");
    UUID area18 = location();
    UUID squadron = orgUnit("SQUADRON", null);
    UUID other = orgUnit("SQUADRON", null);
    joins(squadron, false);
    UUID stamped = row(laranite, area18, 500, 3, squadron);
    UUID foreign = row(laranite, area18, 500, 5, other);
    UUID loose = row(laranite, area18, 500, 4, null);

    change(share(laranite, area18, 500, squadron, "5", "12", "SCU"))
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(exists(stamped)).isFalse();
    assertThat(amount(loose)).isEqualTo(2.0);
    assertThat(amount(foreign)).isEqualTo(5.0);
    assertThat(pooled(squadron)).isEqualTo(5.0);
  }

  @Test
  void theWholeReportedPersonalPartCanBeSharedIncludingSharedStockWithoutAUnit() throws Exception {
    UUID laranite = material("SCU");
    UUID area18 = location();
    UUID squadron = orgUnit("SQUADRON", null);
    joins(squadron, false);
    UUID loose = row(laranite, area18, 500, 4, null);
    sharedRow(laranite, area18, 500, 3, squadron);
    UUID unitless = insert(laranite, area18, 500, 3, false, null, 600);
    String place = locationName(area18);

    change("{\"dryRun\":true,\"ops\":[" + share(laranite, place, squadron, "8", "7") + "]}")
        .andExpect(jsonPath("$.results[0].reason").value("QUANTITY_ABOVE_EXPECTED"));
    change("{\"dryRun\":true,\"ops\":[" + share(laranite, place, squadron, "7", "7") + "]}")
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.notApplied").value(0));
    assertThat(amount(loose)).isEqualTo(4.0);
    assertThat(amount(unitless)).isEqualTo(3.0);

    change(share(laranite, area18, 500, squadron, "6", "7", "SCU"))
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(exists(loose)).isFalse();
    assertThat(amount(unitless)).isEqualTo(1.0);
    assertThat(pooled(squadron)).isEqualTo(9.0);
    assertThat(total(laranite)).isEqualTo(10.0);
    read(get(PATH))
        .andExpect(jsonPath("$.items[0].quantity.amount").value(10))
        .andExpect(jsonPath("$.items[0].split.personal.amount").value(1))
        .andExpect(pool(squadron).value(9));
    assertThat(
            jdbc.queryForList(
                "SELECT event_type || ':' || client_id FROM audit_event WHERE subject_id IN (?, ?)"
                    + " AND event_type IN ('INVENTORY_ITEM_DEPERSONALIZED',"
                    + " 'INVENTORY_POOL_CHANGED')",
                String.class,
                loose,
                unitless))
        .containsExactlyInAnyOrder(
            "INVENTORY_ITEM_DEPERSONALIZED:" + client, "INVENTORY_POOL_CHANGED:" + client);

    change(share(laranite, area18, 500, squadron, "2", "1", "SCU"))
        .andExpect(jsonPath("$.results[0].reason").value("QUANTITY_ABOVE_EXPECTED"));
    change(share(laranite, area18, 500, squadron, "1", "1", "SCU"))
        .andExpect(jsonPath("$.applied").value(1));
    assertThat(amount(unitless)).isEqualTo(1.0);
    assertThat(pooled(squadron)).isEqualTo(10.0);
    assertThat(total(laranite)).isEqualTo(10.0);
  }

  @Test
  void aUnitTheMemberIsNotADirectMemberOfIsRefusedEvenForAnAdminOrAnAreaLeader() throws Exception {
    UUID laranite = material("SCU");
    UUID area18 = location();
    UUID area = orgUnit("BEREICH", null);
    UUID below = orgUnit("SQUADRON", area);
    joins(area, true);
    jdbc.update(
        "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)",
        member,
        roleRepository.findByCode(Roles.ADMIN).orElseThrow().getId());
    UUID loose = row(laranite, area18, 500, 10, null);

    change(share(laranite, area18, 500, below, "4", "10", "SCU"))
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].result").value("rejected"))
        .andExpect(jsonPath("$.results[0].reason").value("NOT_A_MEMBER"));
    change(share(laranite, area18, 500, orgUnit("SQUADRON", null), "4", "10", "SCU"))
        .andExpect(jsonPath("$.results[0].reason").value("NOT_A_MEMBER"));

    assertThat(amount(loose)).isEqualTo(10.0);
    assertThat(journalRepository.findAllByUserIdOrderByRecordedAtAsc(member)).isEmpty();
  }

  @Test
  void aStaleExpectationAndAQuantityAboveItAreRefused() throws Exception {
    UUID laranite = material("SCU");
    UUID area18 = location();
    UUID squadron = orgUnit("SQUADRON", null);
    joins(squadron, false);
    UUID loose = row(laranite, area18, 500, 10, null);
    sharedRow(laranite, area18, 500, 2, squadron);

    change(share(laranite, area18, 500, squadron, "4", "12", "SCU"))
        .andExpect(jsonPath("$.results[0].reason").value("VERSION_CONFLICT"));
    change(share(laranite, area18, 500, squadron, "11", "10", "SCU"))
        .andExpect(jsonPath("$.results[0].reason").value("QUANTITY_ABOVE_EXPECTED"));

    assertThat(amount(loose)).isEqualTo(10.0);
    assertThat(pooled(squadron)).isEqualTo(2.0);
  }

  @Test
  void earmarkedAndOfferedStockStaysWhereItIs() throws Exception {
    UUID laranite = material("SCU");
    UUID area18 = location();
    UUID squadron = orgUnit("SQUADRON", null);
    joins(squadron, false);
    UUID loose = row(laranite, area18, 500, 10, null);
    reserve(loose, 7);

    change(share(laranite, area18, 500, squadron, "4", "10", "SCU"))
        .andExpect(jsonPath("$.results[0].reason").value("STOCK_EARMARKED"));

    UUID agricium = material("SCU");
    UUID offered = row(agricium, area18, 500, 10, null);
    offer(offered, 8);

    change(share(agricium, area18, 500, squadron, "4", "10", "SCU"))
        .andExpect(jsonPath("$.results[0].reason").value("STOCK_OFFERED"));

    assertThat(amount(loose)).isEqualTo(10.0);
    assertThat(amount(offered)).isEqualTo(10.0);
    assertThat(pooled(squadron)).isZero();
  }

  @Test
  void anItemMovesInWholePiecesOnlyAndStolenStockStaysStolen() throws Exception {
    UUID helmet = item();
    UUID orison = location();
    UUID squadron = orgUnit("SQUADRON", null);
    joins(squadron, false);
    UUID pieces = itemRow(helmet, orison, 5, true);
    String ops =
        """
        {"ops":[{"op":"share","material":{"bt":"%1$s"},"location":{"name":"%2$s"},
                 "quality":0,"stolen":true,"orgUnit":{"id":"%3$s"},
                 "quantity":{"amount":1.5,"unit":"PIECE"},
                 "expectedQuantity":{"amount":5,"unit":"PIECE"}},
                {"op":"share","material":{"bt":"%1$s"},"location":{"name":"%2$s"},
                 "quality":0,"stolen":true,"orgUnit":{"id":"%3$s"},
                 "quantity":{"amount":2,"unit":"PIECE"},
                 "expectedQuantity":{"amount":5,"unit":"PIECE"}}]}
        """
            .formatted(helmet, locationName(orison), squadron);

    change(ops)
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.results[0].index").value(0))
        .andExpect(jsonPath("$.results[0].reason").value("UNIT_MISMATCH"));

    assertThat(amount(pieces)).isEqualTo(3.0);
    assertThat(
            jdbc.queryForList(
                "SELECT amount FROM inventory_item WHERE user_id = ? AND NOT personal AND stolen"
                    + " AND owning_org_unit_id = ?",
                Double.class,
                member,
                squadron))
        .containsExactly(2.0);
  }

  @Test
  void sharesAndSetsInOneChangeSetAreDecidedInOrder() throws Exception {
    UUID laranite = material("SCU");
    UUID area18 = location();
    UUID squadron = orgUnit("SQUADRON", null);
    joins(squadron, false);
    row(laranite, area18, 500, 10, null);
    String place = locationName(area18);

    change(
            "{\"ops\":["
                + set(laranite, place, "15", "10")
                + ","
                + share(laranite, place, squadron, "12", "15")
                + ","
                + set(laranite, place, "13", "15")
                + "]}")
        .andExpect(jsonPath("$.applied").value(3));

    assertThat(total(laranite)).isEqualTo(13.0);
    assertThat(pooled(squadron)).isEqualTo(12.0);
    assertThat(
            jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM inventory_item WHERE user_id = ?"
                    + " AND personal",
                Double.class,
                member))
        .isEqualTo(1.0);
  }

  @Test
  void aDryRunDecidesTheShareAndWritesNothing() throws Exception {
    UUID laranite = material("SCU");
    UUID area18 = location();
    UUID squadron = orgUnit("SQUADRON", null);
    joins(squadron, false);
    UUID loose = row(laranite, area18, 500, 10, null);
    String place = locationName(area18);

    change(
            "{\"dryRun\":true,\"ops\":["
                + share(laranite, place, squadron, "4", "10")
                + ","
                + share(laranite, place, orgUnit("SQUADRON", null), "4", "6")
                + "]}")
        .andExpect(jsonPath("$.dryRun").value(true))
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.results[0].index").value(1))
        .andExpect(jsonPath("$.results[0].reason").value("NOT_A_MEMBER"));

    assertThat(amount(loose)).isEqualTo(10.0);
    assertThat(pooled(squadron)).isZero();
    assertThat(journalRepository.findAllByUserIdOrderByRecordedAtAsc(member)).isEmpty();
  }

  @Test
  void sharingEveryLotWhollyIsNeverHeldBackAsAMassChange() throws Exception {
    UUID laranite = material("SCU");
    UUID squadron = orgUnit("SQUADRON", null);
    joins(squadron, false);
    StringBuilder ops = new StringBuilder();
    for (int i = 0; i < 5; i++) {
      UUID place = location();
      row(laranite, place, 500, 10, null);
      ops.append(ops.isEmpty() ? "" : ",")
          .append(share(laranite, locationName(place), squadron, "10", "10"));
    }

    change("{\"ops\":[" + ops + "]}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(5));

    assertThat(pooled(squadron)).isEqualTo(50.0);
    assertThat(total(laranite)).isEqualTo(50.0);
  }

  @Test
  void aShareWithoutAUnitIsRefusedAsInvalid() throws Exception {
    UUID laranite = material("SCU");
    UUID area18 = location();
    row(laranite, area18, 500, 10, null);

    change(
            """
            {"ops":[{"op":"share","material":{"bt":"%s"},"location":{"name":"%s"},
                     "quality":500,"stolen":false,"quantity":{"amount":4,"unit":"SCU"},
                     "expectedQuantity":{"amount":10,"unit":"SCU"}}]}
            """
                .formatted(laranite, locationName(area18)))
        .andExpect(status().isBadRequest());

    assertThat(total(laranite)).isEqualTo(10.0);
  }

  /**
   * Builds one share op of a not-stolen lot.
   *
   * @param material the material
   * @param location the location
   * @param quality the quality
   * @param unit the receiving org unit
   * @param quantity the amount to move
   * @param expected the personal amount the client last saw
   * @param measure the unit of both amounts
   * @return the change set as JSON
   */
  private @NotNull String share(
      @NotNull UUID material,
      @NotNull UUID location,
      int quality,
      @NotNull UUID unit,
      @NotNull String quantity,
      @NotNull String expected,
      @NotNull String measure) {
    return """
    {"ops":[{"op":"share","material":{"bt":"%s"},"location":{"name":"%s"},"quality":%d,
             "stolen":false,"orgUnit":{"id":"%s"},"quantity":{"amount":%s,"unit":"%s"},
             "expectedQuantity":{"amount":%s,"unit":"%s"}}]}
    """
        .formatted(
            material, locationName(location), quality, unit, quantity, measure, expected, measure);
  }

  /**
   * Builds one share op of a not-stolen SCU lot at quality 500.
   *
   * @param material the material
   * @param place the location name
   * @param unit the receiving org unit
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
    return """
    {"op":"share","material":{"bt":"%s"},"location":{"name":"%s"},"quality":500,"stolen":false,
     "orgUnit":{"id":"%s"},"quantity":{"amount":%s,"unit":"SCU"},
     "expectedQuantity":{"amount":%s,"unit":"SCU"}}\
    """
        .formatted(material, place, unit, quantity, expected);
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
   * Seeds an org unit.
   *
   * @param kind the kind
   * @param parent the parent unit, or {@code null}
   * @return its id
   */
  private @NotNull UUID orgUnit(@NotNull String kind, @Nullable UUID parent) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible, parent_org_unit_id) VALUES (?, ?, ?, ?, TRUE, FALSE, FALSE, ?)",
        id,
        kind,
        "Unit " + id,
        "U" + id.toString().substring(0, 6),
        parent);
    orgUnits.add(id);
    return id;
  }

  /**
   * Makes the member a direct member of an org unit.
   *
   * @param orgUnit the org unit
   * @param areaLead whether the member leads it as a Bereichsleiter
   */
  private void joins(@NotNull UUID orgUnit, boolean areaLead) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role) VALUES (?, ?, ?)",
        member,
        orgUnit,
        areaLead ? "BEREICHSLEITER" : "MEMBER");
  }

  /**
   * Seeds a personal Lager row, older the more it is stamped.
   *
   * @param material the material
   * @param location the location
   * @param quality the quality
   * @param amount the amount
   * @param unit the owning org unit, or {@code null}
   * @return the row
   */
  private @NotNull UUID row(
      @NotNull UUID material,
      @NotNull UUID location,
      int quality,
      double amount,
      @Nullable UUID unit) {
    return insert(material, location, quality, amount, true, unit, unit == null ? 10 : 3600);
  }

  /**
   * Seeds a shared Lager row of the member.
   *
   * @param material the material
   * @param location the location
   * @param quality the quality
   * @param amount the amount
   * @param unit the pool's unit
   * @return the row
   */
  private @NotNull UUID sharedRow(
      @NotNull UUID material,
      @NotNull UUID location,
      int quality,
      double amount,
      @NotNull UUID unit) {
    return insert(material, location, quality, amount, false, unit, 7200);
  }

  private @NotNull UUID insert(
      @NotNull UUID material,
      @NotNull UUID location,
      int quality,
      double amount,
      boolean personal,
      @Nullable UUID unit,
      long ageSeconds) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, owning_org_unit_id, created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, false, ?, ?, 0)
        """,
        id,
        member,
        material,
        location,
        quality,
        amount,
        personal,
        unit,
        Timestamp.from(Instant.now().minusSeconds(ageSeconds)));
    return id;
  }

  /**
   * Seeds a personal item row.
   *
   * @param item the item
   * @param location the location
   * @param amount the pieces
   * @param stolen whether it is stolen
   * @return the row
   */
  private @NotNull UUID itemRow(
      @NotNull UUID item, @NotNull UUID location, double amount, boolean stolen) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, game_item_id, location_id, amount, personal,
                                    stolen, created_at, version)
        VALUES (?, ?, ?, ?, ?, true, ?, now(), 0)
        """,
        id,
        member,
        item,
        location,
        amount,
        stolen);
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

  /**
   * Offers part of a row on the Materialbörse.
   *
   * @param inventoryItem the row
   * @param amount the offered amount
   */
  private void offer(@NotNull UUID inventoryItem, double amount) {
    jdbc.update(
        """
        INSERT INTO material_exchange_offer (id, inventory_item_id, owner_id, status, released_at,
                                             version, created_at, updated_at, offered_amount,
                                             offer_kind)
        VALUES (?, ?, ?, 'ACTIVE', now(), 0, now(), now(), ?, 'MATERIAL')
        """,
        UUID.randomUUID(),
        inventoryItem,
        member,
        amount);
  }

  private @NotNull UUID material(@NotNull String unit) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'REFINED', ?, false, false, true, 'UEX_ONLY')
        """,
        id,
        "Stock share " + id,
        unit);
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

  private boolean exists(@NotNull UUID row) {
    Integer count =
        jdbc.queryForObject("SELECT COUNT(*) FROM inventory_item WHERE id = ?", Integer.class, row);
    return count != null && count > 0;
  }

  private double pooled(@NotNull UUID unit) {
    Double sum =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM inventory_item WHERE user_id = ?"
                + " AND NOT personal AND owning_org_unit_id = ?",
            Double.class,
            member,
            unit);
    return sum == null ? 0 : sum;
  }

  private double total(@NotNull UUID material) {
    Double sum =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM inventory_item WHERE user_id = ?"
                + " AND material_id = ?",
            Double.class,
            member,
            material);
    return sum == null ? 0 : sum;
  }
}
