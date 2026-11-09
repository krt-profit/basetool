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

import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.persistedProfitArea;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ConnectedAppMassChangeRequestDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ConnectedAppMassChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeHolderRefs;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeLiveSync;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeLocationResolver;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeMassChangeService;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeUndoResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeUndoService;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A client's pickup with store lines and the two store-choice reads it needs, relayed from the
 * ingest gateway (REQ-XCH-053, REQ-XCH-054, REQ-SEC-039): who may be booked onto is decided from
 * the database, a line books through the web's store, and the whole op is refused on one refused
 * line. Writes commit, because the undo reads only finished transactions.
 */
@SpringBootTest
class ExchangeRefineryStoreControllerTest {

  @Autowired private OrgUnitRepository orgUnitRepository;

  private static final String CHANGES = "/api/v1/exchange/me/refinery-orders/changes";
  private static final String RECEIVERS = "/api/v1/exchange/me/store-choices/receivers";
  private static final String JOB_ORDERS = "/api/v1/exchange/me/store-choices/job-orders";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "s".repeat(39);
  private static final String ALL =
      "exchange.refinery.read,exchange.refinery.write,exchange.refinery.store";
  private static final String QUALITY_TIER = "6b1f2e0a-3c1d-4f5e-9a10-000000000000";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private ExchangeHolderRefs holderRefs;
  @Autowired private ExchangeUndoService undoService;
  @Autowired private ExchangeMassChangeService massChangeService;
  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private ExchangeLiveSync liveSync;

  /** Spied by the same name as in the other exchange write tests, so they share one context. */
  @MockitoSpyBean private ExchangeLocationResolver locationResolver;

