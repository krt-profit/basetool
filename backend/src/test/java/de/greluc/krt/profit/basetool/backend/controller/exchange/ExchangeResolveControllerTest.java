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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.ActingMemberHeader;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "app.security.ingest-gateway.client-ids=test-ingest-gateway")
class ExchangeResolveControllerTest {

  private static final String PATH = "/api/v1/exchange/catalog/resolve";
  private static final UUID MEMBER = UUID.fromString("44444444-4444-4444-4444-4444444440a2");
  private static final String GATEWAY = "55555555-5555-5555-5555-555555555555";
  private static final UUID RIFLE_ITEM_GUID =
      UUID.fromString("7a000001-0000-4000-8000-000000000001");
  private static final UUID WIDGET_GUID = UUID.fromString("7a000001-0000-4000-8000-000000000002");
  private static final int RIFLE_UEX_ID = 990_001;
  private static final int WIDGET_UEX_ID = 990_002;
  private static final int RESOLVIUM_COMMODITY = 990_003;
  private static final int HAULER_UEX_ID = 990_004;

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeClientRepository clientRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;

  private MockMvc mockMvc;
  private GameItem widget;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();

    User member = new User();
    member.setId(MEMBER);
    member.setUsername("exchange-resolver");
    member.setApprovalStatus(ApprovalStatus.ACTIVE);
    member.setInKeycloak(true);
    member.setRoles(
        new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    userRepository.saveAndFlush(member);

    ExchangeClient client = new ExchangeClient();
    client.setClientId("versekit-resolve");
    client.setDisplayName("VerseKit");
    client.setStatus(ExchangeClientStatus.ACTIVE);
    client.setCapabilities(
        EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.BLUEPRINTS_READ));
    clientRepository.saveAndFlush(client);
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(true);
    settingsRepository.saveAndFlush(settings);

    GameItem rifle = item("Xch Resolve Rifle", null);
    rifle.setExternalUuid(RIFLE_ITEM_GUID);
    rifle.setUexItemId(RIFLE_UEX_ID);
    rifle.setNameKey("item_NameXchResolveRifle");
    gameItemRepository.saveAndFlush(rifle);
    blueprint("BP_XCH_RESOLVE_RIFLE", "Xch Resolve Rifle", rifle);
    blueprint("BP_XCH_SHARED", "Xch Resolve Rifle", null);
    blueprint("BP_XCH_SHARED", "Xch Resolve Pistol", null);

    widget = item("Xch Resolve Widget", "XCH_Widget_A");
    widget.setP4kUuid(WIDGET_GUID);
    widget.setUexItemId(WIDGET_UEX_ID);
    widget.setNameKey("item_NameXchWidgetA");
    gameItemRepository.saveAndFlush(widget);
    gameItemRepository.saveAndFlush(item("Xch Resolve Twin", null));
    gameItemRepository.saveAndFlush(item("Xch Resolve Twin", null));

    Material resolvium = material("Xch Resolvium", true);
    resolvium.setIdCommodity(RESOLVIUM_COMMODITY);
    resolvium.setScwikiKey("xch_resolvium");
    materialRepository.saveAndFlush(resolvium);
    materialRepository.saveAndFlush(material("Xch Concealium", false));

