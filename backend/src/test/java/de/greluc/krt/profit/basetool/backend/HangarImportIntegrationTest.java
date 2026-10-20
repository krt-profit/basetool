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

package de.greluc.krt.profit.basetool.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipProductionStatus;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.hangar.internal.FleetviewImportResponseDto;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Transactional
class HangarImportIntegrationTest {

  @Autowired private SquadronRepository squadronRepository;

  private Squadron iridium;

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private ShipRepository shipRepository;

  @Autowired private ShipTypeRepository shipTypeRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  private User user1;
  private ShipType type135c;
  private ShipType typeZeus;

  @BeforeEach
  void setUp() {
    iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    user1 = new User();
    user1.setId(UUID.randomUUID());
    user1.setUsername("importuser");
    userRepository.save(user1);
    OrgUnitMembership iridiumMembership = new OrgUnitMembership();
    iridiumMembership.setId(new OrgUnitMembershipId(user1.getId(), Squadron.IRIDIUM_ID));
    iridiumMembership.setUser(user1);
    iridiumMembership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(iridiumMembership);

    type135c = new ShipType();
    type135c.setName("135c");
    type135c = shipTypeRepository.save(type135c);

    typeZeus = new ShipType();
    typeZeus.setName("zeus mk ii mr");
    typeZeus = shipTypeRepository.save(typeZeus);
  }

  @Test
  void importFleetview_success_importsMatchedShips() throws Exception {
    String json =
        """
        [
          {"name":"135c","shipname":"","type":"ship"},
          {"name":"zeus mk ii mr","shipname":"My Zeus","type":"ship"},
          {"name":"unknown xz99","shipname":"","type":"ship"}
        ]
        """;
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "fleetview.json", "application/json", json.getBytes(StandardCharsets.UTF_8));

