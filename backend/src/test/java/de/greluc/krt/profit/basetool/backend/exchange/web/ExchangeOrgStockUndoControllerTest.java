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
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A Logistician undoes their client's org-stock writes from „Verbundene Anwendungen", an admin's
 * bulk undo includes them, and the mass-change guard holds a batch that takes too much out of one
 * pool until the writer confirms it (REQ-XCH-046, REQ-XCH-021, REQ-XCH-034).
 */
@SpringBootTest
class ExchangeOrgStockUndoControllerTest {

  private static final String CHANGES = "/api/v1/exchange/org-stock/changes";
  private static final String MEMBERS = "/api/v1/exchange/org-stock/members";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Ku9_" + "u".repeat(39);
  private static final String WRITE = "exchange.orgstock.write";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private KnownExchangeClients knownClients;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> users = new ArrayList<>();
  private final List<UUID> orgUnits = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();
  private final List<UUID> missions = new ArrayList<>();
  private final List<UUID> jobOrders = new ArrayList<>();

  private MockMvc mockMvc;
  private UUID writer;
  private UUID holder;
  private String client;
  private UUID registryId;
  private boolean wasEnabled;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    writer = user("undo-writer", Roles.KRT_MEMBER);
    holder = user("undo-holder", Roles.KRT_MEMBER);
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(
            ExchangeCapability.CONNECT,
            ExchangeCapability.ORGSTOCK_READ,
            ExchangeCapability.ORGSTOCK_DETAILS,
            ExchangeCapability.ORGSTOCK_WRITE));
    registryId = clientRepository.saveAndFlush(registered).getId();
    knownClients.invalidate();
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", registryId);
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    for (UUID id : users) {
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", id);
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM notification WHERE recipient_user_id = ?", id);
    }
    jobOrders.forEach(id -> jdbc.update("DELETE FROM job_order WHERE id = ?", id));
    for (UUID id : missions) {
      jdbc.update(
          "DELETE FROM mission_finance_entry WHERE mission_participant_id IN"
              + " (SELECT id FROM mission_participant WHERE mission_id = ?)",
          id);
      jdbc.update("DELETE FROM mission_participant WHERE mission_id = ?", id);
      jdbc.update("DELETE FROM mission WHERE id = ?", id);
    }
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
  void anUndoRestoresTheEntriesOfEveryOpTypeAndIsAudited() throws Exception {
    UUID pool = orgUnit();
    UUID other = orgUnit();
    UUID receiver = user("undo-receiver", Roles.KRT_MEMBER);
    joins(writer, pool, true);
    joins(writer, other, true);
    joins(holder, pool, false);
    joins(holder, other, false);
    joins(receiver, other, false);
    UUID titanium = material("SCU");
    UUID area18 = location();
    UUID lorville = location();
    UUID mission = mission();
    UUID order = jobOrder(pool, titanium);
    UUID bookedOut = stock(holder, titanium, area18, 10, pool);
    UUID sold = stock(holder, titanium, lorville, 8, pool);
    UUID relocated = stock(holder, material("SCU"), area18, 6, pool);
    UUID given = stock(holder, material("SCU"), area18, 5, pool);
    UUID repooled = stock(holder, material("SCU"), area18, 4, pool);
    UUID noted = stock(holder, material("SCU"), area18, 3, pool);
    UUID marked = stock(holder, material("SCU"), area18, 7, pool);
    UUID earmarked = stock(holder, titanium, location(), 9, pool);
    earmarkToMission(earmarked, mission, 2);
    jdbc.update("UPDATE inventory_item SET note = 'Hangar 3' WHERE id = ?", noted);
    UUID copper = material("SCU");
    Map<UUID, String> before = snapshot();

    clientWrite(
            ops(
                bookIn(pool, ref(pool, holder), copper, area18, "12"),
                entryOp("book-out", pool, bookedOut, 0, quantity("4")),
                entryOp(
                    "sell",
                    pool,
                    sold,
                    0,
                    quantity("8") + ",\"terminal\":\"TDD Area18\",\"proceeds\":1000"),
                entryOp(
                    "move-location",
                    pool,
                    relocated,
                    0,
                    quantity("2") + ",\"location\":{\"name\":\"Undo " + lorville + "\"}"),
                entryOp(
                    "move-holder",
                    pool,
                    given,
                    0,
                    quantity("5")
                        + ",\"toUnit\":\""
                        + other
                        + "\",\"holder\":\""
                        + ref(other, receiver)
                        + "\""),
                entryOp("move-pool", pool, repooled, 0, ",\"toUnit\":\"" + other + "\""),
                entryOp("note", pool, noted, 0, ",\"note\":\"Moved away\""),
                entryOp("stolen", pool, marked, 0, ",\"stolen\":true" + quantity("3")),
                entryOp(
                    "allocation-add",
                    pool,
                    earmarked,
                    0,
                    ",\"jobOrder\":\"" + order + "\"" + quantity("5")),
                entryOp(
                    "allocation-change",
                    pool,
                    earmarked,
                    1,
                    ",\"mission\":\"" + mission + "\"" + quantity("4")),
                entryOp(
                    "allocation-remove", pool, earmarked, 2, ",\"mission\":\"" + mission + "\"")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(11))
        .andExpect(jsonPath("$.results.length()").value(0));
    assertThat(snapshot()).isNotEqualTo(before);

    undo(writer).andExpect(status().isOk()).andExpect(jsonPath("$.skipped.length()").value(0));

    Map<UUID, String> after = snapshot();
    assertThat(after.keySet()).containsExactlyInAnyOrderElementsOf(before.keySet());
    before.forEach((id, row) -> assertThat(after.get(id)).as("entry %s", id).isEqualTo(row));
    assertThat(
            jdbc.queryForObject(
                "SELECT note FROM inventory_item WHERE id = ?", String.class, noted))
        .isEqualTo("Moved away");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM exchange_journal WHERE user_id = ? AND undone_at IS NULL",
                Integer.class,
                writer))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type = 'INVENTORY_ITEM_RESTORED'"
                    + " AND actor_user_id = ? AND target_user_id = ?",
                Integer.class,
                writer,
                holder))
        .isPositive();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type = 'EXCHANGE_CHANGES_UNDONE'"
                    + " AND target_user_id = ?",
                Integer.class,
                writer))
        .isEqualTo(1);
  }

  @Test
  void anEntryChangedSinceIsRefusedAsAConflictAndTheOthersAreStillRestored() throws Exception {
    UUID pool = orgUnit();
    joins(writer, pool, true);
    joins(holder, pool, false);
    UUID titanium = material("SCU");
    UUID first = stock(holder, titanium, location(), 10, pool);
    UUID second = stock(holder, material("SCU"), location(), 10, pool);
    clientWrite(
            ops(
                entryOp("book-out", pool, first, 0, quantity("4")),
                entryOp("book-out", pool, second, 0, quantity("4"))))
        .andExpect(jsonPath("$.applied").value(2));
    jdbc.update("UPDATE inventory_item SET amount = 5, version = version + 1 WHERE id = ?", first);

    undo(writer)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.restored").value(1))
        .andExpect(jsonPath("$.skipped[0].resource").value("ORG_STOCK"))
        .andExpect(jsonPath("$.skipped[0].reason").value("CHANGED_AFTERWARDS"))
        .andExpect(jsonPath("$.skipped[0].label").value("Undo " + titanium));

    assertThat(amountOf(first)).isEqualTo(5);
    assertThat(amountOf(second)).isEqualTo(10);
  }

  @Test
  void theHoldersCannotUndoTheWritersChanges() throws Exception {
    UUID pool = orgUnit();
    joins(writer, pool, true);
    joins(holder, pool, false);
    UUID entry = stock(holder, material("SCU"), location(), 10, pool);
    clientWrite(ops(entryOp("book-out", pool, entry, 0, quantity("4"))))
        .andExpect(jsonPath("$.applied").value(1));

    undo(holder).andExpect(status().isOk()).andExpect(jsonPath("$.restored").value(0));

    assertThat(amountOf(entry)).isEqualTo(6);
  }

  @Test
  void aWriterWhoIsNoLongerALogisticianOfThePoolCannotUndoItsEntries() throws Exception {
    UUID pool = orgUnit();
    joins(writer, pool, true);
    joins(holder, pool, false);
    UUID entry = stock(holder, material("SCU"), location(), 10, pool);
    clientWrite(ops(entryOp("book-out", pool, entry, 0, quantity("4"))))
        .andExpect(jsonPath("$.applied").value(1));
    jdbc.update("UPDATE org_unit_membership SET is_logistician = false WHERE user_id = ?", writer);

    undo(writer)
        .andExpect(jsonPath("$.restored").value(0))
        .andExpect(jsonPath("$.skipped[0].reason").value("GONE"));

    assertThat(amountOf(entry)).isEqualTo(6);
  }

  @Test
  void aBatchThatTakesTooMuchOutOfOnePoolIsHeldAndAppliesOnlyAfterTheWriterConfirms()
      throws Exception {
    UUID pool = orgUnit();
    joins(writer, pool, true);
    joins(holder, pool, false);
    List<String> bookOuts = new ArrayList<>();
    List<UUID> entries = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      UUID entry = stock(holder, material("SCU"), location(), 10, pool);
      entries.add(entry);
      if (i < 5) {
        bookOuts.add(entryOp("book-out", pool, entry, 0, quantity("1")));
      }
    }
    String changeSet = ops(bookOuts.toArray(String[]::new));
    clientWrite(changeSet.replace("]}", "],\"dryRun\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(5));

    clientWrite(changeSet)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MASS_CHANGE_CONFIRMATION_REQUIRED"));
    assertThat(entries).allSatisfy(entry -> assertThat(amountOf(entry)).isEqualTo(10));

    massChange("preview", staged(changeSet))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dryRun").value(true))
        .andExpect(jsonPath("$.applied").value(5));
    assertThat(entries).allSatisfy(entry -> assertThat(amountOf(entry)).isEqualTo(10));

    massChange("confirm", staged(changeSet))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(5));
    assertThat(entries.subList(0, 5)).allSatisfy(entry -> assertThat(amountOf(entry)).isEqualTo(9));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM exchange_journal WHERE user_id = ? AND removal",
                Integer.class,
                writer))
        .isEqualTo(5);
  }

  @Test
  void theGuardCountsPerPoolAndNeitherNoteNorMoveWithinThePoolCounts() throws Exception {
    UUID first = orgUnit();
    UUID second = orgUnit();
    joins(writer, first, true);
    joins(writer, second, true);
    joins(holder, first, false);
    joins(holder, second, false);
    List<String> ops = new ArrayList<>();
    for (UUID pool : List.of(first, second)) {
      for (int i = 0; i < 4; i++) {
        UUID entry = stock(holder, material("SCU"), location(), 10, pool);
        ops.add(entryOp("book-out", pool, entry, 0, quantity("1")));
      }
      UUID noted = stock(holder, material("SCU"), location(), 10, pool);
      ops.add(entryOp("note", pool, noted, 0, ",\"note\":\"Bay 2\""));
      UUID moved = stock(holder, material("SCU"), location(), 10, pool);
      ops.add(
          entryOp(
              "move-location",
              pool,
              moved,
              0,
              quantity("10") + ",\"location\":{\"name\":\"Undo " + location() + "\"}"));
    }

    clientWrite(ops(ops.toArray(String[]::new)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(12));
  }

  @Test
  void theAdminsBulkUndoRestoresTheOrgStockWritesOfEveryWriter() throws Exception {
    UUID admin = user("undo-admin", Roles.KRT_MEMBER, Roles.ADMIN);
    UUID second = user("undo-writer-b", Roles.KRT_MEMBER);
    UUID pool = orgUnit();
    joins(writer, pool, true);
    joins(second, pool, true);
    joins(holder, pool, false);
    UUID mine = stock(holder, material("SCU"), location(), 10, pool);
    UUID theirs = stock(holder, material("SCU"), location(), 10, pool);
    clientWrite(ops(entryOp("book-out", pool, mine, 0, quantity("4"))))
        .andExpect(jsonPath("$.applied").value(1));
    mockMvc
        .perform(
            relayed(post(CHANGES), second)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ops(entryOp("book-out", pool, theirs, 0, quantity("10")))))
        .andExpect(jsonPath("$.applied").value(1));

    String body =
        mockMvc
            .perform(
                post("/api/v1/connected-apps/admin/clients/" + registryId + "/undo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"since\":\"" + hourAgo() + "\",\"resource\":\"ORG_STOCK\"}")
                    .with(asAdmin(admin)))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String run = awaitEnd(JsonPath.read(body, "$.id"), admin);

    assertThat(JsonPath.<String>read(run, "$.run.status")).isEqualTo("COMPLETED");
    assertThat(JsonPath.<Integer>read(run, "$.run.membersTotal")).isEqualTo(2);
    assertThat(JsonPath.<Integer>read(run, "$.run.restored")).isEqualTo(2);
    assertThat(amountOf(mine)).isEqualTo(10);
    assertThat(amountOf(theirs)).isEqualTo(10);
    assertThat(
            jdbc.queryForList(
                "SELECT actor_user_id FROM audit_event WHERE event_type = 'INVENTORY_ITEM_RESTORED'"
                    + " AND subject_id IN (?, ?)",
                UUID.class,
                mine,
                theirs))
        .hasSize(2)
        .containsOnly(admin);
  }

  /**
   * Reads every entry of the holders as the undo should leave it: holder, pool, lot, amount and
   * earmarks.
   *
   * @return the rows by id
   */
  private @NotNull Map<UUID, String> snapshot() {
    Map<UUID, String> rows = new TreeMap<>();
    String holders = String.join(",", users.stream().map(id -> "'" + id + "'").toList());
    jdbc.query(
        """
        SELECT i.id, i.user_id, i.owning_org_unit_id, i.material_id, i.location_id, i.quality,
               i.stolen, i.amount,
               (SELECT string_agg(j.job_order_id || '=' || j.amount, ',' ORDER BY j.job_order_id)
                  FROM inventory_item_job_order_allocation j WHERE j.inventory_item_id = i.id)
                 AS jobs,
               (SELECT string_agg(m.mission_id || '=' || m.amount, ',' ORDER BY m.mission_id)
                  FROM inventory_item_mission_allocation m WHERE m.inventory_item_id = i.id)
                 AS missions
        FROM inventory_item i WHERE i.user_id IN (%s)
        """
            .formatted(holders),
        rs -> {
          rows.put(
              rs.getObject("id", UUID.class),
              String.join(
                  "|",
                  Arrays.asList(
                      rs.getString("user_id"),
                      rs.getString("owning_org_unit_id"),
                      rs.getString("material_id"),
                      rs.getString("location_id"),
                      rs.getString("quality"),
                      rs.getString("stolen"),
                      rs.getString("amount"),
                      rs.getString("jobs"),
                      rs.getString("missions"))));
        });
    return rows;
  }

  private ResultActions clientWrite(@NotNull String changeSet) throws Exception {
    return mockMvc.perform(
        relayed(post(CHANGES), writer).contentType(MediaType.APPLICATION_JSON).content(changeSet));
  }

  private ResultActions undo(@NotNull UUID member) throws Exception {
    return mockMvc.perform(
        post("/api/v1/connected-apps/" + client + "/undo")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"since\":\"" + hourAgo() + "\"}")
            .with(browser(member)));
  }

  private ResultActions massChange(@NotNull String step, @NotNull String json) throws Exception {
    return mockMvc.perform(
        post("/api/v1/connected-apps/mass-changes/" + step)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .with(browser(writer)));
  }

  /**
   * Builds the staged org-stock change set the confirmation page hands back.
   *
   * @param changeSet the change set
   * @return the request as JSON
   */
  private @NotNull String staged(@NotNull String changeSet) {
    return ("{\"clientId\":\"%s\",\"installationKey\":\"%s\",\"resource\":\"org-stock\","
            + "\"changeSet\":\"%s\",\"stagedAt\":\"%s\"}")
        .formatted(client, KEY, changeSet.replace("\"", "\\\""), Instant.now());
  }

  /**
   * Polls an admin's bulk undo run until it has ended.
   *
   * @param runId the run
   * @param admin the admin
   * @return the run's detail
   * @throws Exception if a request fails or the run does not end within 30 seconds
   */
  private @NotNull String awaitEnd(@NotNull String runId, @NotNull UUID admin) throws Exception {
    long deadline = System.nanoTime() + 30_000_000_000L;
    while (System.nanoTime() < deadline) {
      String body =
          mockMvc
              .perform(get("/api/v1/connected-apps/admin/undo-runs/" + runId).with(asAdmin(admin)))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      if (!"RUNNING".equals(JsonPath.read(body, "$.run.status"))) {
        return body;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("The bulk undo run did not end in time");
  }

  private static @NotNull Instant hourAgo() {
    return Instant.now().minus(1, ChronoUnit.HOURS);
  }

  private static @NotNull RequestPostProcessor browser(@NotNull UUID member) {
    return jwt().jwt(t -> t.subject(member.toString()).claim("azp", "basetool-frontend"));
  }

  private static @NotNull RequestPostProcessor asAdmin(@NotNull UUID admin) {
    return jwt()
        .jwt(t -> t.subject(admin.toString()).claim("azp", "basetool-frontend"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  /**
   * Reads a member's ref from a unit's roster, as the writer.
   *
   * @param unit the unit
   * @param user the member
   * @return the ref
   * @throws Exception if the request fails
   */
  private @NotNull String ref(@NotNull UUID unit, @NotNull UUID user) throws Exception {
    String name = "Roster " + user;
    jdbc.update("UPDATE app_user SET display_name = ? WHERE id = ?", name, user);
    String roster =
        mockMvc
            .perform(relayed(get(MEMBERS).param("unit", unit.toString()), writer))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> refs = JsonPath.read(roster, "$.items[?(@.name == '" + name + "')].ref");
    assertThat(refs).hasSize(1);
    return refs.getFirst();
  }

  private static @NotNull String ops(@NotNull String... ops) {
    return "{\"ops\":[" + String.join(",", ops) + "]}";
  }

  private static @NotNull String quantity(@NotNull String amount) {
    return ",\"quantity\":{\"amount\":" + amount + ",\"unit\":\"SCU\"}";
  }

  /**
   * Builds an op on an entry.
   *
   * @param kind the op
   * @param unit the pool the op names
   * @param entry the entry
   * @param version the version the client read
   * @param members further members, each starting with a comma
   * @return the op
   */
  private static @NotNull String entryOp(
      @NotNull String kind,
      @NotNull UUID unit,
      @NotNull UUID entry,
      long version,
      @NotNull String members) {
    return "{\"op\":\""
        + kind
        + "\",\"unit\":\""
        + unit
        + "\",\"entry\":\""
        + entry
        + "\",\"version\":"
        + version
        + members
        + "}";
  }

  /**
   * Builds a book-in op of an SCU material at quality 500, not stolen.
   *
   * @param unit the pool
   * @param holderRef the holder's ref
   * @param material the material
   * @param location the location, named by its name
   * @param amount the amount
   * @return the op
   */
  private static @NotNull String bookIn(
      @NotNull UUID unit,
      @NotNull String holderRef,
      @NotNull UUID material,
      @NotNull UUID location,
      @NotNull String amount) {
    return "{\"op\":\"book-in\",\"unit\":\""
        + unit
        + "\",\"holder\":\""
        + holderRef
        + "\",\"material\":{\"bt\":\""
        + material
        + "\"},\"location\":{\"name\":\"Undo "
        + location
        + "\"},\"quality\":500,\"stolen\":false"
        + quantity(amount)
        + "}";
  }

  private double amountOf(@NotNull UUID entry) {
    Double amount =
        jdbc.queryForObject("SELECT amount FROM inventory_item WHERE id = ?", Double.class, entry);
    return amount == null ? 0 : amount;
  }

  /**
   * Inserts and commits a shared material entry of quality 1.
   *
   * @param owner the holder
   * @param material the material
   * @param location the location
   * @param amount the amount
   * @param pool the owning org unit
   * @return the entry's id
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

  private void earmarkToMission(@NotNull UUID inventoryItem, @NotNull UUID mission, double amount) {
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

  private @NotNull UUID mission() {
    UUID mission = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission (id, name, version) VALUES (?, ?, 0)", mission, "Op " + mission);
    missions.add(mission);
    return mission;
  }

  /**
   * Seeds an open job order of a unit that needs a material.
   *
   * @param unit the requesting and responsible unit
   * @param material the material it needs
   * @return the order id
   */
  private @NotNull UUID jobOrder(@NotNull UUID unit, @NotNull UUID material) {
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
        INSERT INTO job_order_material (id, job_order_id, material_id, amount, created_at,
                                        updated_at, version, quality_tier_id)
        VALUES (?, ?, ?, 20, now(), now(), 0, '6b1f2e0a-3c1d-4f5e-9a10-000000000000')
        """,
        UUID.randomUUID(),
        order,
        material);
    return order;
  }

  private @NotNull UUID material(@NotNull String quantityType) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'RAW', ?, false, false, true, 'UEX_ONLY')
        """,
        id,
        "Undo " + id,
        quantityType);
    materials.add(id);
    return id;
  }

  private @NotNull UUID location() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO location (id, name, hidden) VALUES (?, ?, false)", id, "Undo " + id);
    locations.add(id);
    return id;
  }

  private @NotNull UUID orgUnit() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible) VALUES (?, 'SPECIAL_COMMAND', ?, ?, TRUE, FALSE, FALSE)",
        id,
        "Pool " + id,
        "U" + id.toString().substring(0, 8));
    orgUnits.add(id);
    return id;
  }

  private void joins(@NotNull UUID user, @NotNull UUID orgUnit, boolean logistician) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, 'MEMBER', ?)",
        user,
        orgUnit,
        logistician);
  }

  private @NotNull UUID user(@NotNull String username, @NotNull String... roles) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    Set<Role> granted = new HashSet<>();
    for (String role : roles) {
      granted.add(roleRepository.findByCode(role).orElseThrow());
    }
    user.setRoles(granted);
    UUID id = userRepository.saveAndFlush(user).getId();
    users.add(id);
    return id;
  }

  private @NotNull MockHttpServletRequestBuilder relayed(
      @NotNull MockHttpServletRequestBuilder request, @NotNull UUID member) {
    return request
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, WRITE)
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY);
  }
}
