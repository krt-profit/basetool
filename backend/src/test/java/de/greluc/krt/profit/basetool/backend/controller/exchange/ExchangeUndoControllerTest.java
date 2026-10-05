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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.exchange.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintNameNormalizer;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The member undoes a client's writes from the connected-apps page: entries go back to their state
 * before the client's first write in the span, an entry changed afterwards is skipped and reported,
 * and only the member's browser session may do it (REQ-XCH-022). The change-log retention is 30
 * days here, so the undo's reach follows it. Writes commit.
 */
@SpringBootTest
@TestPropertySource(properties = "app.exchange.change-retention.max-age=P30D")
class ExchangeUndoControllerTest {

  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Ux9_" + "u".repeat(39);

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
  private UUID member;
  private UUID other;
  private String client;
  private boolean wasEnabled;
  private final List<UUID> blueprints = new ArrayList<>();
  private final List<UUID> materials = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();
  private final List<UUID> shipTypes = new ArrayList<>();
  private final List<UUID> orgUnits = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("undo-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    member = userRepository.saveAndFlush(user).getId();
    User second = new User();
    second.setId(UUID.randomUUID());
    second.setUsername("undo-other-" + UUID.randomUUID());
    second.setApprovalStatus(ApprovalStatus.ACTIVE);
    second.setInKeycloak(true);
    second.setRoles(
        new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    other = userRepository.saveAndFlush(second).getId();
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(
            ExchangeCapability.CONNECT,
            ExchangeCapability.BLUEPRINTS_WRITE,
            ExchangeCapability.STOCK_WRITE,
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
    jdbc.update("DELETE FROM personal_blueprint WHERE owner_user_id = ?", member);
    jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", member);
    for (UUID id : List.of(member, other)) {
      jdbc.update("DELETE FROM ship WHERE owner_id = ?", id);
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", id);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }
    orgUnits.forEach(id -> jdbc.update("DELETE FROM org_unit WHERE id = ?", id));
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
  void blueprintsGoBackToTheirStateBeforeTheClientsWrites() throws Exception {
    String rifle = product("Arrowhead Rifle");
    String pistol = product("Arclight Pistol");
    owns(pistol);
    write(
            "blueprints",
            "{\"ops\":[{\"op\":\"add\",\"ref\":{\"bt\":\"%s\"}},{\"op\":\"remove\",\"key\":\"%s\"}]}"
                .formatted(rifle, pistol))
        .andExpect(jsonPath("$.applied").value(2));

    undo(Instant.now().minus(1, ChronoUnit.HOURS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.restored").value(2))
        .andExpect(jsonPath("$.skipped.length()").value(0));

    assertThat(owned()).containsExactly(pistol);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM exchange_journal WHERE user_id = ? AND undone_at IS NULL",
                Integer.class,
                member))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type = 'EXCHANGE_CHANGES_UNDONE'"
                    + " AND target_user_id = ?",
                Integer.class,
                member))
        .isEqualTo(1);
  }

  @Test
  void aLotChangedInTheWebAfterwardsIsSkippedAndReported() throws Exception {
    UUID laranite = material("Laranite");
    UUID agricium = material("Agricium");
    String place = locationName(location());
    write(
            "stock",
            "{\"ops\":[" + lot(laranite, place, "5") + "," + lot(agricium, place, "3") + "]}")
        .andExpect(jsonPath("$.applied").value(2));
    jdbc.update(
        "UPDATE inventory_item SET amount = 7 WHERE user_id = ? AND material_id = ?",
        member,
        agricium);

    undo(Instant.now().minus(1, ChronoUnit.HOURS))
        .andExpect(jsonPath("$.restored").value(1))
        .andExpect(jsonPath("$.skipped[0].resource").value("STOCK"))
        .andExpect(jsonPath("$.skipped[0].reason").value("CHANGED_AFTERWARDS"))
        .andExpect(jsonPath("$.skipped[0].label").value(materialName(agricium)));

    assertThat(total(laranite)).isZero();
    assertThat(total(agricium)).isEqualTo(7.0);
  }

  @Test
  void aLotWhosePlaceIsGoneIsSkippedAndReported() throws Exception {
    UUID laranite = material("Laranite");
    UUID place = location();
    String name = locationName(place);
    write("stock", "{\"ops\":[" + lot(laranite, name, "5") + "]}")
        .andExpect(jsonPath("$.applied").value(1));
    write(
            "stock",
            """
            {"ops":[{"op":"set-quantity","material":{"bt":"%s"},"location":{"name":"%s"},
                     "quality":500,"stolen":false,"quantity":{"amount":0,"unit":"SCU"},
                     "expectedQuantity":{"amount":5,"unit":"SCU"}}]}
            """
                .formatted(laranite, name))
        .andExpect(jsonPath("$.applied").value(1));
    jdbc.update("DELETE FROM location WHERE id = ?", place);
    locations.remove(place);

    undo(Instant.now().minus(1, ChronoUnit.HOURS))
        .andExpect(jsonPath("$.restored").value(0))
        .andExpect(jsonPath("$.skipped[0].resource").value("STOCK"))
        .andExpect(jsonPath("$.skipped[0].reason").value("GONE"));

    assertThat(total(laranite)).isZero();
  }

  @Test
  void shipsAreRemovedRenamedBackAndRecreated() throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    UUID renamed = ship("Old name", cutlass);
    UUID removed = ship("Retired", cutlass);
    write(
            "ships",
            "{\"ops\":[{\"op\":\"link\",\"externalId\":\"vk-1\",\"shipId\":\"%s\"},"
                    .formatted(renamed)
                + ("{\"op\":\"upsert\",\"externalId\":\"vk-1\",\"shipId\":\"%s\",\"version\":0,"
                        + "\"shipType\":{\"bt\":\"%s\"},\"name\":\"New name\","
                        + "\"insurance\":{\"kind\":\"LTI\"}},")
                    .formatted(renamed, cutlass)
                + ("{\"op\":\"upsert\",\"externalId\":\"vk-2\",\"shipType\":{\"bt\":\"%s\"},"
                        + "\"insurance\":{\"kind\":\"LTI\"}},")
                    .formatted(cutlass)
                + "{\"op\":\"remove\",\"shipId\":\"%s\",\"version\":0}]}".formatted(removed))
        .andExpect(jsonPath("$.applied").value(4));
    assertThat(names()).containsExactlyInAnyOrder("New name", null);

    undo(Instant.now().minus(1, ChronoUnit.HOURS)).andExpect(jsonPath("$.restored").value(3));

    assertThat(names()).containsExactlyInAnyOrder("Old name", "Retired");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM exchange_ship_link WHERE user_id = ?", Integer.class, member))
        .isZero();
  }

  @Test
  void onlyTheWritesSinceThePointInTimeAreUndone() throws Exception {
    String rifle = product("Arrowhead Rifle");
    String pistol = product("Arclight Pistol");
    write("blueprints", "{\"ops\":[{\"op\":\"add\",\"ref\":{\"bt\":\"%s\"}}]}".formatted(rifle));
    jdbc.update(
        "UPDATE exchange_journal SET recorded_at = ? WHERE user_id = ?",
        Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS)),
        member);
    write("blueprints", "{\"ops\":[{\"op\":\"add\",\"ref\":{\"bt\":\"%s\"}}]}".formatted(pistol));

