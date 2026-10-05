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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.greluc.krt.profit.basetool.backend.exchange.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeJournalRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * A client's ship changes, relayed from the ingest gateway: links before creates, version-checked
 * updates and removals through the Hangar, the mission detachment reported, ships removed elsewhere
 * brought back only with the member's consent, and the mass-change guard (REQ-XCH-017, REQ-XCH-021,
 * REQ-XCH-022). Writes commit.
 */
@SpringBootTest
class ExchangeShipWriteControllerTest {

  private static final String PATH = "/api/v1/exchange/me/ships";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "s".repeat(39);
  private static final String OTHER_KEY = "Lx9_" + "s".repeat(39);

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private ExchangeJournalRepository journalRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private KnownExchangeClients knownClients;
  @Autowired private PlatformTransactionManager transactionManager;

  private MockMvc mockMvc;
  private UUID member;
  private UUID other;
  private String client;
  private boolean wasEnabled;
  private final List<UUID> shipTypes = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();
  private final List<UUID> missions = new ArrayList<>();
  private final List<UUID> orgUnits = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    member = user("ship-writer").getId();
    other = user("ship-other").getId();
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(
            ExchangeCapability.CONNECT,
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
    missions.forEach(id -> jdbc.update("DELETE FROM mission_unit WHERE mission_id = ?", id));
    missions.forEach(id -> jdbc.update("DELETE FROM mission WHERE id = ?", id));
    for (UUID id : List.of(member, other)) {
      jdbc.update("DELETE FROM ship WHERE owner_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }
    orgUnits.forEach(
        id -> jdbc.update("DELETE FROM org_unit_membership WHERE org_unit_id = ?", id));
    orgUnits.forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
    shipTypes.forEach(id -> jdbc.update("DELETE FROM ship_type WHERE id = ?", id));
    locations.forEach(id -> jdbc.update("DELETE FROM location WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aFleetviewHangarIsLinkedBeforeTheFirstSyncAndGetsNoDuplicate() throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    UUID first = ship(member, "Blackbird", cutlass, "LTI");
    UUID second = ship(member, null, cutlass, "6");

    change(
            ops(
                link("vk-1", first),
                link("vk-2", second),
                upsert("vk-1", first, 0L, cutlass, "Night Owl", "{\"kind\":\"LTI\"}", null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(3));

    assertThat(ships(member)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT name FROM ship WHERE id = ?", String.class, first))
        .isEqualTo("Night Owl");
    String feed =
        mockMvc
            .perform(relayed(get(PATH), KEY, "exchange.hangar.read"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> linked = JsonPath.read(feed, "$.items[?(@.shipId=='" + second + "')].externalId");
    assertThat(linked).containsExactly("vk-2");
    String otherFeed =
        mockMvc
            .perform(relayed(get(PATH), OTHER_KEY, "exchange.hangar.read"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<Object> unlinked = JsonPath.read(otherFeed, "$.items[*].externalId");
    assertThat(unlinked).isEmpty();
  }

  @Test
  void aShipCreatedForAMemberOfSeveralUnitsHasNoUnitAndOfOneUnitThatUnit() throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    UUID squadron = orgUnit("SQUADRON");
    UUID command = orgUnit("SPECIAL_COMMAND");
    joins(squadron);
    joins(command);

    change(ops(upsert("vk-9", null, null, cutlass, "Two units", "{\"kind\":\"LTI\"}", null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1));
    assertThat(
            jdbc.queryForList(
                "SELECT owning_org_unit_id FROM ship WHERE owner_id = ? AND name = 'Two units'",
                UUID.class,
                member))
        .containsExactly((UUID) null);

    jdbc.update(
        "DELETE FROM org_unit_membership WHERE user_id = ? AND org_unit_id = ?", member, command);
    change(ops(upsert("vk-10", null, null, cutlass, "One unit", "{\"kind\":\"LTI\"}", null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1));
    assertThat(
            jdbc.queryForObject(
                "SELECT owning_org_unit_id FROM ship WHERE owner_id = ? AND name = 'One unit'",
                UUID.class,
                member))
        .isEqualTo(squadron);
  }

  /**
   * Seeds an active org unit.
   *
   * @param kind its kind
   * @return its id
   */
  private @NotNull UUID orgUnit(@NotNull String kind) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible) VALUES (?, ?, ?, ?, TRUE, FALSE, FALSE)",
        id,
        kind,
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
        "INSERT INTO org_unit_membership (user_id, org_unit_id) VALUES (?, ?)", member, orgUnit);
  }

  @Test
  void anUpsertWithoutAShipIdCreatesTheShipAndLinksIt() throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    UUID lorville = location("Lorville " + UUID.randomUUID());

    change(
            ops(
                upsert(
                    "vk-7",
                    null,
                    null,
                    cutlass,
                    null,
                    "{\"kind\":\"MONTHS\",\"months\":6}",
                    "{\"name\":\"" + locationName(lorville) + "\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.detachedFromMissions").value(0));

    UUID created =
        jdbc.queryForObject("SELECT id FROM ship WHERE owner_id = ?", UUID.class, member);
    assertThat(
            jdbc.queryForObject(
                "SELECT insurance FROM ship WHERE id = ? AND location_id = ?",
                String.class,
                created,
                lorville))
        .isEqualTo("6");
    assertThat(
            jdbc.queryForObject(
                "SELECT external_id FROM exchange_ship_link WHERE ship_id = ?",
                String.class,
                created))
        .isEqualTo("vk-7");
    assertThat(audit("HANGAR_SHIP_CREATED", created)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT client_id FROM audit_event WHERE event_type = 'HANGAR_SHIP_CREATED'"
                    + " AND subject_id = ?",
                String.class,
                created))
        .isEqualTo(client);
    assertThat(journalRepository.findAllByUserIdOrderByRecordedAtAsc(member))
        .singleElement()
        .satisfies(e -> assertThat(e.getAction().name()).isEqualTo("SHIP_UPSERT"));
  }

  @Test
  void aStaleVersionOrALinkedIdWithoutItsShipIsAVersionConflict() throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    UUID first = ship(member, "Blackbird", cutlass, "LTI");
    jdbc.update("UPDATE ship SET version = 3 WHERE id = ?", first);
    change(ops(link("vk-1", first))).andExpect(jsonPath("$.applied").value(1));

    change(
            ops(
                upsert("vk-1", first, 2L, cutlass, "Renamed", "{\"kind\":\"LTI\"}", null),
                upsert("vk-1", null, null, cutlass, "Twin", "{\"kind\":\"LTI\"}", null)))
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].reason").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.results[1].reason").value("VERSION_CONFLICT"));

    assertThat(ships(member)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT name FROM ship WHERE id = ?", String.class, first))
        .isEqualTo("Blackbird");
  }

  @Test
  void aShipIsLinkedOncePerInstallation() throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    UUID first = ship(member, "Blackbird", cutlass, "LTI");
    change(ops(link("vk-1", first))).andExpect(jsonPath("$.applied").value(1));

    change(ops(link("vk-9", first)))
        .andExpect(jsonPath("$.results[0].result").value("rejected"))
        .andExpect(jsonPath("$.results[0].reason").value("LINK_TARGET_TAKEN"));
    changeAs(OTHER_KEY, ops(link("vk-9", first))).andExpect(jsonPath("$.applied").value(1));
    change(ops(link("vk-1", first))).andExpect(jsonPath("$.unchanged").value(1));
  }

  @Test
  void aRemovalDetachesTheShipFromItsMissionUnitsAndReportsIt() throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    UUID first = ship(member, "Blackbird", cutlass, "LTI");
    UUID mission = mission();
    UUID unit = missionUnit(mission, first);
    double framesBefore = frames("hangar_own");

    change(ops(remove(first, 0L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1))
        .andExpect(jsonPath("$.detachedFromMissions").value(1));

    assertThat(ships(member)).isZero();
    assertThat(frames("hangar_own")).isEqualTo(framesBefore + 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM mission_unit WHERE id = ? AND ship_id IS NULL",
                Integer.class,
                unit))
        .isEqualTo(1);
    assertThat(audit("MISSION_UNIT_UPDATED", mission)).isEqualTo(1);
    assertThat(journalRepository.findAllByUserIdOrderByRecordedAtAsc(member))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.getAction().name()).isEqualTo("SHIP_REMOVE");
              assertThat(e.isRemoval()).isTrue();
            });
  }

