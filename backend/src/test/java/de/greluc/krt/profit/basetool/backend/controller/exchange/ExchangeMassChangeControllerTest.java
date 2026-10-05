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
import java.time.Duration;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The member confirms a change set the mass-change guard held back: the page previews it, then
 * applies it as the client's own write, after checking the client and installation again; only the
 * member's browser session may do it (REQ-XCH-021). Writes commit.
 */
@SpringBootTest
class ExchangeMassChangeControllerTest {

  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Mx9_" + "m".repeat(39);

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
  private String client;
  private UUID clientUuid;
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
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("mass-" + UUID.randomUUID());
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
            ExchangeCapability.BLUEPRINTS_WRITE,
            ExchangeCapability.STOCK_WRITE,
            ExchangeCapability.HANGAR_WRITE));
    clientUuid = clientRepository.saveAndFlush(registered).getId();
    knownClients.invalidate();
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", clientUuid);
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    jdbc.update("DELETE FROM personal_blueprint WHERE owner_user_id = ?", member);
    jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", member);
    jdbc.update("DELETE FROM ship WHERE owner_id = ?", member);
    jdbc.update("DELETE FROM user_roles WHERE user_id = ?", member);
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
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
  void aHeldBackBatchIsPreviewedAndThenAppliedAsTheClientsOwnWrite() throws Exception {
    List<String> products = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      String key = product("Rifle " + i);
      owns(key);
      products.add(key);
    }
    String changeSet = removeAll(products);
    mockMvc
        .perform(clientWrite("blueprints", changeSet.replace("]}", "],\"dryRun\":true}")))
        .andExpect(status().isOk());
    mockMvc
        .perform(clientWrite("blueprints", changeSet))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MASS_CHANGE_CONFIRMATION_REQUIRED"));

    massChange("preview", staged("blueprints", changeSet))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clientName").value("VerseKit"))
        .andExpect(jsonPath("$.dryRun").value(true))
        .andExpect(jsonPath("$.applied").value(6));
    assertThat(owned()).hasSize(6);

    massChange("confirm", staged("blueprints", changeSet))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dryRun").value(false))
        .andExpect(jsonPath("$.applied").value(6));
    assertThat(owned()).isEmpty();
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT source_channel || '|' || source_client || '|' || source_key"
                    + " FROM exchange_change WHERE user_id = ? AND resource = 'BLUEPRINT'"
                    + " AND source_channel <> 'system'",
                String.class,
                member))
        .containsExactly("client|" + client + "|" + KEY);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM exchange_journal WHERE user_id = ? AND removal",
                Integer.class,
                member))
        .isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type ="
                    + " 'EXCHANGE_MASS_CHANGE_CONFIRMED' AND target_user_id = ?",
                Integer.class,
                member))
        .isEqualTo(1);
  }

  @Test
  void aSuspendedClientOrADisconnectedInstallationCannotBeConfirmed() throws Exception {
    String rifle = product("Arrowhead Rifle");
    mockMvc
        .perform(
            clientWrite(
                "blueprints", "{\"ops\":[{\"op\":\"add\",\"ref\":{\"bt\":\"" + rifle + "\"}}]}"))
        .andExpect(status().isOk());
    String changeSet = removeAll(List.of(rifle));

    jdbc.update("UPDATE exchange_client SET status = 'SUSPENDED' WHERE client_id = ?", client);
    massChange("confirm", staged("blueprints", changeSet)).andExpect(status().isForbidden());

    jdbc.update("UPDATE exchange_client SET status = 'ACTIVE' WHERE client_id = ?", client);
    jdbc.update("UPDATE exchange_installation SET revoked_at = now() WHERE user_id = ?", member);
    massChange("confirm", staged("blueprints", changeSet)).andExpect(status().isForbidden());

    assertThat(owned()).containsExactly(rifle);
  }

  @Test
  void aBatchCannotBeConfirmedAfterTheClientWasDisconnectedSinceItsStaging() throws Exception {
    String rifle = ownedViaClient("Arrowhead Rifle");
    String changeSet = removeAll(List.of(rifle));
    Instant stagedAt = Instant.now().minus(Duration.ofMinutes(10));

    jdbc.update(
        "INSERT INTO exchange_client_revocation (exchange_client_id, user_id, revoked_at)"
            + " VALUES (?, ?, ?)",
        clientUuid,
        member,
        Timestamp.from(stagedAt.plus(Duration.ofMinutes(4))));

    massChange("preview", staged("blueprints", changeSet, stagedAt))
        .andExpect(status().isForbidden());
    massChange("confirm", staged("blueprints", changeSet, stagedAt))
        .andExpect(status().isForbidden());
    assertThat(owned()).containsExactly(rifle);
  }

  @Test
  void aDisconnectBeforeTheStagingDoesNotRefuseTheBatch() throws Exception {
    String rifle = ownedViaClient("Arrowhead Rifle");
    String changeSet = removeAll(List.of(rifle));
    Instant stagedAt = Instant.now().minus(Duration.ofMinutes(10));

    jdbc.update(
        "INSERT INTO exchange_client_revocation (exchange_client_id, user_id, revoked_at)"
            + " VALUES (?, ?, ?)",
        clientUuid,
        member,
        Timestamp.from(stagedAt.minus(Duration.ofMinutes(5))));

    massChange("confirm", staged("blueprints", changeSet, stagedAt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(1));
    assertThat(owned()).isEmpty();
  }

  @Test
  void aClientSuspendedSinceTheStagingIsRefusedEvenWhenActiveAgain() throws Exception {
    String rifle = ownedViaClient("Arrowhead Rifle");
    String changeSet = removeAll(List.of(rifle));
    Instant stagedAt = Instant.now().minus(Duration.ofMinutes(10));

    jdbc.update(
        "INSERT INTO audit_event (id, occurred_at, domain, event_type, actor_handle, subject_id)"
            + " VALUES (?, ?, 'CONNECTED_APPS', 'EXCHANGE_CLIENT_SUSPENDED', 'system', ?)",
        UUID.randomUUID(),
        Timestamp.from(stagedAt.plus(Duration.ofMinutes(2))),
        clientUuid);

    massChange("confirm", staged("blueprints", changeSet, stagedAt))
        .andExpect(status().isForbidden());
    assertThat(owned()).containsExactly(rifle);
  }

  @Test
  void aBatchPastItsStagingLifetimeOrStagedInTheFutureIsRefused() throws Exception {
    String rifle = ownedViaClient("Arrowhead Rifle");
    String changeSet = removeAll(List.of(rifle));

    massChange(
            "confirm", staged("blueprints", changeSet, Instant.now().minus(Duration.ofMinutes(31))))
        .andExpect(status().isForbidden());
    massChange(
            "confirm", staged("blueprints", changeSet, Instant.now().plus(Duration.ofMinutes(5))))
        .andExpect(status().isBadRequest());
    massChange(
            "confirm",
            ("{\"clientId\":\"%s\",\"installationKey\":\"%s\","
                    + "\"resource\":\"blueprints\",\"changeSet\":%s}")
                .formatted(client, KEY, quote(changeSet)))
        .andExpect(status().isBadRequest());
    assertThat(owned()).containsExactly(rifle);
  }

  @Test
  void aChangeSetThatDoesNotReadIsABadRequest() throws Exception {
    String rifle = product("Arrowhead Rifle");
    mockMvc
        .perform(
            clientWrite(
                "blueprints", "{\"ops\":[{\"op\":\"add\",\"ref\":{\"bt\":\"" + rifle + "\"}}]}"))
        .andExpect(status().isOk());

    massChange("preview", staged("blueprints", "not json")).andExpect(status().isBadRequest());
    massChange("preview", staged("blueprints", "{\"ops\":[]}")).andExpect(status().isBadRequest());
  }

  @Test
  void onlyTheMembersBrowserSessionMayConfirm() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/connected-apps/mass-changes/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(staged("blueprints", "{\"ops\":[]}"))
                .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
                .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
                .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
                .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, "exchange.connect")
                .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY))
        .andExpect(status().isForbidden());
  }

  private static @NotNull String removeAll(@NotNull List<String> keys) {
    List<String> ops = new ArrayList<>();
    keys.forEach(k -> ops.add("{\"op\":\"remove\",\"key\":\"" + k + "\"}"));
    return "{\"ops\":[" + String.join(",", ops) + "]}";
  }

  private @NotNull String staged(@NotNull String resource, @NotNull String changeSet) {
    return staged(resource, changeSet, Instant.now());
  }

  /**
   * Builds the staged change set the page hands back.
   *
   * @param resource the resource
   * @param changeSet the change set
   * @param stagedAt when the gateway staged it
   * @return the request as JSON
   */
  private @NotNull String staged(
      @NotNull String resource, @NotNull String changeSet, @NotNull Instant stagedAt) {
    return ("{\"clientId\":\"%s\",\"installationKey\":\"%s\",\"resource\":\"%s\","
            + "\"changeSet\":%s,\"stagedAt\":\"%s\"}")
        .formatted(client, KEY, resource, quote(changeSet), stagedAt);
  }

  /**
   * Adds a blueprint through the client, which also registers the test installation.
   *
   * @param name the blueprint's name
   * @return its product key
   * @throws Exception if the request fails
   */
  private @NotNull String ownedViaClient(@NotNull String name) throws Exception {
    String key = product(name);
    mockMvc
        .perform(
            clientWrite(
                "blueprints", "{\"ops\":[{\"op\":\"add\",\"ref\":{\"bt\":\"" + key + "\"}}]}"))
        .andExpect(status().isOk());
    return key;
  }

  private static @NotNull String quote(@NotNull String raw) {
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private ResultActions massChange(@NotNull String step, @NotNull String json) throws Exception {
    return mockMvc.perform(
        post("/api/v1/connected-apps/mass-changes/" + step)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .with(browser()));
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder clientWrite(
      @NotNull String resource, @NotNull String json) {
    return post("/api/v1/exchange/me/" + resource + "/changes")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json)
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, "exchange.blueprints.write")
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY);
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor browser() {
    return jwt().jwt(t -> t.subject(member.toString()).claim("azp", "basetool-frontend"));
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

  private @NotNull List<String> names() {
    return jdbc.queryForList("SELECT name FROM ship WHERE owner_id = ?", String.class, member);
  }
}