    String response =
        mockMvc
            .perform(
                multipart("/api/v1/hangar/import/ships")
                    .file(file)
                    .with(jwt().jwt(builder -> builder.subject(user1.getId().toString()))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    FleetviewImportResponseDto result =
        objectMapper.readValue(response, FleetviewImportResponseDto.class);
    assertEquals(2, result.importedCount());
    assertEquals(1, result.skippedCount());
    assertEquals(0, result.duplicateCount());
    assertTrue(result.skippedShips().contains("unknown xz99"));

    assertEquals(2, shipRepository.findByOwnerId(user1.getId()).size());
  }

  @Test
  void importFleetview_memberOfSeveralUnitsWithoutPin_importsShipsWithoutUnit() throws Exception {
    Squadron second = new Squadron();
    second.setName("Import Second Staffel");
    second.setShorthand("S" + UUID.randomUUID().toString().substring(0, 8));
    second = squadronRepository.save(second);
    OrgUnitMembership secondMembership = new OrgUnitMembership();
    secondMembership.setId(new OrgUnitMembershipId(user1.getId(), second.getId()));
    secondMembership.setUser(user1);
    secondMembership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(secondMembership);

    String json =
        """
        [{"name":"135c","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "fleetview.json", "application/json", json.getBytes(StandardCharsets.UTF_8));

    mockMvc
        .perform(
            multipart("/api/v1/hangar/import/ships")
                .file(file)
                .with(jwt().jwt(builder -> builder.subject(user1.getId().toString()))))
        .andExpect(status().isOk());

    Ship imported = shipRepository.findByOwnerId(user1.getId()).stream().findFirst().orElseThrow();
    assertNull(imported.getOwningOrgUnit());
  }

  @Test
  void importFleetview_unauthenticated_returns401() throws Exception {
    String json =
        """
        [{"name":"135c","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "fleetview.json", "application/json", json.getBytes(StandardCharsets.UTF_8));

    mockMvc
        .perform(multipart("/api/v1/hangar/import/ships").file(file))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void importFleetview_emptyFile_returns400() throws Exception {
    MockMultipartFile emptyFile =
        new MockMultipartFile("file", "fleetview.json", "application/json", new byte[0]);

    mockMvc
        .perform(
            multipart("/api/v1/hangar/import/ships")
                .file(emptyFile)
                .with(jwt().jwt(builder -> builder.subject(user1.getId().toString()))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void importFleetview_invalidJson_returns400() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "fleetview.json",
            "application/json",
            "NOT JSON".getBytes(StandardCharsets.UTF_8));

    mockMvc
        .perform(
            multipart("/api/v1/hangar/import/ships")
                .file(file)
                .with(jwt().jwt(builder -> builder.subject(user1.getId().toString()))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void importFleetview_reImport_noNewShipCreatedWhenAlreadyPresent() throws Exception {
    Ship existing = new Ship();
    existing.setOwningOrgUnit(iridium);
    existing.setShipType(type135c);
    existing.setOwner(user1);
    existing.setInsurance("LTI");
    shipRepository.save(existing);

    String json =
        """
        [{"name":"135c","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "fleetview.json", "application/json", json.getBytes(StandardCharsets.UTF_8));

    String response =
        mockMvc
            .perform(
                multipart("/api/v1/hangar/import/ships")
                    .file(file)
                    .with(jwt().jwt(builder -> builder.subject(user1.getId().toString()))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    FleetviewImportResponseDto result =
        objectMapper.readValue(response, FleetviewImportResponseDto.class);
    assertEquals(0, result.importedCount());
    assertEquals(1, result.duplicateCount());

    assertEquals(1, shipRepository.findByOwnerId(user1.getId()).size());
  }

  @Test
  void importFleetview_partialDuplicate_createsOnlyMissingShips() throws Exception {
    Ship existing = new Ship();
    existing.setOwningOrgUnit(iridium);
    existing.setShipType(type135c);
    existing.setOwner(user1);
    existing.setInsurance("LTI");
    shipRepository.save(existing);

    String json =
        """
        [
          {"name":"135c","shipname":"","type":"ship"},
          {"name":"135c","shipname":"","type":"ship"}
        ]
        """;
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "fleetview.json", "application/json", json.getBytes(StandardCharsets.UTF_8));

    String response =
        mockMvc
            .perform(
                multipart("/api/v1/hangar/import/ships")
                    .file(file)
                    .with(jwt().jwt(builder -> builder.subject(user1.getId().toString()))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    FleetviewImportResponseDto result =
        objectMapper.readValue(response, FleetviewImportResponseDto.class);
    assertEquals(1, result.importedCount());
    assertEquals(1, result.duplicateCount());

    assertEquals(2, shipRepository.findByOwnerId(user1.getId()).size());
  }

  @Test
  void importFleetview_caseInsensitiveMatch_importsShip() throws Exception {
    String json =
        """
        [{"name":"135C","shipname":"","type":"ship"}]
        """;
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "fleetview.json", "application/json", json.getBytes(StandardCharsets.UTF_8));

    String response =
        mockMvc
            .perform(
                multipart("/api/v1/hangar/import/ships")
                    .file(file)
                    .with(jwt().jwt(builder -> builder.subject(user1.getId().toString()))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    FleetviewImportResponseDto result =
        objectMapper.readValue(response, FleetviewImportResponseDto.class);
    assertEquals(1, result.importedCount());
    assertEquals(0, result.skippedCount());
  }

  @Test
  void importFleetview_setsIndividualShipName() throws Exception {
    String json =
        """
        [{"name":"zeus mk ii mr","shipname":"Stella Aeterna","type":"ship"}]
        """;
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "fleetview.json", "application/json", json.getBytes(StandardCharsets.UTF_8));

    mockMvc
        .perform(
            multipart("/api/v1/hangar/import/ships")
                .file(file)
                .with(jwt().jwt(builder -> builder.subject(user1.getId().toString()))))
        .andExpect(status().isOk());

    Ship imported = shipRepository.findByOwnerId(user1.getId()).stream().findFirst().orElseThrow();
    assertEquals("Stella Aeterna", imported.getName());
  }

  @Test
  void importShips_starjumpFleetviewer_nameAndSlugFallback() throws Exception {
    typeZeus.setUexSlug("zeus-mkii-mr");
    typeZeus = shipTypeRepository.save(typeZeus);

    String json =
        """
        {
          "type": "starjumpFleetviewer",
          "version": 1,
          "canvasItems": [
            { "id":"1", "itemType":"SHIP", "shipSlug":"135c", "variantSlug":"",
              "defaultText":"135c" },
            { "id":"2", "itemType":"TEXTGROUP", "text":"135c" },
            { "id":"3", "itemType":"SHIP", "shipSlug":"zeus-mkii-mr", "variantSlug":"",
              "defaultText":"No Name The Matcher Knows" }
          ]
        }
        """;
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "STARJUMP_FleetViewer.json",
            "application/json",
            json.getBytes(StandardCharsets.UTF_8));

    String response =
        mockMvc
            .perform(
                multipart("/api/v1/hangar/import/ships")
                    .file(file)
                    .with(jwt().jwt(builder -> builder.subject(user1.getId().toString()))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    FleetviewImportResponseDto result =
        objectMapper.readValue(response, FleetviewImportResponseDto.class);
    assertEquals(2, result.importedCount());
    assertEquals(0, result.skippedCount());
    assertEquals(2, shipRepository.findByOwnerId(user1.getId()).size());
  }

  @Test
  void importShips_fleetyards_nameMatchAndCustomName() throws Exception {
    String json =
        """
        [
          {
            "name":"135c", "slug":"orig-135c", "shipCode":"orig_135c",
            "manufacturerName":"Origin Jumpworks", "manufacturerCode":"ORIG",
            "shipName":"My Little Ship", "wanted":false, "flagship":false,
            "public":true, "nameVisible":true, "saleNotify":false,
            "groups":[], "modules":[], "upgrades":[]
          },
          {
            "name":"unknown xz99", "slug":"alien-xz99", "shipCode":"alien_xz99",
            "manufacturerCode":"ALN", "groups":[], "modules":[], "upgrades":[]
          }
        ]
        """;
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "fleetyards-hangar.json",
            "application/json",
            json.getBytes(StandardCharsets.UTF_8));

    String response =
        mockMvc
            .perform(
                multipart("/api/v1/hangar/import/ships")
                    .file(file)
                    .with(jwt().jwt(builder -> builder.subject(user1.getId().toString()))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    FleetviewImportResponseDto result =
        objectMapper.readValue(response, FleetviewImportResponseDto.class);
    assertEquals(1, result.importedCount());
    assertEquals(1, result.skippedCount());
    assertTrue(result.skippedShips().contains("unknown xz99"));

    Ship imported = shipRepository.findByOwnerId(user1.getId()).stream().findFirst().orElseThrow();
    assertEquals("My Little Ship", imported.getName());
  }

  @Test
  void importHangarExtension_jsonThenCsv_fillsTheSameShipsWithoutDuplicates() throws Exception {
    String json =
        """
        [
          {"manufacturer":{"code":"ORIG","name":"Origin Jumpworks"},"code":"ORIG_135c",
           "matrix":"135c","status":"Flight-Ready","insurance":"LTI","name":"135c"},
          {"manufacturer":{"code":"RSI","name":"Roberts Space Industries"},"code":"RSI_Zeus",
           "matrix":"Zeus Mk II MR","status":"In-Concept","insurance":"6MI","name":"Zeus Mk II MR"}
        ]
        """;
    FleetviewImportResponseDto first = upload("my-fleet.json", "application/json", json);
    assertEquals(2, first.importedCount());

    UUID zeusId =
        shipRepository.findByOwnerId(user1.getId()).stream()
            .filter(s -> s.getShipType().getId().equals(typeZeus.getId()))
            .findFirst()
            .orElseThrow()
            .getId();
    Long versionBefore = shipRepository.findById(zeusId).orElseThrow().getVersion();

    String csv =
        """
        Pledge ID,Pledge Date,Pledge Cost,Meltable,Giftable,Pledge Name,Pledge Content,Content Type
        "500","October 14, 2026","1,275.00 USD","yes","no","Zeus Pack","RSI Zeus Mk II MR","Ship"
        "500","October 14, 2026","1,275.00 USD","yes","no","Zeus Pack","6 Month Insurance","Insurance"
        "500","October 14, 2026","1,275.00 USD","yes","no","Zeus Pack","Zeus - Paint","Skin"
        "501","May 1, 2015","45.00 USD","no","no","135c","Origin 135c","Ship"
        """;
    FleetviewImportResponseDto second = upload("my-hangar.csv", "text/csv", csv);
    assertEquals(0, second.importedCount());
    assertEquals(2, second.duplicateCount());
    assertEquals(2, second.updatedCount());

    FleetviewImportResponseDto third = upload("my-hangar.csv", "text/csv", csv);
    assertEquals(0, third.importedCount());
    assertEquals(0, third.updatedCount());

    shipRepository.flush();
    assertEquals(2, shipRepository.findByOwnerId(user1.getId()).size());
    Ship zeus = shipRepository.findById(zeusId).orElseThrow();
    assertEquals(ShipProductionStatus.IN_CONCEPT, zeus.getProductionStatus());
    assertEquals("6", zeus.getInsurance());
    assertEquals("500", zeus.getPledgeId());
    assertEquals(LocalDate.of(2026, 10, 14), zeus.getPledgeDate());
    assertEquals(0, new BigDecimal("1275.00").compareTo(zeus.getPledgeCost()));
    assertEquals("USD", zeus.getPledgeCostCurrency());
    assertEquals(Boolean.TRUE, zeus.getMeltable());
    assertEquals(Boolean.FALSE, zeus.getGiftable());
    assertEquals(versionBefore, zeus.getVersion());
  }

  private FleetviewImportResponseDto upload(String name, String type, String body)
      throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", name, type, body.getBytes(StandardCharsets.UTF_8));
    String response =
        mockMvc
            .perform(
                multipart("/api/v1/hangar/import/ships")
                    .file(file)
                    .with(jwt().jwt(builder -> builder.subject(user1.getId().toString()))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readValue(response, FleetviewImportResponseDto.class);
  }
}
