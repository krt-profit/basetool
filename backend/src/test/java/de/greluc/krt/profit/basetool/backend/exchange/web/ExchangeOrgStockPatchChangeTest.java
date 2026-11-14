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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchChangeWrites;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchKind;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeDto;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeOptions;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeTrigger;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.TriggerPatchChangeRequest;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * An org-stock op against an entry a patch change moved or wiped names the patch change, judged by
 * the entry's own latest org-stock feed entry and never by a lot-level re-announce (REQ-PATCH-057).
 *
 * <p>Its live time lies in 2000, before every other test's rows, and its options wipe nothing; the
 * wipe is the patch change's deletion of one row, and every patch change is removed afterwards, as
 * in {@code ExchangePatchChangeControllerTest}.
 */
@SpringBootTest
class ExchangeOrgStockPatchChangeTest {

  private static final String CHANGES = "/api/v1/exchange/org-stock/changes";
  private static final String CHOICE = "/api/v1/exchange/me/patch-change/home-location";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "o".repeat(39);
  private static final String ORG_WRITE = "exchange.orgstock.write";
  private static final String PATCH_BOTH = "exchange.patch.read,exchange.patch.write";
  private static final Instant LIVE_AT = Instant.parse("2000-01-01T00:00:00Z");
  private static final Instant BEFORE = Instant.parse("1999-06-01T00:00:00Z");

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private KnownExchangeClients knownClients;
  @Autowired private PatchChangeTrigger trigger;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> users = new ArrayList<>();
  private final List<UUID> locations = new ArrayList<>();