  private MockMvc mockMvc;
  private UUID member;
  private UUID mate;
  private UUID shy;
  private UUID stranger;
  private String client;
  private boolean wasEnabled;
  private UUID refinery;
  private UUID unit;
  private UUID otherUnit;
  private UUID foreignUnit;
  private UUID ore;
  private UUID refined;
  private UUID unrelated;
  private final List<UUID> users = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> jobOrders = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    member = user("store-writer", Roles.KRT_MEMBER);
    mate = user("store-mate", Roles.KRT_MEMBER);
    shy = user("store-shy", Roles.KRT_MEMBER);
    stranger = user("store-stranger", Roles.KRT_MEMBER);
    client = register();
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
    refinery = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, refinery, "ARC-L1 Refinery " + refinery);
    jdbc.update(
        "UPDATE city SET has_refinery_terminal = TRUE WHERE id = (SELECT city_id FROM location"
            + " WHERE id = ?)",
        refinery);
    unit = orgUnit();
    otherUnit = orgUnit();
    foreignUnit = orgUnit();
    membership(member, unit, false);
    membership(mate, unit, false);
    membership(mate, otherUnit, false);
    membership(shy, unit, false);
    membership(stranger, foreignUnit, false);
    jdbc.update(
        "INSERT INTO exchange_name_withholding (user_id, withhold_name) VALUES (?, TRUE)", shy);
    refined = material("Quantainium (Refined)", "REFINED", null);
    ore = material("Quantainium", "RAW", refined);
    unrelated = material("Agricium (Refined)", "REFINED", null);
  }

  @AfterEach
  void tearDown() {
    for (UUID id : users) {
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", id);
      jdbc.update(
          "DELETE FROM refinery_good WHERE refinery_order_id IN"
              + " (SELECT id FROM refinery_order WHERE owner_id = ?)",
          id);
      jdbc.update("DELETE FROM refinery_order WHERE owner_id = ?", id);
      jdbc.update("DELETE FROM exchange_journal WHERE user_id = ?", id);
      jdbc.update("DELETE FROM exchange_name_withholding WHERE user_id = ?", id);
      jdbc.update("DELETE FROM notification WHERE recipient_user_id = ?", id);
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
    }
    jobOrders.forEach(id -> jdbc.update("DELETE FROM job_order WHERE id = ?", id));
    users.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    UUID city =
        jdbc.queryForObject("SELECT city_id FROM location WHERE id = ?", UUID.class, refinery);
    jdbc.update("DELETE FROM location WHERE id = ?", refinery);
    jdbc.update("DELETE FROM city WHERE id = ?", city);
    materials.reversed().forEach(id -> jdbc.update("DELETE FROM material WHERE id = ?", id));
    for (UUID id : List.of(unit, otherUnit, foreignUnit)) {
      jdbc.update("DELETE FROM org_unit WHERE id = ?", id);
    }
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aPlainMembersReceiversHoldOnlyTheMemberWithTheirUnits() throws Exception {
    relayed(get(RECEIVERS), ALL)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].ref").value(ref(member)))
        .andExpect(jsonPath("$.items[0].self").value(true))
        .andExpect(jsonPath("$.items[0].orgUnits[0].id").value(unit.toString()));
  }

  @Test
  void aLogisticiansReceiversAddTheUnitsMembersWithTheirUnitsButNoneWithholdingTheirName()
      throws Exception {
    logistician();

    String body =
        relayed(get(RECEIVERS), ALL)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonPath.<List<String>>read(body, "$.items[*].ref"))
        .containsExactly(ref(member), ref(mate));
    assertThat(JsonPath.<List<String>>read(body, "$.items[1].orgUnits[*].id"))
        .containsExactlyInAnyOrder(unit.toString(), otherUnit.toString());
    assertThat(JsonPath.<List<Boolean>>read(body, "$.items[*].self")).containsExactly(true, false);
  }

  @Test
  void anAdminWithoutTheGrantMayBookOnlyOntoThemselves() throws Exception {
    jdbc.update(
        "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)",
        member,
        roleRepository.findByCode(Roles.ADMIN).orElseThrow().getId());
    UUID order = order("IN_PROGRESS");

    relayed(get(RECEIVERS), ALL).andExpect(jsonPath("$.items.length()").value(1));
    changes(pickup(order, line(refined, "\"receiver\": \"" + ref(mate) + "\"")))
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].reason").value("RECEIVER_UNKNOWN"));
    assertThat(stockOf(mate)).isZero();
    assertThat(statusOf(order)).isEqualTo("IN_PROGRESS");
  }

  @Test
  void theJobOrderReadListsTheVisibleActiveOrdersThatNeedTheMaterial() throws Exception {
    withinProfitHierarchy();
    UUID visible = jobOrder(unit, "OPEN", refined);
    UUID done = jobOrder(unit, "COMPLETED", refined);
    UUID foreign = jobOrder(foreignUnit, "OPEN", refined);
    UUID otherNeed = jobOrder(unit, "IN_PROGRESS", unrelated);

    String body =
        relayed(get(JOB_ORDERS).param("material", refined.toString()), ALL)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonPath.<List<String>>read(body, "$.items[*].id"))
        .contains(visible.toString())
        .doesNotContain(done.toString(), foreign.toString(), otherNeed.toString());
    relayed(get(JOB_ORDERS).param("material", UUID.randomUUID().toString()), ALL)
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void withoutTheStoreCapabilityTheReadsAndASetWithLinesAreRefusedAndNothingIsWritten()
      throws Exception {
    String writeOnly = "exchange.refinery.read,exchange.refinery.write";
    UUID order = order("IN_PROGRESS");

    relayed(get(RECEIVERS), writeOnly).andExpect(status().isForbidden());
    relayed(get(JOB_ORDERS).param("material", refined.toString()), writeOnly)
        .andExpect(status().isForbidden());
    relayed(
            post(CHANGES)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ops\": [" + pickup(order, line(refined, null)) + "]}"),
            writeOnly)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
    assertThat(statusOf(order)).isEqualTo("IN_PROGRESS");
    assertThat(stockOf(member)).isZero();
  }

  @Test
  void aPickupWithALineStoresOntoTheMemberLocksTheOrderAndItsUndoIsSkipped() throws Exception {
    UUID order = order("IN_PROGRESS");
    Instant since = Instant.now().minus(1, ChronoUnit.MINUTES);

    changes(pickup(order, line(refined, null)))
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.orders[0].versions.gameData").value(1));

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, stored_at, picked_up_client FROM refinery_order WHERE id = ?", order);
    assertThat(row.get("status")).isEqualTo("COMPLETED");
    assertThat(row.get("stored_at")).isNotNull();
    assertThat(row.get("picked_up_client")).isEqualTo(client);
    Map<String, Object> stock =
        jdbc.queryForMap(
            "SELECT amount, material_id, owning_org_unit_id, personal FROM inventory_item"
                + " WHERE user_id = ?",
            member);
    assertThat(stock.get("amount")).isEqualTo(4.5);
    assertThat(stock.get("material_id")).isEqualTo(refined);
    assertThat(stock.get("owning_org_unit_id")).isEqualTo(unit);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type = 'REFINERY_ORDER_STORED'"
                    + " AND subject_id = ?",
                Integer.class,
                order))
        .isEqualTo(1);

    ExchangeUndoResultDto undo = undoService.undo(member, client, since);

    assertThat(undo.restored()).isZero();
    assertThat(undo.skipped())
        .extracting(ExchangeUndoResultDto.Skipped::reason)
        .containsExactly("STOCK_BOOKED");
    assertThat(statusOf(order)).isEqualTo("COMPLETED");
    assertThat(stockOf(member)).isEqualTo(1);
  }

  @Test
  void aLogisticianBooksOntoAMateInTheirUnitAndOntoAJobOrder() throws Exception {
    logistician();
    withinProfitHierarchy();
    UUID jobOrder = jobOrder(unit, "OPEN", refined);
    UUID order = order("OPEN");

    changes(
            pickup(
                order,
                line(
                        refined,
                        "\"receiver\": \""
                            + ref(mate)
                            + "\", \"orgUnit\": {\"id\": \""
                            + unit
                            + "\"}")
                    + ","
                    + line(refined, "\"jobOrder\": \"" + jobOrder + "\"")))
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(stockOf(mate)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_item_job_order_allocation a JOIN inventory_item i"
                    + " ON i.id = a.inventory_item_id WHERE i.user_id = ? AND a.job_order_id = ?",
                Integer.class,
                member,
                jobOrder))
        .isEqualTo(1);
  }

  @Test
  void aRefusedLineRefusesTheWholePickupWithItsReasonAndBooksNothing() throws Exception {
    withinProfitHierarchy();
    UUID foreignJob = jobOrder(foreignUnit, "OPEN", refined);
    UUID notOutput = order("OPEN");
    UUID foreignReceiver = order("OPEN");
    UUID foreignOrgUnit = order("OPEN");
    UUID invisibleJob = order("OPEN");

    changes(
            pickup(notOutput, line(refined, null) + "," + line(unrelated, null))
                + ","
                + pickup(foreignReceiver, line(refined, "\"receiver\": \"" + ref(stranger) + "\""))
                + ","
                + pickup(
                    foreignOrgUnit, line(refined, "\"orgUnit\": {\"id\": \"" + foreignUnit + "\"}"))
                + ","
                + pickup(invisibleJob, line(refined, "\"jobOrder\": \"" + foreignJob + "\"")))
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].reason").value("MATERIAL_NOT_AN_OUTPUT"))
        .andExpect(jsonPath("$.results[1].reason").value("RECEIVER_UNKNOWN"))
        .andExpect(jsonPath("$.results[2].reason").value("ORG_UNIT_NOT_ALLOWED"))
        .andExpect(jsonPath("$.results[3].reason").value("JOB_ORDER_INVALID"));

    assertThat(stockOf(member)).isZero();
    assertThat(stockOf(stranger)).isZero();
    for (UUID id : List.of(notOutput, foreignReceiver, foreignOrgUnit, invisibleJob)) {
      assertThat(statusOf(id)).isEqualTo("OPEN");
    }
  }

  @Test
  void aPickupWithALineRefreshesTheOrdersStoreSectionAndAPlainOneOnlyTheOrder() throws Exception {
    UUID stored = order("IN_PROGRESS");
    UUID plain = order("IN_PROGRESS");

    changes(
            pickup(stored, line(refined, null))
                + ",{\"op\": \"pickup\", \"id\": \""
                + plain
                + "\", \"versions\": {\"gameData\": 0}}")
        .andExpect(jsonPath("$.applied").value(2));

    verify(liveSync)
        .refineryOrdersChanged(
            argThat(written -> written.containsAll(List.of(stored, plain))),
            argThat(booked -> booked.contains(stored) && !booked.contains(plain)),
            argThat(Collection::isEmpty));
  }

  @Test
  void aDryRunChecksAPickupsLinesAgainstTheGoodsAnEarlierCreateOfTheSetGivesTheOrder()
      throws Exception {
    String create =
        "{\"op\": \"create\", \"clientKey\": \"s-1\", \"orgUnit\": {\"id\": \""
            + unit
            + "\"}, \"location\": {\"name\": \"ARC-L1 Refinery "
            + refinery
            + "\"}, \"status\": \"IN_PROGRESS\", \"goods\": [{\"input\": {\"bt\": \""
            + ore
            + "\"}, \"inputQuantity\": 600, \"outputQuantity\": 450, \"quality\": 500}]}";

    changes(true, create + "," + pickupByKey("s-1", line(unrelated, null)))
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.results[0].index").value(1))
        .andExpect(jsonPath("$.results[0].reason").value("MATERIAL_NOT_AN_OUTPUT"));
    changes(true, create + "," + pickupByKey("s-1", line(refined, null)))
        .andExpect(jsonPath("$.applied").value(2));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM refinery_order WHERE owner_id = ?", Integer.class, member))
        .isZero();
    assertThat(stockOf(member)).isZero();
  }

  @Test
  void severalLinesOnOneGoodWriteTheirSumBackIntoItsYield() throws Exception {
    UUID order = order("IN_PROGRESS");

    changes(pickup(order, line(refined, 3.0, null) + "," + line(refined, 2.0, null)))
        .andExpect(jsonPath("$.applied").value(1));

    assertThat(yieldOf(order)).isEqualTo(500);
    assertThat(stockOf(member)).isEqualTo(2);
  }

  @Test
  void aDryRunWritesAPickupsLinesBackIntoTheGoodsAsTheRealRunDoes() throws Exception {
    UUID order = order("IN_PROGRESS");
    UUID good =
        jdbc.queryForObject(
            "SELECT id FROM refinery_good WHERE refinery_order_id = ?", UUID.class, order);
    String set =
        pickup(order, line(refined, 4.0, null))
            + ",{\"op\": \"good-update\", \"id\": \""
            + order
            + "\", \"versions\": {\"gameData\": 1}, \"goodId\": \""
            + good
            + "\", \"outputQuantity\": 400}";

    changes(true, set)
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.unchanged").value(1));
    changes(set)
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.unchanged").value(1));

    assertThat(yieldOf(order)).isEqualTo(400);
  }

  @Test
  void storeLinesOnACompletedOrderAreOrderStoredAndBookNothing() throws Exception {
    UUID order = order("COMPLETED");

    changes(pickup(order, line(refined, null)))
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].reason").value("ORDER_STORED"));

    assertThat(stockOf(member)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT stored_at FROM refinery_order WHERE id = ?", Instant.class, order))
        .isNull();
  }

  @Test
  void aConfirmedSetDecidesItsJobOrdersAsTheRelayDoesNotAsTheBrowsersAdminSession()
      throws Exception {
    withinProfitHierarchy();
    jdbc.update(
        "INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)",
        member,
        roleRepository.findByCode(Roles.ADMIN).orElseThrow().getId());
    UUID foreignJob = jobOrder(foreignUnit, "OPEN", refined);
    UUID order = order("OPEN");
    String pickup = pickup(order, line(refined, "\"jobOrder\": \"" + foreignJob + "\""));
    changes(true, pickup).andExpect(jsonPath("$.results[0].reason").value("JOB_ORDER_INVALID"));

    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken(member.toString(), null, Roles.authority(Roles.ADMIN)));
    MockHttpServletRequest browser = new MockHttpServletRequest();
    browser.addHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, foreignUnit.toString());
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(browser));
    ConnectedAppMassChangeResultDto preview;
    try {
      preview =
          massChangeService.preview(
              member,
              new ConnectedAppMassChangeRequestDto(
                  client, KEY, "refinery-orders", "{\"ops\": [" + pickup + "]}", Instant.now()));
    } finally {
      RequestContextHolder.resetRequestAttributes();
      SecurityContextHolder.clearContext();
    }

    assertThat(preview.applied()).isZero();
    assertThat(preview.notApplied()).isEqualTo(1);
  }

  /**
   * Reads the yield of an order's only good.
   *
   * @param order the order
   * @return the good's output quantity in units
   */
  private int yieldOf(@NotNull UUID order) {
    return jdbc.queryForObject(
        "SELECT output_quantity FROM refinery_good WHERE refinery_order_id = ?",
        Integer.class,
        order);
  }

  /**
   * A pickup with store lines of the order a client key names, its counter echoed at 0.
   *
   * @param key the client key
   * @param lines the lines, comma-separated
   * @return the op
   */
  private static @NotNull String pickupByKey(@NotNull String key, @NotNull String lines) {
    return "{\"op\": \"pickup\", \"clientKey\": \""
        + key
        + "\", \"versions\": {\"gameData\": 0}, \"lines\": ["
        + lines
        + "]}";
  }

  /**
   * A pickup with store lines.
   *
   * @param order the order
   * @param lines the lines, comma-separated
   * @return the op
   */
  private static @NotNull String pickup(@NotNull UUID order, @NotNull String lines) {
    return "{\"op\": \"pickup\", \"id\": \""
        + order
        + "\", \"versions\": {\"gameData\": 0}, \"lines\": ["
        + lines
        + "]}";
  }

  /**
   * A store line of 4.5 SCU at quality 500 at the test refinery.
   *
   * @param material the line's material
   * @param extra further members, comma-separated, or {@code null}
   * @return the line
   */
  private @NotNull String line(@NotNull UUID material, @Nullable String extra) {
    return line(material, 4.5, extra);
  }

  /**
   * A store line at quality 500 at the test refinery.
   *
   * @param material the line's material
   * @param scu the amount in SCU
   * @param extra further members, comma-separated, or {@code null}
   * @return the line
   */
  private @NotNull String line(@NotNull UUID material, double scu, @Nullable String extra) {
    return "{\"material\": {\"bt\": \""
        + material
        + "\"}, \"location\": {\"name\": \"ARC-L1 Refinery "
        + refinery
        + "\"}, \"quality\": 500, \"quantity\": {\"amount\": "
        + scu
        + ", \"unit\": \"SCU\"}"
        + (extra == null ? "" : ", " + extra)
        + "}";
  }

  /**
   * Inserts and commits an order of the member at the test refinery with one good refining the ore.
   *
   * @param status its status
   * @return its id
   */
  private @NotNull UUID order(@NotNull String status) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO refinery_order (id, version, owner_id, location_id, status, last_changed_at)"
            + " VALUES (?, 0, ?, ?, ?, now())",
        id,
        member,
        refinery,
        status);
    jdbc.update(
        """
        INSERT INTO refinery_good (id, version, input_material_id, input_quantity,
                                   output_material_id, output_quantity, quality, refinery_order_id)
        VALUES (?, 0, ?, 600, ?, 450, 500, ?)
        """,
        UUID.randomUUID(),
        ore,
        refined,
        id);
    return id;
  }

  /**
   * Inserts and commits a job order a unit is responsible for that needs one material.
   *
   * @param responsible the responsible unit
   * @param status its status
   * @param material the material it needs
   * @return its id
   */
  private @NotNull UUID jobOrder(
      @NotNull UUID responsible, @NotNull String status, @NotNull UUID material) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order (id, priority, created_at, updated_at, version, status,"
            + " requesting_org_unit_id, responsible_org_unit_id, type)"
            + " VALUES (?, 2, now(), now(), 0, ?, ?, ?, 'MATERIAL')",
        id,
        status,
        responsible,
        responsible);
    jdbc.update(
        """
        INSERT INTO job_order_material (id, job_order_id, material_id, amount, created_at,
                                        updated_at, version, quality_tier_id)
        VALUES (?, ?, ?, 20, now(), now(), 0, ?::uuid)
        """,
        UUID.randomUUID(),
        id,
        material,
        QUALITY_TIER);
    jobOrders.add(id);
    return id;
  }

  /**
   * Posts a change set with every refinery capability.
   *
   * @param ops the ops, comma-separated
   * @return the result
   * @throws Exception if the request fails
   */
  private @NotNull ResultActions changes(@NotNull String ops) throws Exception {
    return changes(false, ops);
  }

  /**
   * Posts a change set with every refinery capability.
   *
   * @param dryRun whether only to decide
   * @param ops the ops, comma-separated
   * @return the result
   * @throws Exception if the request fails
   */
  private @NotNull ResultActions changes(boolean dryRun, @NotNull String ops) throws Exception {
    return relayed(
        post(CHANGES)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"dryRun\": " + dryRun + ", \"ops\": [" + ops + "]}"),
        ALL);
  }

  /** Makes the member a Logistician of their unit. */
  private void logistician() {
    jdbc.update(
        "UPDATE org_unit_membership SET is_logistician = TRUE WHERE user_id = ? AND org_unit_id"
            + " = ?",
        member,
        unit);
  }

  /** Parents the member's unit to the designated Profit area for job-order access. */
  private void withinProfitHierarchy() {
    jdbc.update(
        "UPDATE org_unit SET parent_org_unit_id = ? WHERE id = ?",
        persistedProfitArea(orgUnitRepository).getId(),
        unit);
  }

  /**
   * The ref of a member at the test client.
   *
   * @param user the member
   * @return the ref
   */
  private @NotNull String ref(@NotNull UUID user) {
    return holderRefs.forClient(client).apply(user);
  }

  /**
   * Counts a member's inventory rows.
   *
   * @param user the member
   * @return the number of rows
   */
  private int stockOf(@NotNull UUID user) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM inventory_item WHERE user_id = ?", Integer.class, user);
  }

  /**
   * Reads an order's status.
   *
   * @param id the order
   * @return its status
   */
  private @NotNull String statusOf(@NotNull UUID id) {
    return jdbc.queryForObject("SELECT status FROM refinery_order WHERE id = ?", String.class, id);
  }

  /**
   * Seeds a material.
   *
   * @param name the name prefix
   * @param type {@code RAW} or {@code REFINED}
   * @param refinedInto the material it refines into, or {@code null}
   * @return its id
   */
  private @NotNull UUID material(
      @NotNull String name, @NotNull String type, @Nullable UUID refinedInto) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems, refined_material_id)
        VALUES (?, ?, ?, 'SCU', false, false, true, 'UEX_ONLY', ?)
        """,
        id,
        name + " " + id,
        type,
        refinedInto);
    materials.add(id);
    return id;
  }

  /**
   * Seeds a Staffel.
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
    return id;
  }

  /**
   * Seeds a direct membership.
   *
   * @param user the member
   * @param orgUnit the unit
   * @param isLogistician whether the membership carries the Logistician flag
   */
  private void membership(@NotNull UUID user, @NotNull UUID orgUnit, boolean isLogistician) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, 'MEMBER', ?)",
        user,
        orgUnit,
        isLogistician);
  }

  /**
   * Registers an active client with the three refinery capabilities.
   *
   * @return the client id
   */
  private @NotNull String register() {
    String id = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(id);
    registered.setDisplayName("Client " + id);
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(
            ExchangeCapability.CONNECT,
            ExchangeCapability.REFINERY_READ,
            ExchangeCapability.REFINERY_WRITE,
            ExchangeCapability.REFINERY_STORE));
    clientRepository.saveAndFlush(registered);
    return id;
  }

  /**
   * Seeds a member.
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
    user.setRoles(
        new HashSet<Role>(
            Arrays.stream(roles)
                .map(code -> roleRepository.findByCode(code).orElseThrow())
                .collect(Collectors.toSet())));
    UUID id = userRepository.saveAndFlush(user).getId();
    users.add(id);
    return id;
  }

  /**
   * Performs a request relayed for the member.
   *
   * @param request the request
   * @param capabilities the relayed capabilities, comma-separated
   * @return the result
   * @throws Exception if the request fails
   */
  private @NotNull ResultActions relayed(
      @NotNull MockHttpServletRequestBuilder request, @NotNull String capabilities)
      throws Exception {
    return mockMvc.perform(
        request
            .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
            .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
            .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
            .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capabilities)
            .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY));
  }
}