  @Test
  void aShipRemovedInTheWebComesBackOnlyWithTheMembersConsent() throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    UUID first = ship(member, "Blackbird", cutlass, "LTI");
    change(ops(link("vk-1", first))).andExpect(jsonPath("$.applied").value(1));
    jdbc.update("DELETE FROM ship WHERE id = ?", first);

    String op = upsert("vk-1", first, 0L, cutlass, "Blackbird", "{\"kind\":\"LTI\"}", null);
    change(ops(op))
        .andExpect(jsonPath("$.results[0].result").value("rejected"))
        .andExpect(jsonPath("$.results[0].reason").value("REMOVED_ELSEWHERE"));
    assertThat(ships(member)).isZero();

    change(ops(withOverride(op))).andExpect(jsonPath("$.applied").value(1));
    assertThat(ships(member)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM exchange_ship_link WHERE external_id = 'vk-1'"
                    + " AND user_id = ?",
                Integer.class,
                member))
        .isEqualTo(1);
  }

  @Test
  void anotherMembersShipAndAnUnknownTypeOrPlaceAreNotApplied() throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    UUID theirs = ship(other, "Theirs", cutlass, "LTI");

    change(
            ops(
                link("vk-1", theirs),
                remove(theirs, 0L),
                upsert("vk-2", null, null, UUID.randomUUID(), null, "{\"kind\":\"LTI\"}", null),
                upsert(
                    "vk-3",
                    null,
                    null,
                    cutlass,
                    null,
                    "{\"kind\":\"LTI\"}",
                    "{\"name\":\"Nowhere " + UUID.randomUUID() + "\"}")))
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].result").value("unmatched"))
        .andExpect(jsonPath("$.results[1].result").value("unmatched"))
        .andExpect(jsonPath("$.results[2].result").value("unmatched"))
        .andExpect(jsonPath("$.results[3].reason").value("LOCATION_UNKNOWN"));

    assertThat(ships(other)).isEqualTo(1);
    assertThat(ships(member)).isZero();
  }

  @Test
  void anotherMembersShipIsNeverLockedSoItsOwnersConcurrentEditDoesNotHoldUpTheBatch()
      throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    UUID theirs = ship(other, "Theirs", cutlass, "LTI");
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread owner =
        new Thread(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status -> {
                          jdbc.queryForList("SELECT id FROM ship WHERE id = ? FOR UPDATE", theirs);
                          locked.countDown();
                          try {
                            release.await(30, TimeUnit.SECONDS);
                          } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                          }
                        }));
    owner.start();
    try {
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
      assertTimeoutPreemptively(
          Duration.ofSeconds(10),
          () ->
              change(
                      ops(
                          link("vk-1", theirs),
                          upsert(
                              "vk-2", theirs, 0L, cutlass, "Mine now", "{\"kind\":\"LTI\"}", null),
                          remove(theirs, 0L)))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.applied").value(0))
                  .andExpect(jsonPath("$.results[0].result").value("unmatched"))
                  .andExpect(jsonPath("$.results[1].result").value("unmatched"))
                  .andExpect(jsonPath("$.results[2].result").value("unmatched")));
    } finally {
      release.countDown();
      owner.join(30_000);
    }

    assertThat(jdbc.queryForObject("SELECT name FROM ship WHERE id = ?", String.class, theirs))
        .isEqualTo("Theirs");
  }

  @Test
  void anUpdateThatChangesNameAndTypeCountsAsARemoval() throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    UUID carrack = shipType("Carrack");
    List<UUID> hangar =
        IntStream.range(0, 10).mapToObj(i -> ship(member, "Ship " + i, cutlass, "LTI")).toList();
    String removals =
        hangar.subList(0, 4).stream().map(id -> remove(id, 0L)).collect(Collectors.joining(","));

    change(
            "{\"ops\":["
                + removals
                + ","
                + upsert("vk-9", hangar.get(9), 0L, carrack, "Other", "{\"kind\":\"LTI\"}", null)
                + "]}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MASS_CHANGE_CONFIRMATION_REQUIRED"));
    assertThat(ships(member)).isEqualTo(10);

    change(
            "{\"ops\":["
                + removals
                + ","
                + upsert("vk-9", hangar.get(9), 0L, cutlass, "Other", "{\"kind\":\"LTI\"}", null)
                + "]}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(5));
    assertThat(ships(member)).isEqualTo(6);
  }

  @Test
  void withoutTheWriteCapabilityTheChangeIsRefused() throws Exception {
    mockMvc
        .perform(
            relayed(post(PATH + "/changes"), KEY, "exchange.hangar.read")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ops(remove(UUID.randomUUID(), 0L))))
        .andExpect(status().isForbidden());
  }

  private static @NotNull String ops(@NotNull String... ops) {
    return "{\"ops\":[" + String.join(",", ops) + "]}";
  }

  private static @NotNull String link(@NotNull String externalId, @NotNull UUID shipId) {
    return "{\"op\":\"link\",\"externalId\":\"%s\",\"shipId\":\"%s\"}"
        .formatted(externalId, shipId);
  }

  private static @NotNull String remove(@NotNull UUID shipId, long version) {
    return "{\"op\":\"remove\",\"shipId\":\"%s\",\"version\":%d}".formatted(shipId, version);
  }

  /**
   * Builds one upsert op.
   *
   * @param externalId the installation's id
   * @param shipId the server ship, or {@code null}
   * @param version the version, or {@code null}
   * @param shipType the ship type
   * @param name the name, or {@code null}
   * @param insurance the insurance as JSON
   * @param location the location as JSON, or {@code null}
   * @return the op as JSON
   */
  private static @NotNull String upsert(
      @NotNull String externalId,
      @Nullable UUID shipId,
      @Nullable Long version,
      @NotNull UUID shipType,
      @Nullable String name,
      @NotNull String insurance,
      @Nullable String location) {
    StringBuilder op =
        new StringBuilder("{\"op\":\"upsert\",\"externalId\":\"" + externalId + "\"");
    if (shipId != null) {
      op.append(",\"shipId\":\"").append(shipId).append("\",\"version\":").append(version);
    }
    op.append(",\"shipType\":{\"bt\":\"").append(shipType).append("\"}");
    if (name != null) {
      op.append(",\"name\":\"").append(name).append('"');
    }
    op.append(",\"insurance\":").append(insurance);
    if (location != null) {
      op.append(",\"location\":").append(location);
    }
    return op.append('}').toString();
  }

  private static @NotNull String withOverride(@NotNull String op) {
    return op.substring(0, op.length() - 1) + ",\"override\":true}";
  }

  private ResultActions change(@NotNull String json) throws Exception {
    return changeAs(KEY, json);
  }

  /**
   * Posts a change set as one installation of the test client.
   *
   * @param key the installation's key thumbprint
   * @param json the change set
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions changeAs(@NotNull String key, @NotNull String json) throws Exception {
    return mockMvc.perform(
        relayed(post(PATH + "/changes"), key, "exchange.hangar.write")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  /**
   * Adds the gateway's identity and the relay headers.
   *
   * @param request the request
   * @param key the installation's key thumbprint
   * @param capabilities the relayed capabilities
   * @return the request
   */
  private @NotNull MockHttpServletRequestBuilder relayed(
      @NotNull MockHttpServletRequestBuilder request,
      @NotNull String key,
      @NotNull String capabilities) {
    return request
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capabilities)
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, key);
  }

  private @NotNull User user(@NotNull String prefix) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + "-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    return userRepository.saveAndFlush(user);
  }

  private @NotNull UUID ship(
      @NotNull UUID owner, @Nullable String name, @NotNull UUID type, @NotNull String insurance) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ship (id, version, name, ship_type_id, insurance, fitted, owner_id)"
            + " VALUES (?, 0, ?, ?, ?, false, ?)",
        id,
        name,
        type,
        insurance,
        owner);
    return id;
  }

  private @NotNull UUID shipType(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO ship_type (id, name) VALUES (?, ?)", id, name + " " + id);
    shipTypes.add(id);
    return id;
  }

  private @NotNull UUID location(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO location (id, name, hidden) VALUES (?, ?, false)", id, name);
    locations.add(id);
    return id;
  }

  private @NotNull String locationName(@NotNull UUID location) {
    return jdbc.queryForObject("SELECT name FROM location WHERE id = ?", String.class, location);
  }

  private @NotNull UUID mission() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO mission (id, name, version) VALUES (?, ?, 0)", id, "Op " + id);
    missions.add(id);
    return id;
  }

  private @NotNull UUID missionUnit(@NotNull UUID mission, @NotNull UUID ship) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission_unit (id, mission_id, ship_id, name, high_value_unit, version)"
            + " VALUES (?, ?, ?, 'Alpha', false, 0)",
        id,
        mission,
        ship);
    return id;
  }

  private int ships(@NotNull UUID owner) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM ship WHERE owner_id = ?", Integer.class, owner);
  }

  private int audit(@NotNull String type, @NotNull UUID subject) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_event WHERE event_type = ? AND subject_id = ?",
        Integer.class,
        type,
        subject);
  }

  /**
   * Reads how many live-sync frames the backend accepted for one room class.
   *
   * @param topicClass the room class's metric label
   * @return the count so far
   */
  private double frames(@NotNull String topicClass) {
    io.micrometer.core.instrument.Counter counter =
        meterRegistry
            .find(MetricNames.LIVESYNC_PUBLISH_ACCEPTED)
            .tag(MetricNames.TAG_TOPIC_CLASS, topicClass)
            .counter();
    return counter == null ? 0 : counter.count();
  }
}
