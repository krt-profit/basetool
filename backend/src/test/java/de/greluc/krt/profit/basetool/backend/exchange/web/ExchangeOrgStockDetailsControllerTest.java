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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.context.WebApplicationContext;

/**
 * The single entries of the org stock for a Logistician of the pool under {@code
 * exchange.orgstock.details}: who gets them, what an entry carries, the per-client holder ref, a
 * withheld name and the feed entries the details produce (REQ-XCH-043, REQ-XCH-044).
 */
@SpringBootTest
class ExchangeOrgStockDetailsControllerTest {

  private static final String PATH = "/api/v1/exchange/org-stock";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "h".repeat(39);
  private static final String READ = "exchange.orgstock.read";
  private static final String DETAILS = READ + ",exchange.orgstock.details";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> users = new ArrayList<>();
  private final List<UUID> orgUnits = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();
  private final List<UUID> missions = new ArrayList<>();
  private final List<UUID> jobOrders = new ArrayList<>();
  private final List<String> clients = new ArrayList<>();

  private MockMvc mockMvc;
  private UUID member;
  private UUID holder;
  private String client;
  private boolean wasEnabled;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    member = user("details-member");
    holder = user("details-holder");
    client =
        register(
            EnumSet.of(
                ExchangeCapability.CONNECT,
                ExchangeCapability.ORGSTOCK_READ,
                ExchangeCapability.ORGSTOCK_DETAILS));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    clients.forEach(id -> jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", id));
    for (UUID id : users) {
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", id);
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM exchange_name_withholding WHERE user_id = ?", id);
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
  void theLogisticianFlagAndALeadershipRankGetEntriesAPlainMemberGetsNone() throws Exception {
    UUID flagged = orgUnit("SPECIAL_COMMAND", null);
    UUID led = orgUnit("SQUADRON", null);
    UUID plain = orgUnit("SPECIAL_COMMAND", null);
    joins(member, flagged, "MEMBER", true);
    joins(member, led, "STAFFELLEITER", false);
    joins(member, plain, "MEMBER", false);
    UUID titanium = material();
    UUID area18 = location();
    stock(holder, titanium, area18, 4, flagged);
    stock(holder, titanium, area18, 5, led);
    stock(holder, titanium, area18, 6, plain);

    String body = body(relayed(get(PATH), DETAILS));

    assertThat(entriesOf(body, flagged)).hasSize(1);
    assertThat(entriesOf(body, led)).hasSize(1);
    assertThat(JsonPath.<List<Object>>read(body, unitLots(plain) + ".entries")).isEmpty();
  }

  @Test
  void aBereichRankGetsEntriesForItsOwnPoolAndTheUnitsBelowButNoneOutsideIt() throws Exception {
    UUID bereich = orgUnit("BEREICH", null);
    UUID child = orgUnit("SPECIAL_COMMAND", bereich);
    UUID otherBereich = orgUnit("BEREICH", null);
    UUID outside = orgUnit("SPECIAL_COMMAND", otherBereich);
    joins(member, bereich, "BEREICHSLEITER", false);
    joins(member, child, "MEMBER", false);
    joins(member, outside, "MEMBER", false);
    UUID titanium = material();
    UUID area18 = location();
    stock(holder, titanium, area18, 4, bereich);
    stock(holder, titanium, area18, 5, child);
    stock(holder, titanium, area18, 6, outside);

    String body = body(relayed(get(PATH), DETAILS));

    assertThat(entriesOf(body, bereich)).hasSize(1);
    assertThat(entriesOf(body, child)).hasSize(1);
    assertThat(JsonPath.<List<Object>>read(body, unitLots(outside) + ".entries")).isEmpty();
  }

  @Test
  void withoutTheDetailsCapabilityRelayedAndGrantedNoLotCarriesEntries() throws Exception {
    UUID staffel = orgUnit("SQUADRON", null);
    joins(member, staffel, "MEMBER", true);
    stock(holder, material(), location(), 4, staffel);

    mockMvc
        .perform(relayed(get(PATH), READ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].entries").doesNotExist());

    client = register(EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.ORGSTOCK_READ));
    mockMvc
        .perform(relayed(get(PATH), DETAILS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].entries").doesNotExist());
  }

  @Test
  void anEntryCarriesWhatTheWebsEntryRowShows() throws Exception {
    UUID staffel = orgUnit("SQUADRON", null);
    joins(member, staffel, "MEMBER", true);
    jdbc.update("UPDATE app_user SET display_name = 'Cpt. Holder' WHERE id = ?", holder);
    UUID row = stock(holder, material(), location(), 10, staffel);
    jdbc.update("UPDATE inventory_item SET note = 'Hangar 3' WHERE id = ?", row);
    UUID order = earmarkToJobOrder(row, 4, staffel, true);
    UUID mission = earmarkToMission(row, 2.5);

    String body = body(relayed(get(PATH), DETAILS));
    Map<String, Object> entry = entriesOf(body, staffel).getFirst();

    assertThat(JsonPath.<String>read(entry, "$.id")).isEqualTo(row.toString());
    assertThat(JsonPath.<Number>read(entry, "$.version").longValue()).isZero();
    assertThat(JsonPath.<Boolean>read(entry, "$.holder.withheld")).isFalse();
    assertThat(JsonPath.<String>read(entry, "$.holder.name")).isEqualTo("Cpt. Holder");
    assertThat(JsonPath.<String>read(entry, "$.holder.ref")).hasSize(43);
    assertThat(JsonPath.<Number>read(entry, "$.quantity.amount").doubleValue()).isEqualTo(10);
    assertThat(JsonPath.<String>read(entry, "$.note")).isEqualTo("Hangar 3");
    assertThat(JsonPath.<String>read(entry, "$.jobOrders[0].id")).isEqualTo(order.toString());
    assertThat(JsonPath.<Integer>read(entry, "$.jobOrders[0].number"))
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT display_id FROM job_order WHERE id = ?", Integer.class, order));
    assertThat(JsonPath.<Number>read(entry, "$.jobOrders[0].quantity.amount").doubleValue())
        .isEqualTo(4);
    assertThat(JsonPath.<Boolean>read(entry, "$.jobOrders[0].delivered")).isTrue();
    assertThat(JsonPath.<String>read(entry, "$.missions[0].id")).isEqualTo(mission.toString());
    assertThat(JsonPath.<String>read(entry, "$.missions[0].name")).isEqualTo("Op " + mission);
    assertThat(JsonPath.<Number>read(entry, "$.missions[0].quantity.amount").doubleValue())
        .isEqualTo(2.5);
    assertThat(JsonPath.<Number>read(entry, "$.free.jobOrders.amount").doubleValue()).isEqualTo(6);
    assertThat(JsonPath.<Number>read(entry, "$.free.missions.amount").doubleValue()).isEqualTo(7.5);
  }

  @Test
  void aHoldersRefIsStableAtOneClientAndDiffersAtAnother() throws Exception {
    UUID staffel = orgUnit("SQUADRON", null);
    joins(member, staffel, "MEMBER", true);
    stock(holder, material(), location(), 4, staffel);

    String first = refOf(body(relayed(get(PATH), DETAILS)), staffel);
    String again = refOf(body(relayed(get(PATH), DETAILS)), staffel);
    client =
        register(
            EnumSet.of(
                ExchangeCapability.CONNECT,
                ExchangeCapability.ORGSTOCK_READ,
                ExchangeCapability.ORGSTOCK_DETAILS));
    String elsewhere = refOf(body(relayed(get(PATH), DETAILS)), staffel);

    assertThat(again).isEqualTo(first);
    assertThat(elsewhere).isNotEqualTo(first);
    assertThat(first).doesNotContain(holder.toString());
  }

  @Test
  void everyClientHoldsItsOwnThirtyTwoByteSalt() {
    String second = register(EnumSet.of(ExchangeCapability.CONNECT));

    byte[] own = clientRepository.findHolderRefSalt(client).orElseThrow();
    byte[] other = clientRepository.findHolderRefSalt(second).orElseThrow();

    assertThat(own).hasSize(32);
    assertThat(other).hasSize(32).isNotEqualTo(own);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM exchange_client WHERE octet_length(holder_ref_salt) <> 32",
                Long.class))
        .isZero();
  }

  @Test
  void aHolderWhoWithholdsTheirNameIsShownWithoutNameRefOrNote() throws Exception {
    UUID staffel = orgUnit("SQUADRON", null);
    joins(member, staffel, "MEMBER", true);
    UUID row = stock(holder, material(), location(), 4, staffel);
    jdbc.update("UPDATE inventory_item SET note = 'secret' WHERE id = ?", row);
    withholds(holder, true);

    Map<String, Object> entry = entriesOf(body(relayed(get(PATH), DETAILS)), staffel).getFirst();

    assertThat(JsonPath.<Boolean>read(entry, "$.holder.withheld")).isTrue();
    assertThat(entry).doesNotContainKey("note");
    assertThat(JsonPath.<Map<String, Object>>read(entry, "$.holder")).containsOnlyKeys("withheld");
    assertThat(JsonPath.<String>read(entry, "$.id")).isEqualTo(row.toString());
  }

  @Test
  void switchingTheWithholdingAnnouncesTheHoldersLots() throws Exception {
    UUID staffel = orgUnit("SQUADRON", null);
    joins(member, staffel, "MEMBER", true);
    stock(holder, material(), location(), 4, staffel);
    stock(member, material(), location(), 5, staffel);
    String cursor = snapshotEnd();

    withholds(holder, true);

    String body = body(relayed(get(PATH).param("cursor", cursor), DETAILS));
    assertThat(JsonPath.<List<Object>>read(body, "$.items")).hasSize(1);
    assertThat(JsonPath.<Boolean>read(body, "$.items[0].entries[0].holder.withheld")).isTrue();

    String later = JsonPath.read(body, "$.nextCursor");
    withholds(holder, false);

    String after = body(relayed(get(PATH).param("cursor", later), DETAILS));
    assertThat(JsonPath.<List<Object>>read(after, "$.items")).hasSize(1);
    assertThat(JsonPath.<Boolean>read(after, "$.items[0].entries[0].holder.withheld")).isFalse();
  }

  @Test
  void becomingOrCeasingToBeALogisticianOfTheUnitAnnouncesItsPool() throws Exception {
    UUID staffel = orgUnit("SQUADRON", null);
    joins(member, staffel, "MEMBER", false);
    stock(holder, material(), location(), 4, staffel);
    String cursor = snapshotEnd();

    jdbc.update(
        "UPDATE org_unit_membership SET is_logistician = true WHERE user_id = ? AND org_unit_id ="
            + " ?",
        member,
        staffel);
    String body = body(relayed(get(PATH).param("cursor", cursor), DETAILS));
    assertThat(entriesOf(body, staffel)).hasSize(1);

    String later = JsonPath.read(body, "$.nextCursor");
    jdbc.update(
        "UPDATE org_unit_membership SET is_logistician = false WHERE user_id = ? AND org_unit_id ="
            + " ?",
        member,
        staffel);
    String after = body(relayed(get(PATH).param("cursor", later), DETAILS));
    assertThat(JsonPath.<List<Object>>read(after, "$.items")).hasSize(1);
    assertThat(JsonPath.<List<Object>>read(after, unitLots(staffel) + ".entries")).isEmpty();
  }

  @Test
  void aNoteAnEarmarkAHolderRenameAndAMissionRenameEachReachTheFeed() throws Exception {
    UUID staffel = orgUnit("SQUADRON", null);
    joins(member, staffel, "MEMBER", true);
    UUID noted = stock(holder, material(), location(), 4, staffel);
    UUID earmarked = stock(member, material(), location(), 5, staffel);
    UUID mission = earmarkToMission(earmarked, 1);

    String cursor = snapshotEnd();
    jdbc.update("UPDATE inventory_item SET note = 'moved' WHERE id = ?", noted);
    assertThat(changedLots(cursor)).hasSize(1);

    cursor = snapshotEnd();
    earmarkToJobOrder(earmarked, 2, staffel, false);
    assertThat(changedLots(cursor)).hasSize(1);

    cursor = snapshotEnd();
    jdbc.update("UPDATE app_user SET display_name = 'Renamed' WHERE id = ?", holder);
    String body = body(relayed(get(PATH).param("cursor", cursor), DETAILS));
    assertThat(JsonPath.<List<String>>read(body, "$.items[*].entries[*].holder.name"))
        .containsExactly("Renamed");

    cursor = snapshotEnd();
    jdbc.update("UPDATE mission SET name = 'Op Renamed' WHERE id = ?", mission);
    body = body(relayed(get(PATH).param("cursor", cursor), DETAILS));
    assertThat(JsonPath.<List<String>>read(body, "$.items[*].entries[*].missions[*].name"))
        .containsExactly("Op Renamed");
  }

  /**
   * Reads the snapshot to its end.
   *
   * @return the feed cursor it ends with
   * @throws Exception if the request fails
   */
  private @NotNull String snapshotEnd() throws Exception {
    String cursor = JsonPath.read(body(relayed(get(PATH), DETAILS)), "$.nextCursor");
    assertThat(cursor).startsWith("f1.");
    return cursor;
  }

  /**
   * Reads the keys of the lots a feed page answers.
   *
   * @param cursor the feed cursor
   * @return the keys of the changed lots
   * @throws Exception if the request fails
   */
  private @NotNull List<String> changedLots(@NotNull String cursor) throws Exception {
    return JsonPath.read(
        body(relayed(get(PATH).param("cursor", cursor), DETAILS)), "$.items[*].key");
  }

  /**
   * Sends a request and returns the body of its {@code 200} answer.
   *
   * @param request the relayed request
   * @return the body
   * @throws Exception if the request fails
   */
  private @NotNull String body(@NotNull MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * Builds the JSON path of a unit's lots on a page.
   *
   * @param unit the pool
   * @return the path
   */
  private static @NotNull String unitLots(@NotNull UUID unit) {
    return "$.items[?(@.unit.id == '" + unit + "')]";
  }

  /**
   * Reads the entries of a unit's only lot on a page.
   *
   * @param body the page
   * @param unit the pool
   * @return the entries
   */
  private static @NotNull List<Map<String, Object>> entriesOf(
      @NotNull String body, @NotNull UUID unit) {
    List<List<Map<String, Object>>> lots = JsonPath.read(body, unitLots(unit) + ".entries");
    assertThat(lots).hasSize(1);
    return lots.getFirst();
  }

  /**
   * Reads the holder ref of a unit's only entry on a page.
   *
   * @param body the page
   * @param unit the pool
   * @return the ref
   */
  private static @NotNull String refOf(@NotNull String body, @NotNull UUID unit) {
    return JsonPath.read(entriesOf(body, unit).getFirst(), "$.holder.ref");
  }

  /**
   * Registers an active client and makes it the current one.
   *
   * @param capabilities the granted capabilities
   * @return the client id
   */
  private @NotNull String register(@NotNull Set<ExchangeCapability> capabilities) {
    String id = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(id);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(EnumSet.copyOf(capabilities));
    clientRepository.saveAndFlush(registered);
    clients.add(id);
    return id;
  }

  /**
   * Stores a member's withholding choice.
   *
   * @param user the member
   * @param withhold the choice
   */
  private void withholds(@NotNull UUID user, boolean withhold) {
    jdbc.update(
        """
        INSERT INTO exchange_name_withholding (user_id, withhold_name) VALUES (?, ?)
        ON CONFLICT (user_id) DO UPDATE SET withhold_name = EXCLUDED.withhold_name
        """,
        user,
        withhold);
  }

  /**
   * Inserts and commits a shared material stock row of quality 1.
   *
   * @param owner the holder
   * @param material the material
   * @param location the location
   * @param amount the amount
   * @param pool the owning org unit
   * @return the row id
   */
  private @NotNull UUID stock(
      @NotNull UUID owner,
      @NotNull UUID material,
      @NotNull UUID location,
      double amount,
      @NotNull UUID pool) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, user_id, material_id, location_id, quality, amount,
                                    personal, stolen, owning_org_unit_id, version)
        VALUES (?, ?, ?, ?, 1, ?, false, false, ?, 0)
        """,
        id,
        owner,
        material,
        location,
        amount,
        pool);
    return id;
  }

  /**
   * Earmarks part of a row to a new mission.
   *
   * @param inventoryItem the row
   * @param amount the earmarked amount
   * @return the mission id
   */
  private @NotNull UUID earmarkToMission(@NotNull UUID inventoryItem, double amount) {
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
    return mission;
  }

  /**
   * Earmarks part of a row to a new job order of the unit.
   *
   * @param inventoryItem the row
   * @param amount the earmarked amount
   * @param unit the requesting and responsible unit
   * @param delivered whether the slice is delivered
   * @return the order id
   */
  private @NotNull UUID earmarkToJobOrder(
      @NotNull UUID inventoryItem, double amount, @NotNull UUID unit, boolean delivered) {
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
        VALUES (?, ?, ?, ?, ?, 0, now(), now())
        """,
        UUID.randomUUID(),
        inventoryItem,
        order,
        amount,
        delivered);
    return order;
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
        "Details " + id);
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
        "INSERT INTO location (id, name, hidden) VALUES (?, ?, false)", id, "Details " + id);
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
        "D" + id.toString().substring(0, 8),
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
   * @param logistician whether the membership carries the Logistician flag
   */
  private void joins(
      @NotNull UUID user, @NotNull UUID orgUnit, @NotNull String role, boolean logistician) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, ?, ?)",
        user,
        orgUnit,
        role,
        logistician);
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
}
