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

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
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
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeDto;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeOptions;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeTrigger;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchKind;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.TriggerPatchChangeRequest;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.WipeException;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.WipeExceptionMode;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.WipeExceptionTarget;
import de.greluc.krt.profit.basetool.backend.platform.api.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.support.TestPlaces;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
 * The patch change relayed from the ingest gateway (REQ-PATCH-050…053): the read of the latest
 * patch change with its options and the member's prompt, the choice with the web's rules made as
 * the client, the scopes, and the refusal of a push against a lot or ship a patch change removed.
 *
 * <p>Its live times lie in 2000, before every other test's rows, and its options wipe nothing;
 * every patch change is removed afterwards, as in {@code PatchChangeTriggerIntegrationTest}.
 */
@SpringBootTest
class ExchangePatchChangeControllerTest {

  private static final String PATH = "/api/v1/exchange/me/patch-change";
  private static final String CHOICE = PATH + "/home-location";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "p".repeat(39);
  private static final String READ = "exchange.patch.read";
  private static final String BOTH = "exchange.patch.read,exchange.patch.write";
  private static final Instant LIVE_AT = Instant.parse("2000-01-01T00:00:00Z");
  private static final Instant BEFORE = Instant.parse("1999-06-01T00:00:00Z");

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private KnownExchangeClients knownClients;
  @Autowired private PatchChangeTrigger trigger;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> locations = new ArrayList<>();

