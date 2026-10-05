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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.exchange.api.ActingMemberHeader;
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
import de.greluc.krt.profit.basetool.backend.service.TermsAcceptanceService;
import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration tests for the exchange drafts: the gateway relays a client's upload, the backend
 * previews it as the web import would, and nothing is written (REQ-XCH-019).
 */
@SpringBootTest
class ExchangeDraftControllerTest {

  private static final String BLUEPRINTS = "/api/v1/exchange/me/drafts/blueprints";
  private static final String REFINERY = "/api/v1/exchange/me/drafts/refinery-orders";
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final String KEY = "Kx9_" + "d".repeat(39);

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private BlueprintNameNormalizer normalizer;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TermsAcceptanceService termsAcceptanceService;

  private MockMvc mockMvc;
  private UUID member;
  private String client;
  private boolean wasEnabled;
  private final List<UUID> blueprints = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("draft-member-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    member = userRepository.saveAndFlush(user).getId();
    client = "sx-" + UUID.randomUUID().toString().substring(0, 8);
    ExchangeClient registered = new ExchangeClient();
    registered.setClientId(client);
    registered.setDisplayName("SC Extractor");
    registered.setStatus(ExchangeClientStatus.ACTIVE);
    registered.setCapabilities(
        EnumSet.of(
            ExchangeCapability.CONNECT,
            ExchangeCapability.DRAFTS_BLUEPRINTS,
            ExchangeCapability.DRAFTS_REFINERY));
    clientRepository.saveAndFlush(registered);
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
    jdbc.update("DELETE FROM user_roles WHERE user_id = ?", member);
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
    blueprints.forEach(id -> jdbc.update("DELETE FROM blueprint WHERE id = ?", id));
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aBlueprintDraftIsPreviewedLikeAnUploadAndNothingIsWritten() throws Exception {
    String rifle = product("Arrowhead Rifle");
    String pistolName = productName("Arclight Pistol");
    owns(normalizer.normalize(pistolName));

    send(
            BLUEPRINTS,
            """
            {"format":"basetool.blueprints","formatVersion":"1.0",
             "generator":{"name":"SC Extractor","version":"2.0.0"},
             "items":[
               {"ref":{"bt":"%1$s"},"acquiredAt":"2026-09-20T10:00:00Z"},
               {"ref":{"bt":"%1$s"},"acquiredAt":"2026-09-10T10:00:00Z"},
               {"ref":{"name":"%2$s"}},
               {"ref":{"locKey":"item_Name_No_Such_Blueprint_%3$s"}}]}
            """
                .formatted(rifle, pistolName, UUID.randomUUID().toString().substring(0, 8)),
            "exchange.drafts.blueprints")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.matched").value(1))
        .andExpect(jsonPath("$.alreadyOwned").value(1))
        .andExpect(jsonPath("$.unmatched").value(1))
        .andExpect(jsonPath("$.entries[0].productKey").value(rifle))
        .andExpect(jsonPath("$.entries[0].suggestedAcquiredAt").value("2026-09-10T10:00:00Z"));

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM personal_blueprint WHERE owner_user_id = ?",
                Integer.class,
                member))
        .isEqualTo(1);
  }

  @Test
  void theWebImportReadsTheEnvelopeAndStillTheOldExport() throws Exception {
    termsAcceptanceService.acceptCurrentTerms(member);
    String rifle = product("Arrowhead Rifle");
    String pistolName = productName("Arclight Pistol");

    upload(
            """
            {"format":"basetool.blueprints","formatVersion":"1.0",
             "items":[{"ref":{"bt":"%s"},"acquiredAt":"2026-09-10T10:00:00Z"}]}
            """
                .formatted(rifle))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.matched").value(1))
        .andExpect(jsonPath("$.entries[0].productKey").value(rifle));

    upload("{\"blueprints\":[{\"productName\":\"%s\"}]}".formatted(pistolName))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(1));

    upload("{\"format\":\"basetool.blueprints\",\"items\":[{\"acquiredAt\":\"x\"}]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("basetool.blueprints")))
        .andExpect(jsonPath("$.detail").value(not(startsWith("error."))));

    upload("this is not json")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("JSON")))
        .andExpect(jsonPath("$.detail").value(not(startsWith("error."))));

    upload(
            """
            {"format":"basetool.blueprints","formatVersion":"1.1",
             "items":[{"ref":{"bt":"%s"}}]}
            """
                .formatted(rifle))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(1));

    upload(
            """
            {"format":"basetool.blueprints","formatVersion":"2.0",
             "items":[{"ref":{"bt":"%s"}}]}
            """
                .formatted(rifle))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.detail").value(containsString("1.x")));
  }

  @Test
  void aBlueprintDraftOfAnotherMajorFormatVersionIsRefused() throws Exception {
    send(
            BLUEPRINTS,
            "{\"format\":\"basetool.blueprints\",\"formatVersion\":\"2.0\",\"items\":[]}",
            "exchange.drafts.blueprints")
        .andExpect(status().isBadRequest());
    send(
            BLUEPRINTS,
            "{\"format\":\"basetool.blueprints\",\"formatVersion\":\"1.3\",\"items\":[]}",
            "exchange.drafts.blueprints")
        .andExpect(status().isOk());
  }

  @Test
  void aRefineryDraftReachesTheExtractorsImport() throws Exception {
    send(
            REFINERY,
            """
            {"schemaVersion":2,"orders":[{"panelType":"SETUP","quoted":true,"goods":[]}]}
            """,
            "exchange.drafts.refinery")
        .andExpect(status().isBadRequest());

    send(
            REFINERY,
            """
            {"schemaVersion":1,"orders":[{"panelType":"SETUP","quoted":true,
              "sourceImages":[{"name":"panel.png","width":1920,"height":1080}],"goods":[
              {"rawMaterialName":"NO SUCH ORE %s","quality":500,"inputQuantity":10,
               "outputQuantity":5,"refine":true}]}]}
            """
                .formatted(UUID.randomUUID()),
            "exchange.drafts.refinery")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.goodsTotal").value(1));
  }

  @Test
  void eachDraftNeedsItsOwnCapability() throws Exception {
    send(
            BLUEPRINTS,
            "{\"format\":\"basetool.blueprints\",\"formatVersion\":\"1.0\",\"items\":[]}",
            "exchange.drafts.refinery")
        .andExpect(status().isForbidden());
    send(
            REFINERY,
            """
            {"schemaVersion":1,"orders":[{"panelType":"SETUP",
              "sourceImages":[{"name":"panel.png","width":1920,"height":1080}],
              "goods":[{"rawMaterialName":"ORE","quality":500,"inputQuantity":10,
                         "outputQuantity":5,"refine":true}]}]}
            """,
            "exchange.drafts.blueprints")
        .andExpect(status().isForbidden());
  }

  /**
   * Posts a draft as the test client.
   *
   * @param path the route
   * @param json the body
   * @param capabilities the relayed capabilities
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions send(
      @NotNull String path, @NotNull String json, @NotNull String capabilities) throws Exception {
    return mockMvc.perform(
        post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .with(jwt().jwt(t -> t.subject(GATEWAY).claim("azp", "test-ingest-gateway")))
            .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, member.toString())
            .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, client)
            .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, capabilities)
            .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, KEY));
  }

  /**
   * Uploads a file to the web blueprint import's preview as the member's own browser session.
   *
   * @param json the file's content
   * @return the result
   * @throws Exception if the request fails
   */
  private ResultActions upload(@NotNull String json) throws Exception {
    return mockMvc.perform(
        multipart("/api/v1/personal-blueprints/import/preview")
            .file(
                new MockMultipartFile(
                    "file", "bp.json", "application/json", json.getBytes(StandardCharsets.UTF_8)))
            .with(jwt().jwt(t -> t.subject(member.toString()).claim("azp", "basetool-frontend"))));
  }

  /**
   * Seeds an active catalogue product with a unique name.
   *
   * @param name the name's stem
   * @return its product key
   */
  private @NotNull String product(@NotNull String name) {
    return normalizer.normalize(productName(name));
  }

  /**
   * Seeds an active catalogue product with a unique name.
   *
   * @param name the name's stem
   * @return its display name
   */
  private @NotNull String productName(@NotNull String name) {
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    blueprint.setScwikiKey("bp_" + UUID.randomUUID());
    blueprint.setOutputName(name + " " + UUID.randomUUID().toString().substring(0, 8));
    blueprint.setIsAvailableByDefault(false);
    Blueprint saved = blueprintRepository.saveAndFlush(blueprint);
    blueprints.add(saved.getId());
    return saved.getOutputName();
  }

  /**
   * Gives the member a blueprint directly, as the web would.
   *
   * @param productKey the product key
   */
  private void owns(@NotNull String productKey) {
    jdbc.update(
        """
        INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name)
        VALUES (?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        member,
        productKey,
        productKey);
  }
}