    ShipType hauler = new ShipType();
    hauler.setName("Xch Resolve Hauler");
    hauler.setClassName("XCH_Hauler");
    hauler.setUexVehicleId(HAULER_UEX_ID);
    shipTypeRepository.saveAndFlush(hauler);
  }

  @Test
  void blueprintsResolveByEveryFieldAndWarnAboutNameKeys() throws Exception {
    String body =
        """
        {"kind":"BLUEPRINT","refs":[
          {"bt":"xch resolve rifle"},
          {"scRecord":"bp_xch_shared"},
          {"scGuid":"%s"},
          {"uexId":%d},
          {"name":"Xch Resolve Rifle"},
          {"locKey":"item_NameXchPistol","name":"Xch Resolve Pistol"},
          {"bt":"no such product","scRecord":"BP_XCH_RESOLVE_RIFLE"},
          {"name":"Qqqq Zzzz Wwww"},
          {"locKey":"ITEM_NAMEXCHRESOLVERIFLE"}
        ]}
        """
            .formatted(RIFLE_ITEM_GUID, RIFLE_UEX_ID);

    mockMvc
        .perform(relayed(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results.length()").value(9))
        .andExpect(jsonPath("$.results[0].index").value(0))
        .andExpect(jsonPath("$.results[0].status").value("resolved"))
        .andExpect(jsonPath("$.results[0].ref.bt").value("xch resolve rifle"))
        .andExpect(jsonPath("$.results[0].ref.name").value("Xch Resolve Rifle"))
        .andExpect(jsonPath("$.results[1].status").value("ambiguous"))
        .andExpect(jsonPath("$.results[1].candidates.length()").value(2))
        .andExpect(jsonPath("$.results[1].ref").doesNotExist())
        .andExpect(jsonPath("$.results[2].ref.bt").value("xch resolve rifle"))
        .andExpect(jsonPath("$.results[3].ref.bt").value("xch resolve rifle"))
        .andExpect(jsonPath("$.results[4].ref.bt").value("xch resolve rifle"))
        .andExpect(jsonPath("$.results[5].ref.bt").value("xch resolve pistol"))
        .andExpect(jsonPath("$.results[6].ref.bt").value("xch resolve rifle"))
        .andExpect(jsonPath("$.results[7].status").value("unmatched"))
        .andExpect(jsonPath("$.results[7].candidates").doesNotExist())
        .andExpect(jsonPath("$.results[8].ref.bt").value("xch resolve rifle"))
        .andExpect(jsonPath("$.warnings.length()").value(1))
        .andExpect(jsonPath("$.warnings[0].pointer").value("/refs/5/locKey"))
        .andExpect(jsonPath("$.warnings[0].code").value("LOC_KEY_UNRESOLVED"));
  }

  @Test
  void itemsResolveByIdClassGuidUexIdAndExactName() throws Exception {
    String body =
        """
        {"kind":"ITEM","refs":[
          {"bt":"%s"},
          {"scRecord":"xch_widget_a"},
          {"scGuid":"%s"},
          {"uexId":%d},
          {"name":"XCH RESOLVE WIDGET"},
          {"name":"Xch Resolve Twin"},
          {"bt":"not-a-uuid"},
          {"locKey":"item_NameXchWidgetA"}
        ]}
        """
            .formatted(widget.getId(), WIDGET_GUID, WIDGET_UEX_ID);

    mockMvc
        .perform(relayed(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].ref.bt").value(widget.getId().toString()))
        .andExpect(jsonPath("$.results[1].ref.bt").value(widget.getId().toString()))
        .andExpect(jsonPath("$.results[2].ref.bt").value(widget.getId().toString()))
        .andExpect(jsonPath("$.results[3].ref.bt").value(widget.getId().toString()))
        .andExpect(jsonPath("$.results[4].ref.name").value("Xch Resolve Widget"))
        .andExpect(jsonPath("$.results[5].status").value("ambiguous"))
        .andExpect(jsonPath("$.results[5].candidates.length()").value(2))
        .andExpect(jsonPath("$.results[6].status").value("unmatched"))
        .andExpect(jsonPath("$.results[7].ref.bt").value(widget.getId().toString()))
        .andExpect(jsonPath("$.warnings").doesNotExist());
  }

  @Test
  void materialsResolveOnlyAmongTheVisibleOnes() throws Exception {
    String body =
        """
        {"kind":"MATERIAL","refs":[
          {"name":"xch resolvium"},
          {"uexId":%d},
          {"scRecord":"XCH_RESOLVIUM"},
          {"name":"Xch Concealium"}
        ]}
        """
            .formatted(RESOLVIUM_COMMODITY);

    mockMvc
        .perform(relayed(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].ref.name").value("Xch Resolvium"))
        .andExpect(jsonPath("$.results[1].ref.name").value("Xch Resolvium"))
        .andExpect(jsonPath("$.results[2].ref.name").value("Xch Resolvium"))
        .andExpect(jsonPath("$.results[3].ref").doesNotExist())
        .andExpect(jsonPath("$..[?(@.name == 'Xch Concealium')]").isEmpty());
  }

  @Test
  void shipTypesResolveByClassUexIdAndName() throws Exception {
    String body =
        """
        {"kind":"SHIP_TYPE","refs":[
          {"scRecord":"xch_hauler"},
          {"uexId":%d},
          {"name":"Xch Resolve Hauler"}
        ]}
        """
            .formatted(HAULER_UEX_ID);

    mockMvc
        .perform(relayed(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results[0].ref.name").value("Xch Resolve Hauler"))
        .andExpect(jsonPath("$.results[1].ref.name").value("Xch Resolve Hauler"))
        .andExpect(jsonPath("$.results[2].ref.name").value("Xch Resolve Hauler"));
  }

  @Test
  void moreThanFiveHundredReferencesAreRefused() throws Exception {
    StringBuilder refs = new StringBuilder();
    for (int i = 0; i < 501; i++) {
      refs.append(i == 0 ? "" : ",").append("{\"name\":\"n").append(i).append("\"}");
    }

    mockMvc
        .perform(relayed("{\"kind\":\"ITEM\",\"refs\":[" + refs + "]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aRequestWithoutAKindIsRefused() throws Exception {
    mockMvc.perform(relayed("{\"refs\":[{\"name\":\"x\"}]}")).andExpect(status().isBadRequest());
  }

  @Test
  void aBrowserSessionCannotResolve() throws Exception {
    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"ITEM\",\"refs\":[{\"name\":\"x\"}]}")
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token.subject(MEMBER.toString()).claim("azp", "basetool-frontend"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isForbidden());
  }

  /**
   * Builds a relayed resolve request for the member.
   *
   * @param body the JSON body
   * @return the request
   */
  private static @NotNull MockHttpServletRequestBuilder relayed(@NotNull String body) {
    return post(PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .with(gateway())
        .header(ActingMemberHeader.ON_BEHALF_OF_HEADER, MEMBER.toString())
        .header(ActingMemberHeader.EXCHANGE_CLIENT_HEADER, "versekit-resolve")
        .header(ActingMemberHeader.EXCHANGE_CAPABILITIES_HEADER, "exchange.blueprints.read")
        .header(ActingMemberHeader.EXCHANGE_INSTALLATION_HEADER, "k".repeat(43));
  }

  /**
   * Authenticates as the ingest gateway.
   *
   * @return the JWT post-processor
   */
  private static @NotNull JwtRequestPostProcessor gateway() {
    return jwt().jwt(token -> token.subject(GATEWAY).claim("azp", "test-ingest-gateway"));
  }

  /**
   * Builds an unsaved item.
   *
   * @param name the name
   * @param className the class name, or {@code null}
   * @return the item
   */
  private static @NotNull GameItem item(@NotNull String name, String className) {
    GameItem item = new GameItem();
    item.setName(name);
    item.setClassName(className);
    return item;
  }

  /**
   * Saves an active blueprint.
   *
   * @param key the Wiki key
   * @param outputName the output name
   * @param outputItem the output item, or {@code null}
   */
  private void blueprint(@NotNull String key, @NotNull String outputName, GameItem outputItem) {
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    blueprint.setScwikiKey(key);
    blueprint.setOutputName(outputName);
    blueprint.setOutputItem(outputItem);
    blueprint.setIsAvailableByDefault(false);
    blueprintRepository.saveAndFlush(blueprint);
  }

  /**
   * Builds an unsaved raw material.
   *
   * @param name the name
   * @param visible whether it is visible
   * @return the material
   */
  private static @NotNull Material material(@NotNull String name, boolean visible) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.RAW);
    material.setIsVisible(visible);
    return material;
  }
}