  private MockMvc mockMvc;
  private UUID admin;
  private UUID writer;
  private UUID holder;
  private UUID pool;
  private UUID material;
  private UUID port;
  private UUID home;
  private String client;
  private boolean wasEnabled;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    admin = user("patch-org-admin-", null);
    writer = user("patch-org-writer-", null);
    holder = user("patch-org-holder-", BEFORE);
    port = location("Patch org port ", false);
    home = location("Patch org home ", true);
    material = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'RAW', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        material,
        "Patch org ore " + material);
    pool = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_profit_eligible)"
            + " VALUES (?, 'SPECIAL_COMMAND', ?, ?, TRUE, FALSE)",
        pool,
        "Patch pool " + pool,
        "Q" + pool.toString().substring(0, 8));
    joins(writer, true);
    joins(holder, false);
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
            ExchangeCapability.ORGSTOCK_WRITE,
            ExchangeCapability.PATCH_READ,
            ExchangeCapability.PATCH_WRITE));
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
    SecurityContextHolder.clearContext();
    jdbc.update("DELETE FROM patch_change");
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    for (UUID user : users) {
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", user);
      jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", user);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", user);
      jdbc.update("DELETE FROM app_user WHERE id = ?", user);
    }
    jdbc.update("DELETE FROM org_unit WHERE id = ?", pool);
    jdbc.update("DELETE FROM material WHERE id = ?", material);
    for (UUID location : locations) {
      UUID city =
          jdbc.queryForObject("SELECT city_id FROM location WHERE id = ?", UUID.class, location);
      jdbc.update("DELETE FROM location WHERE id = ?", location);
      jdbc.update("DELETE FROM city WHERE id = ?", city);
    }
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aStaleOpOnAnEntryThePatchChangeMovedIsAVersionConflictNamingIt() throws Exception {
    UUID entry = entry(BEFORE);
    PatchChangeDto recorded = trigger(true);
    mockMvc
        .perform(
            relayed(post(CHOICE), PATCH_BOTH, holder)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"location\":\"" + home + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows").value(1));

    note(entry, 0)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].result").value("rejected"))
        .andExpect(jsonPath("$.results[0].reason").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.results[0].patchChange.id").value(recorded.id().toString()))
        .andExpect(jsonPath("$.results[0].patchChange.label").value("0.1"));
  }

  @Test
  void anOpOnAnEntryThePatchChangeWipedIsRemovedElsewhereNamingIt() throws Exception {
    UUID entry = entry(BEFORE);
    PatchChangeDto recorded = trigger(false);
    asThePatchChange(recorded, "DELETE FROM inventory_item WHERE id = ?", entry);

    note(entry, 0)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].result").value("rejected"))
        .andExpect(jsonPath("$.results[0].reason").value("REMOVED_ELSEWHERE"))
        .andExpect(jsonPath("$.results[0].patchChange.id").value(recorded.id().toString()))
        .andExpect(jsonPath("$.results[0].patchChange.label").value("0.1"));
  }

  @Test
  void aLotLevelReannounceByThePatchChangeNamesItForNoEntry() throws Exception {
    UUID gone = entry(Instant.now());
    UUID stale = entry(Instant.now());
    jdbc.update("UPDATE inventory_item SET note = 'web', version = 1 WHERE id = ?", stale);
    jdbc.update("DELETE FROM inventory_item WHERE id = ?", gone);
    PatchChangeDto recorded = trigger(false);
    asThePatchChange(recorded, "UPDATE org_unit SET name = name || ' renamed' WHERE id = ?", pool);
    Map<String, Object> latest =
        jdbc.queryForMap(
            "SELECT source_channel, org_entry_id FROM exchange_change WHERE org_unit_id = ?"
                + " ORDER BY tx DESC, seq DESC LIMIT 1",
            pool);
    assertThat(latest.get("source_channel")).isEqualTo("patch");
    assertThat(latest.get("org_entry_id")).isNull();

    note(gone, 0)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].reason").value("NOT_PERMITTED"))
        .andExpect(jsonPath("$.results[0].patchChange").doesNotExist());
    note(stale, 0)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].reason").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.results[0].patchChange").doesNotExist());
  }

  /**
   * Sends the writer's {@code note} op on one entry of the pool.
   *
   * @param entry the entry
   * @param version the version the client read
   * @return the result
   * @throws Exception if the request fails
   */
  private @NotNull ResultActions note(@NotNull UUID entry, long version) throws Exception {
    return mockMvc.perform(
        relayed(post(CHANGES), ORG_WRITE, writer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"ops":[{"op":"note","unit":"%s","entry":"%s","version":%d,"note":"x"}]}
                """
                    .formatted(pool, entry, version)));
  }

  /**
   * Runs one statement in its own transaction as the patch change's write.
   *
   * @param recorded the patch change
   * @param sql the statement
   * @param id its one parameter
   */
  private void asThePatchChange(
      @NotNull PatchChangeDto recorded, @NotNull String sql, @NotNull UUID id) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    PatchChangeWrites.runFor(recorded.id(), () -> transaction.execute(_ -> jdbc.update(sql, id)));
  }

  /**
   * Triggers a rebuild that wipes nothing.
   *
   * @param homeLocationPrompt whether it asks the members created before the live time for their
   *     home location
   * @return the recorded patch change
   */
  private @NotNull PatchChangeDto trigger(boolean homeLocationPrompt) {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject(admin.toString())
            .claim("azp", "basetool-frontend")
            .issuedAt(Instant.now())
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    try {
      return trigger.trigger(
          new TriggerPatchChangeRequest(
              "0.1",
              PatchKind.REBUILD,
              LIVE_AT,
              new PatchChangeOptions(Set.of(), Set.of(), false, false, homeLocationPrompt),
              "0.1"));
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  /**
   * Adds the gateway's identity and the relay headers.
   *
   * @param request the request
   * @param capabilities the relayed capabilities
   * @param member the acting member
   * @return the request
   */
  private @NotNull MockHttpServletRequestBuilder relayed(
      @NotNull MockHttpServletRequestBuilder request,
      @NotNull String capabilities,
      @NotNull UUID member) {
    return request
        .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capabilities)
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY);
  }

  /**
   * Inserts one SCU of the holder's shared stock in the pool at the port.
   *
   * @param createdAt when the row was created
   * @return its id
   */
  private @NotNull UUID entry(@NotNull Instant createdAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, version, user_id, material_id, location_id, quality,
                                    amount, personal, stolen, owning_org_unit_id, created_at)
        VALUES (?, 0, ?, ?, ?, 500, 1, false, false, ?, ?)
        """,
        id,
        holder,
        material,
        port,
        pool,
        Timestamp.from(createdAt));
    return id;
  }

  /**
   * Makes a user a direct member of the pool's unit.
   *
   * @param user the user
   * @param logistician whether the membership carries the Logistician flag
   */
  private void joins(@NotNull UUID user, boolean logistician) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id, role, is_logistician)"
            + " VALUES (?, ?, 'MEMBER', ?)",
        user,
        pool,
        logistician);
  }

  /**
   * Persists an active member.
   *
   * @param prefix the username's prefix
   * @param createdAt the account's creation time, or {@code null} for now
   * @return the member's id
   */
  private @NotNull UUID user(@NotNull String prefix, @Nullable Instant createdAt) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(prefix + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    UUID id = userRepository.saveAndFlush(user).getId();
    if (createdAt != null) {
      jdbc.update("UPDATE app_user SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), id);
    }
    users.add(id);
    return id;
  }

  /**
   * Inserts a location on a city of its own.
   *
   * @param prefix the name's prefix
   * @param curatedHome whether it is a curated home location
   * @return its id
   */
  private @NotNull UUID location(@NotNull String prefix, boolean curatedHome) {
    UUID id = UUID.randomUUID();
    TestPlaces.insertLocation(jdbc, id, prefix + id);
    jdbc.update("UPDATE location SET is_home_location = ? WHERE id = ?", curatedHome, id);
    locations.add(id);
    return id;
  }
}