    undo(Instant.now().minus(1, ChronoUnit.HOURS)).andExpect(jsonPath("$.restored").value(1));

    assertThat(owned()).containsExactly(rifle);
  }

  @Test
  void anEntryWhoseChangeLogEntryIsMissingIsSkippedAsChangedAfterwards() throws Exception {
    String rifle = product("Arrowhead Rifle");
    write("blueprints", "{\"ops\":[{\"op\":\"add\",\"ref\":{\"bt\":\"%s\"}}]}".formatted(rifle));
    jdbc.update("DELETE FROM exchange_change WHERE user_id = ?", member);

    undo(Instant.now().minus(1, ChronoUnit.HOURS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.restored").value(0))
        .andExpect(jsonPath("$.skipped[0].resource").value("BLUEPRINT"))
        .andExpect(jsonPath("$.skipped[0].reason").value("CHANGED_AFTERWARDS"));

    assertThat(owned()).containsExactly(rifle);
  }

  @Test
  void theUndoReachesOnlyAsFarBackAsTheConfiguredRetention() throws Exception {
    String rifle = product("Arrowhead Rifle");
    write("blueprints", "{\"ops\":[{\"op\":\"add\",\"ref\":{\"bt\":\"%s\"}}]}".formatted(rifle));
    jdbc.update(
        "UPDATE exchange_journal SET recorded_at = ? WHERE user_id = ?",
        Timestamp.from(Instant.now().minus(40, ChronoUnit.DAYS)),
        member);

    undo(Instant.now().minus(60, ChronoUnit.DAYS)).andExpect(jsonPath("$.restored").value(0));

    assertThat(owned()).containsExactly(rifle);
  }

  @Test
  void aShipRemovedFromAMemberOfSeveralUnitsComesBackWithoutAUnit() throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    UUID removed = ship("Retired", cutlass);
    joins(orgUnit("SQUADRON"));
    joins(orgUnit("SPECIAL_COMMAND"));
    String rifle = product("Arrowhead Rifle");
    write("blueprints", "{\"ops\":[{\"op\":\"add\",\"ref\":{\"bt\":\"%s\"}}]}".formatted(rifle));
    write(
            "ships",
            "{\"ops\":[{\"op\":\"remove\",\"shipId\":\"%s\",\"version\":0}]}".formatted(removed))
        .andExpect(jsonPath("$.applied").value(1));

    undo(Instant.now().minus(1, ChronoUnit.HOURS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.restored").value(2));

    assertThat(names()).containsExactly("Retired");
    assertThat(
            jdbc.queryForList(
                "SELECT owning_org_unit_id FROM ship WHERE owner_id = ?", UUID.class, member))
        .containsExactly((UUID) null);
    assertThat(owned()).isEmpty();
  }

  @Test
  void aReplacedLinkIsNotPutBackOnAShipGivenToAnotherMember() throws Exception {
    UUID cutlass = shipType("Cutlass Black");
    UUID given = ship("Given away", cutlass);
    write(
            "ships",
            "{\"ops\":[{\"op\":\"link\",\"externalId\":\"vk-new\",\"shipId\":\"%s\"}]}"
                .formatted(given))
        .andExpect(jsonPath("$.applied").value(1));
    jdbc.update(
        "UPDATE exchange_journal SET before_state = '{\"externalId\":\"vk-old\"}'"
            + " WHERE user_id = ? AND action = 'SHIP_LINK'",
        member);
    jdbc.update("UPDATE ship SET owner_id = ? WHERE id = ?", other, given);

    undo(Instant.now().minus(1, ChronoUnit.HOURS)).andExpect(status().isOk());

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM exchange_ship_link WHERE user_id = ?", Integer.class, member))
        .isZero();
  }

  @Test
  void onlyTheMembersBrowserSessionMayUndo() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/connected-apps/" + client + "/undo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"since\":\"" + Instant.now() + "\"}")
                .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
                .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
                .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, "exchange.connect")
                .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/connected-apps/unknown-client/undo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"since\":\"" + Instant.now() + "\"}")
                .with(browser()))
        .andExpect(status().isNotFound());
  }

  private ResultActions undo(@NotNull Instant since) throws Exception {
    return mockMvc.perform(
        post("/api/v1/connected-apps/" + client + "/undo")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"since\":\"" + since + "\"}")
            .with(browser()));
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor browser() {
    return jwt().jwt(t -> t.subject(member.toString()).claim("azp", "basetool-frontend"));
  }

  /**
   * Posts a change set as the test client.
   *
   * @param resource {@code blueprints}, {@code stock} or {@code ships}
   * @param json the change set
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions write(@NotNull String resource, @NotNull String json) throws Exception {
    String capability =
        switch (resource) {
          case "blueprints" -> "exchange.blueprints.write";
          case "stock" -> "exchange.stock.write";
          default -> "exchange.hangar.write";
        };
    return mockMvc
        .perform(
            post("/api/v1/exchange/me/" + resource + "/changes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
                .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
                .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capability)
                .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY))
        .andExpect(status().isOk());
  }

  private static @NotNull String lot(
      @NotNull UUID material, @NotNull String place, @NotNull String quantity) {
    return """
    {"op":"set-quantity","material":{"bt":"%s"},"location":{"name":"%s"},"quality":500,
     "stolen":false,"quantity":{"amount":%s,"unit":"SCU"},
     "expectedQuantity":{"amount":0,"unit":"SCU"}}\
    """
        .formatted(material, place, quantity);
  }

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

  private void owns(@NotNull String productKey) {
    jdbc.update(
        "INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name)"
            + " VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        member,
        productKey,
        productKey);
  }

  private @NotNull List<String> owned() {
    return jdbc.queryForList(
        "SELECT product_key FROM personal_blueprint WHERE owner_user_id = ?", String.class, member);
  }

  private @NotNull UUID material(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,"
            + " is_job_order, is_visible, source_systems)"
            + " VALUES (?, ?, 'REFINED', 'SCU', false, false, true, 'UEX_ONLY')",
        id,
        name + " " + id);
    materials.add(id);
    return id;
  }

  private @NotNull String materialName(@NotNull UUID material) {
    return jdbc.queryForObject("SELECT name FROM material WHERE id = ?", String.class, material);
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

  private @NotNull UUID shipType(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO ship_type (id, name) VALUES (?, ?)", id, name + " " + id);
    shipTypes.add(id);
    return id;
  }

  private @NotNull UUID ship(@NotNull String name, @NotNull UUID type) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ship (id, version, name, ship_type_id, insurance, fitted, owner_id)"
            + " VALUES (?, 0, ?, ?, 'LTI', false, ?)",
        id,
        name,
        type,
        member);
    return id;
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

  private @NotNull List<String> names() {
    return jdbc.queryForList("SELECT name FROM ship WHERE owner_id = ?", String.class, member);
  }
}