  private MockMvc mockMvc;
  private UUID admin;
  private UUID member;
  private UUID home;
  private UUID port;
  private UUID material;
  private UUID squadron;
  private UUID shipType;
  private int uexCity;
  private String client;
  private boolean wasEnabled;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    admin = persistUser("patch-xch-admin-", null);
    member = persistUser("patch-xch-member-", BEFORE);
    home = location("Patch home ", true);
    port = location("Patch port ", false);
    uexCity = ThreadLocalRandom.current().nextInt(1_000_000, 2_000_000_000);
    jdbc.update(
        "UPDATE city SET id_city = ?, star_system_name = 'Stanton'"
            + " WHERE id = (SELECT city_id FROM location WHERE id = ?)",
        uexCity,
        home);
    material = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,
                              is_job_order, is_visible, source_systems)
        VALUES (?, ?, 'NO_REFINE', 'SCU', false, false, true, 'UEX_ONLY')
        """,
        material,
        "Patch ore " + material);
    squadron = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_profit_eligible,"
            + " parent_org_unit_id) VALUES (?, 'SQUADRON', ?, ?, TRUE, FALSE, NULL)",
        squadron,
        "Patch squadron " + squadron,
        "P" + squadron.toString().substring(0, 8));
    ShipType type = new ShipType();
    type.setName("Patch hull " + UUID.randomUUID());
    shipType = shipTypeRepository.saveAndFlush(type).getId();
    client = "vk-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("VerseKit");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(
            ExchangeCapability.CONNECT,
            ExchangeCapability.PATCH_READ,
            ExchangeCapability.PATCH_WRITE,
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
    SecurityContextHolder.clearContext();
    jdbc.update("DELETE FROM patch_change");
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", client);
    for (UUID user : List.of(admin, member)) {
      jdbc.update("DELETE FROM inventory_item WHERE user_id = ?", user);
      jdbc.update("DELETE FROM ship WHERE owner_id = ?", user);
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", user);
      jdbc.update("DELETE FROM app_user WHERE id = ?", user);
    }
    jdbc.update("DELETE FROM ship_type WHERE id = ?", shipType);
    jdbc.update("DELETE FROM org_unit WHERE id = ?", squadron);
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
  void beforeAnyPatchChangeTheReadNamesNoneAndOffersTheHomeLocations() throws Exception {
    mockMvc
        .perform(relayed(get(PATH), READ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.patchChange").doesNotExist())
        .andExpect(jsonPath("$.homeLocation.state").value("NOT_ASKED"))
        .andExpect(jsonPath("$.homeLocation.patchChange").doesNotExist())
        .andExpect(jsonPath("$.homeLocation.preview.length()").value(0))
        .andExpect(
            jsonPath("$.homeLocation.locations[?(@.id == '%s')].system", home).value("Stanton"))
        .andExpect(
            jsonPath("$.homeLocation.locations[?(@.id == '%s')].uex.id", home).value(uexCity))
        .andExpect(jsonPath("$.homeLocation.locations[?(@.id == '%s')]", port).isEmpty());
  }

  @Test
  void theReadNamesTheLatestPatchChangeWithItsOptionsAndThePendingMembersPreview()
      throws Exception {
    row(port, null);
    row(port, squadron);
    PatchChangeDto recorded = trigger();

    mockMvc
        .perform(relayed(get(PATH), READ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.patchChange.id").value(recorded.id().toString()))
        .andExpect(jsonPath("$.patchChange.label").value("0.1"))
        .andExpect(jsonPath("$.patchChange.kind").value("REBUILD"))
        .andExpect(jsonPath("$.patchChange.liveAt").value("2000-01-01T00:00:00Z"))
        .andExpect(jsonPath("$.patchChange.options.wipeGroups.length()").value(8))
        .andExpect(jsonPath("$.patchChange.options.wipeGroups[0].group").value("CONSUMABLES"))
        .andExpect(jsonPath("$.patchChange.options.wipeGroups[0].selected").value(false))
        .andExpect(
            jsonPath("$.patchChange.options.wipeGroups[0].types[?(@.value == 'Food')].source")
                .value("ITEM_TYPE"))
        .andExpect(jsonPath("$.patchChange.options.wipeGroups[6].group").value("OTHER_ITEMS"))
        .andExpect(jsonPath("$.patchChange.options.wipeGroups[6].types.length()").value(0))
        .andExpect(
            jsonPath("$.patchChange.options.wipeExceptions[0].entry.bt").value(material.toString()))
        .andExpect(jsonPath("$.patchChange.options.wipeExceptions[0].target").value("MATERIAL"))
        .andExpect(jsonPath("$.patchChange.options.wipeExceptions[0].mode").value("KEEP"))
        .andExpect(jsonPath("$.patchChange.options.cancelRefineryOrders").value(false))
        .andExpect(jsonPath("$.patchChange.options.currencyWipe").value(false))
        .andExpect(jsonPath("$.patchChange.options.homeLocationPrompt").value(true))
        .andExpect(jsonPath("$.homeLocation.state").value("PENDING"))
        .andExpect(jsonPath("$.homeLocation.patchChange.id").value(recorded.id().toString()))
        .andExpect(jsonPath("$.homeLocation.liveAt").value("2000-01-01T00:00:00Z"))
        .andExpect(jsonPath("$.homeLocation.chosen").doesNotExist())
        .andExpect(jsonPath("$.homeLocation.preview.length()").value(1))
        .andExpect(jsonPath("$.homeLocation.preview[0].location.name").value(nameOf(port)))
        .andExpect(jsonPath("$.homeLocation.preview[0].rows").value(2))
        .andExpect(jsonPath("$.homeLocation.preview[0].ships").value(0))
        .andExpect(
            jsonPath("$.homeLocation.preview[0].pools[0].orgUnit.id").value(squadron.toString()))
        .andExpect(jsonPath("$.homeLocation.preview[0].pools[0].rows").value(1));
  }

  @Test
  void theChoiceRelocatesTheMemberOnceAsTheClient() throws Exception {
    UUID own = row(port, null);
    PatchChangeDto recorded = trigger();

    choose(home)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.location.id").value(home.toString()))
        .andExpect(jsonPath("$.location.system").value("Stanton"))
        .andExpect(jsonPath("$.rows").value(1))
        .andExpect(jsonPath("$.ships").value(0));

    assertThat(
            jdbc.queryForObject(
                "SELECT location_id FROM inventory_item WHERE id = ?", UUID.class, own))
        .isEqualTo(home);
    assertThat(
            jdbc.queryForObject(
                "SELECT chosen_by FROM patch_change_relocation WHERE user_id = ?",
                String.class,
                member))
        .isEqualTo("CLIENT");
    assertThat(
            jdbc.queryForObject(
                "SELECT details FROM audit_event WHERE event_type = 'PATCH_CHANGE_RELOCATED'"
                    + " AND subject_id = ?",
                String.class,
                member))
        .contains("chosenBy=CLIENT")
        .contains("client=" + client)
        .contains("patchChange=" + recorded.id());
    choose(home)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("HOME_LOCATION_ALREADY_CHOSEN"));
    mockMvc
        .perform(relayed(get(PATH), READ))
        .andExpect(jsonPath("$.homeLocation.state").value("RELOCATED"))
        .andExpect(jsonPath("$.homeLocation.chosen.id").value(home.toString()))
        .andExpect(jsonPath("$.homeLocation.chosenAt").exists())
        .andExpect(jsonPath("$.homeLocation.preview.length()").value(0));
  }

  @Test
  void aChoiceWithoutAnOpenPhaseOrOfALocationNotOnOfferIsRefused() throws Exception {
    choose(home)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("HOME_LOCATION_NOT_ASKED"));

    trigger();

    choose(port)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("HOME_LOCATION_NOT_OFFERED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM patch_change_relocation WHERE user_id = ?",
                Long.class,
                member))
        .isZero();
  }

  @Test
  void theReadAndTheChoiceNeedTheirOwnScopes() throws Exception {
    mockMvc
        .perform(relayed(get(PATH), "exchange.stock.write"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
    mockMvc
        .perform(
            relayed(post(CHOICE), READ)
                .contentType(MediaType.APPLICATION_JSON)
                .content(choice(home)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  @Test
  void aPushAgainstALotThePatchChangeMovedIsRemovedElsewhereWithThePatchChange() throws Exception {
    row(port, null);
    PatchChangeDto recorded = trigger();
    choose(home).andExpect(status().isOk());

    mockMvc
        .perform(
            relayed(post("/api/v1/exchange/me/stock/changes"), "exchange.stock.write")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"ops":[{"op":"set-quantity","material":{"bt":"%s"},
                             "location":{"name":"%s"},"quality":500,"stolen":false,
                             "quantity":{"amount":2,"unit":"SCU"},
                             "expectedQuantity":{"amount":1,"unit":"SCU"}}]}
                    """
                        .formatted(material, nameOf(port))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(0))
        .andExpect(jsonPath("$.results[0].result").value("rejected"))
        .andExpect(jsonPath("$.results[0].reason").value("REMOVED_ELSEWHERE"))
        .andExpect(jsonPath("$.results[0].patchChange.id").value(recorded.id().toString()))
        .andExpect(jsonPath("$.results[0].patchChange.label").value("0.1"));
  }

  @Test
  void anUpsertOfAShipAPatchChangeRemovedIsRemovedElsewhereWithThePatchChange() throws Exception {
    UUID ship = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ship (id, version, name, ship_type_id, owner_id, insurance, created_at)"
            + " VALUES (?, 0, 'Wiped', ?, ?, 'LTI', ?)",
        ship,
        shipType,
        member,
        Timestamp.from(BEFORE));
    PatchChangeDto recorded = trigger();
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    PatchChangeWrites.runFor(
        recorded.id(),
        () -> transaction.execute(_ -> jdbc.update("DELETE FROM ship WHERE id = ?", ship)));

    mockMvc
        .perform(
            relayed(post("/api/v1/exchange/me/ships/changes"), "exchange.hangar.write")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"ops":[{"op":"upsert","externalId":"vk-1","shipId":"%s","version":0,
                             "shipType":{"bt":"%s"},"name":"Wiped",
                             "insurance":{"kind":"LTI"}}]}
                    """
                        .formatted(ship, shipType)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].reason").value("REMOVED_ELSEWHERE"))
        .andExpect(jsonPath("$.results[0].patchChange.id").value(recorded.id().toString()));
  }

  /**
   * Triggers a rebuild that wipes nothing and asks for a home location, with one keeping exception.
   *
   * @return the recorded patch change
   */
  private @NotNull PatchChangeDto trigger() {
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
              new PatchChangeOptions(
                  Set.of(),
                  Set.of(
                      new WipeException(
                          WipeExceptionTarget.MATERIAL, material, WipeExceptionMode.KEEP)),
                  false,
                  false,
                  true),
              "0.1"));
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  /**
   * Posts the member's choice with both patch scopes.
   *
   * @param location the chosen location
   * @return the result
   * @throws Exception if the request fails
   */
  private @NotNull ResultActions choose(@NotNull UUID location) throws Exception {
    return mockMvc.perform(
        relayed(post(CHOICE), BOTH)
            .contentType(MediaType.APPLICATION_JSON)
            .content(choice(location)));
  }

  /**
   * Renders a choice body.
   *
   * @param location the chosen location
   * @return the body
   */
  private static @NotNull String choice(@NotNull UUID location) {
    return "{\"location\":\"" + location + "\"}";
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

  /**
   * Persists an active member.
   *
   * @param prefix the username's prefix
   * @param createdAt the account's creation time, or {@code null} for now
   * @return the member's id
   */
  private @NotNull UUID persistUser(@NotNull String prefix, @Nullable Instant createdAt) {
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

  /**
   * Reads a location's name.
   *
   * @param location the location
   * @return its name
   */
  private @NotNull String nameOf(@NotNull UUID location) {
    return jdbc.queryForObject("SELECT name FROM location WHERE id = ?", String.class, location);
  }

  /**
   * Inserts one of the member's stock rows of one SCU from before the live time.
   *
   * @param location the location
   * @param pool the org unit it is held for, or {@code null} for personal stock
   * @return its id
   */
  private @NotNull UUID row(@NotNull UUID location, @Nullable UUID pool) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO inventory_item (id, version, user_id, material_id, location_id, quality,
                                    amount, personal, stolen, owning_org_unit_id, created_at)
        VALUES (?, 0, ?, ?, ?, 500, 1, ?, false, ?, ?)
        """,
        id,
        member,
        material,
        location,
        pool == null,
        pool,
        Timestamp.from(BEFORE));
    return id;
  }
}
