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

package de.greluc.krt.profit.basetool.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.SpaceStation;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.FrequencyTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialCategoryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpaceStationRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.StarSystemRepository;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves for every type on the dual-use list of {@link MassAssignmentGuardTest} that its
 * server-managed components cannot be written through the endpoints that bind it (REQ-SEC-077).
 *
 * <p>Each test forges the {@code id} of another existing row, and where the type carries them the
 * owner, the owning Staffel, the status or a sync timestamp, then asserts that no forged value was
 * persisted and that the other row is untouched. Test names start with the decapitalised simple
 * name of the type they prove; the guard checks that one exists per listed type.
 */
@SpringBootTest
@Transactional
class DualUseRequestBodyProofTest {

  @Autowired private WebApplicationContext context;

  @Autowired private UserRepository userRepository;

  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  @Autowired private OrgUnitRepository orgUnitRepository;

  @Autowired private SquadronRepository squadronRepository;

  @Autowired private FrequencyTypeRepository frequencyTypeRepository;

  @Autowired private JobTypeRepository jobTypeRepository;

  @Autowired private LocationRepository locationRepository;

  @Autowired private MaterialCategoryRepository materialCategoryRepository;

  @Autowired private MaterialRepository materialRepository;

  @Autowired private RefiningMethodRepository refiningMethodRepository;

  @Autowired private StarSystemRepository starSystemRepository;

  @Autowired private TerminalRepository terminalRepository;

  @Autowired private SpaceStationRepository spaceStationRepository;

  @Autowired private RefineryOrderRepository refineryOrderRepository;

  private final JsonMapper json = JsonMapper.builder().build();

  private MockMvc mockMvc;

  private User admin;

  private User member;

  private User otherMember;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    admin = saveUser("dualUseAdmin");
    member = saveUser("dualUseMember");
    otherMember = saveUser("dualUseOther");
  }

  @Test
  void frequencyTypeDtoForgedIdNeitherOverwritesNorRetargetsAnotherRow() throws Exception {
    JsonNode victim =
        adminPost("/api/v1/frequency-types", body("name", "Victim FT", "sortIndex", 0));
    UUID victimId = id(victim);

    JsonNode created =
        adminPost(
            "/api/v1/frequency-types",
            body("id", victimId, "name", "Forged FT", "sortIndex", 0, "version", 7));
    UUID createdId = id(created);
    assertThat(createdId).isNotEqualTo(victimId);
    assertThat(created.get("version").asLong()).isZero();

    adminPut(
        "/api/v1/frequency-types/" + createdId,
        body("id", victimId, "name", "Renamed FT", "active", true, "sortIndex", 0));

    assertThat(frequencyTypeRepository.findById(victimId).orElseThrow().getName())
        .isEqualTo("Victim FT");
    assertThat(frequencyTypeRepository.findById(createdId).orElseThrow().getName())
        .isEqualTo("Renamed FT");
  }

  @Test
  void jobTypeDtoForgedIdNeitherOverwritesNorRetargetsAnotherRow() throws Exception {
    UUID victimId =
        id(
            adminPost(
                "/api/v1/job-types",
                body(
                    "name",
                    "Victim JT",
                    "archetype",
                    "CREW",
                    "active",
                    true,
                    "isLeadershipRole",
                    false,
                    "isMissionLead",
                    false)));

    JsonNode created =
        adminPost(
            "/api/v1/job-types",
            body(
                "id",
                victimId,
                "name",
                "Forged JT",
                "archetype",
                "CREW",
                "active",
                true,
                "isLeadershipRole",
                false,
                "isMissionLead",
                false,
                "version",
                7));
    UUID createdId = id(created);
    assertThat(createdId).isNotEqualTo(victimId);

    adminPut(
        "/api/v1/job-types/" + createdId,
        body(
            "id",
            victimId,
            "name",
            "Renamed JT",
            "archetype",
            "CREW",
            "active",
            true,
            "isLeadershipRole",
            false,
            "isMissionLead",
            false,
            "version",
            created.get("version").asLong()));

    assertThat(jobTypeRepository.findById(victimId).orElseThrow().getName()).isEqualTo("Victim JT");
    assertThat(jobTypeRepository.findById(createdId).orElseThrow().getName())
        .isEqualTo("Renamed JT");
  }

  @Test
  void locationDtoForgedIdNeitherOverwritesNorRetargetsAnotherRow() throws Exception {
    UUID victimId =
        id(
            adminPost(
                "/api/v1/locations",
                body("name", "Victim Loc", "hidden", false, "homeLocation", false)));

    JsonNode created =
        adminPost(
            "/api/v1/locations",
            body(
                "id",
                victimId,
                "name",
                "Forged Loc",
                "hidden",
                false,
                "homeLocation",
                false,
                "version",
                7));
    UUID createdId = id(created);
    assertThat(createdId).isNotEqualTo(victimId);

    adminPut(
        "/api/v1/locations/" + createdId,
        body(
            "id",
            victimId,
            "name",
            "Renamed Loc",
            "hidden",
            false,
            "homeLocation",
            false,
            "version",
            created.get("version").asLong()));

    assertThat(locationRepository.findById(victimId).orElseThrow().getName())
        .isEqualTo("Victim Loc");
    assertThat(locationRepository.findById(createdId).orElseThrow().getName())
        .isEqualTo("Renamed Loc");
  }

  @Test
  void materialCategoryDtoForgedIdNeitherOverwritesNorRetargetsAnotherRow() throws Exception {
    UUID victimId = id(adminPost("/api/v1/material-categories", body("name", "Victim Cat")));

    JsonNode created =
        adminPost(
            "/api/v1/material-categories",
            body("id", victimId, "name", "Forged Cat", "version", 7));
    UUID createdId = id(created);
    assertThat(createdId).isNotEqualTo(victimId);

    adminPut(
        "/api/v1/material-categories/" + createdId,
        body("id", victimId, "name", "Renamed Cat", "version", created.get("version").asLong()));

    assertThat(materialCategoryRepository.findById(victimId).orElseThrow().getName())
        .isEqualTo("Victim Cat");
    assertThat(materialCategoryRepository.findById(createdId).orElseThrow().getName())
        .isEqualTo("Renamed Cat");
  }

  @Test
  void materialDtoForgedIdDoesNotRetargetTheUpdate() throws Exception {
    UUID victimId =
        id(
            adminPost(
                "/api/v1/materials",
                body(
                    "name",
                    "Victim Ore",
                    "type",
                    "RAW",
                    "quantityType",
                    "SCU",
                    "isManualRawMaterial",
                    false,
                    "isJobOrder",
                    false)));
    JsonNode target =
        adminPost(
            "/api/v1/materials",
            body(
                "name",
                "Target Ore",
                "type",
                "RAW",
                "quantityType",
                "SCU",
                "isManualRawMaterial",
                false,
                "isJobOrder",
                false));
    UUID targetId = id(target);

    adminPut(
        "/api/v1/materials/" + targetId,
        body(
            "id",
            victimId,
            "name",
            "Renamed Ore",
            "type",
            "RAW",
            "quantityType",
            "SCU",
            "isManualRawMaterial",
            false,
            "isJobOrder",
            false,
            "version",
            target.get("version").asLong()));

    assertThat(materialRepository.findById(victimId).orElseThrow().getName())
        .isEqualTo("Victim Ore");
    assertThat(materialRepository.findById(targetId).orElseThrow().getName())
        .isEqualTo("Renamed Ore");
  }

  @Test
  void refiningMethodDtoForgedIdNeitherOverwritesNorRetargetsAnotherRow() throws Exception {
    UUID victimId = id(adminPost("/api/v1/refining-methods", body("name", "Victim RM")));

    UUID createdId =
        id(adminPost("/api/v1/refining-methods", body("id", victimId, "name", "Forged RM")));
    assertThat(createdId).isNotEqualTo(victimId);

    adminPut("/api/v1/refining-methods/" + createdId, body("id", victimId, "name", "Renamed RM"));

    assertThat(refiningMethodRepository.findById(victimId).orElseThrow().getName())
        .isEqualTo("Victim RM");
    assertThat(refiningMethodRepository.findById(createdId).orElseThrow().getName())
        .isEqualTo("Renamed RM");
  }

  @Test
  void starSystemDtoForgedIdNeitherOverwritesNorRetargetsAnotherRow() throws Exception {
    UUID victimId = id(adminPost("/api/v1/star-systems", body("name", "Victim System")));

    JsonNode created =
        adminPost(
            "/api/v1/star-systems", body("id", victimId, "name", "Forged System", "version", 7));
    UUID createdId = id(created);
    assertThat(createdId).isNotEqualTo(victimId);

    adminPut(
        "/api/v1/star-systems/" + createdId,
        body("id", victimId, "name", "Renamed System", "version", created.get("version").asLong()));

    assertThat(starSystemRepository.findById(victimId).orElseThrow().getName())
        .isEqualTo("Victim System");
    assertThat(starSystemRepository.findById(createdId).orElseThrow().getName())
        .isEqualTo("Renamed System");
  }

  @Test
  void squadronDtoForgedIdNeitherOverwritesNorRetargetsAnotherRow() throws Exception {
    String iridiumName = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow().getName();

    JsonNode created =
        adminPost(
            "/api/v1/squadrons",
            body(
                "id",
                Squadron.IRIDIUM_ID,
                "name",
                "Forged Staffel",
                "shorthand",
                "FST",
                "active",
                true,
                "isPromotionEnabled",
                false,
                "isProfitEligible",
                false,
                "version",
                7));
    UUID createdId = id(created);
    assertThat(createdId).isNotEqualTo(Squadron.IRIDIUM_ID);

    adminPut(
        "/api/v1/squadrons/" + createdId,
        body(
            "id",
            Squadron.IRIDIUM_ID,
            "name",
            "Renamed Staffel",
            "shorthand",
            "RST",
            "version",
            created.get("version").asLong()));

    assertThat(squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow().getName())
        .isEqualTo(iridiumName);
    assertThat(squadronRepository.findById(createdId).orElseThrow().getName())
        .isEqualTo("Renamed Staffel");
  }

  @Test
  void specialCommandDtoForgedIdNeitherOverwritesNorRetargetsAnotherRow() throws Exception {
    String iridiumName = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow().getName();

    JsonNode created =
        adminPost(
            "/api/v1/special-commands",
            body(
                "id",
                Squadron.IRIDIUM_ID,
                "name",
                "Forged SK",
                "shorthand",
                "FSK",
                "active",
                true,
                "isProfitEligible",
                false,
                "version",
                7));
    UUID createdId = id(created);
    assertThat(createdId).isNotEqualTo(Squadron.IRIDIUM_ID);

    adminPut(
        "/api/v1/special-commands/" + createdId,
        body(
            "id",
            Squadron.IRIDIUM_ID,
            "name",
            "Renamed SK",
            "shorthand",
            "RSK",
            "version",
            created.get("version").asLong()));

    assertThat(orgUnitRepository.findById(Squadron.IRIDIUM_ID).orElseThrow().getName())
        .isEqualTo(iridiumName);
    assertThat(orgUnitRepository.findById(createdId).orElseThrow().getName())
        .isEqualTo("Renamed SK");
  }

  @Test
  void bereichDtoForgedIdDoesNotOverwriteAnotherOrgUnit() throws Exception {
    String iridiumName = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow().getName();

    UUID createdId =
        id(
            adminPost(
                "/api/v1/org-units/bereiche",
                body(
                    "id",
                    Squadron.IRIDIUM_ID,
                    "name",
                    "Forged Bereich",
                    "shorthand",
                    "FBE",
                    "version",
                    7)));

    assertThat(createdId).isNotEqualTo(Squadron.IRIDIUM_ID);
    assertThat(orgUnitRepository.findById(Squadron.IRIDIUM_ID).orElseThrow().getName())
        .isEqualTo(iridiumName);
    assertThat(orgUnitRepository.findById(createdId).orElseThrow().getName())
        .isEqualTo("Forged Bereich");
  }

  @Test
  void organisationsleitungDtoForgedIdDoesNotOverwriteAnotherOrgUnit() throws Exception {
    String iridiumName = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow().getName();

    UUID createdId =
        id(
            adminPost(
                "/api/v1/org-units/organisationsleitung",
                body(
                    "id",
                    Squadron.IRIDIUM_ID,
                    "name",
                    "Forged OL",
                    "shorthand",
                    "FOL",
                    "version",
                    7)));

    assertThat(createdId).isNotEqualTo(Squadron.IRIDIUM_ID);
    assertThat(orgUnitRepository.findById(Squadron.IRIDIUM_ID).orElseThrow().getName())
        .isEqualTo(iridiumName);
    assertThat(orgUnitRepository.findById(createdId).orElseThrow().getName())
        .isEqualTo("Forged OL");
  }

  @Test
  void terminalDtoUpdateWritesOnlyTheHiddenFlag() throws Exception {
    Terminal victim = new Terminal();
    victim.setName("Victim Terminal");
    victim = terminalRepository.save(victim);
    Terminal target = new Terminal();
    target.setName("Target Terminal");
    target = terminalRepository.save(target);

    adminPut(
        "/api/v1/terminals/" + target.getId(),
        body(
            "id",
            victim.getId(),
            "name",
            "Forged Terminal",
            "hidden",
            true,
            "hasLoadingDockOverridden",
            true,
            "isAutoLoadOverridden",
            true,
            "uexHasLoadingDock",
            true,
            "uexSyncedAt",
            "2000-01-01T00:00:00Z"));

    Terminal reloaded = terminalRepository.findById(target.getId()).orElseThrow();
    assertThat(reloaded.getHidden()).isTrue();
    assertThat(reloaded.getName()).isEqualTo("Target Terminal");
    assertThat(reloaded.getUexSyncedAt()).isNull();
    assertThat(reloaded.getUexHasLoadingDock()).isNull();
    assertThat(reloaded.getHasLoadingDockOverridden()).isFalse();
    Terminal untouched = terminalRepository.findById(victim.getId()).orElseThrow();
    assertThat(untouched.getName()).isEqualTo("Victim Terminal");
    assertThat(untouched.getHidden()).isFalse();
  }

  @Test
  void refineryOrderDtoCreateIgnoresForgedIdOwnerStaffelAndStatus() throws Exception {
    joinIridium(member);
    Location station = refineryStation();
    List<Map<String, Object>> goods = goods();
    RefineryOrder victim = new RefineryOrder();
    victim.setOwner(otherMember);
    victim.setLocation(station);
    victim.setStartedAt(Instant.now());
    victim.setStatus(RefineryOrderStatus.IN_PROGRESS);
    victim = refineryOrderRepository.save(victim);
    UUID otherSquadron = UUID.randomUUID();

    UUID createdId =
        id(
            memberPost(
                "/api/v1/refinery-orders",
                body(
                    "id",
                    victim.getId(),
                    "owner",
                    Map.of("id", otherMember.getId()),
                    "owningSquadron",
                    Map.of("id", otherSquadron),
                    "status",
                    "COMPLETED",
                    "location",
                    Map.of("id", station.getId()),
                    "durationMinutes",
                    60,
                    "goods",
                    goods,
                    "version",
                    7)));

    assertThat(createdId).isNotEqualTo(victim.getId());
    RefineryOrder created = refineryOrderRepository.findById(createdId).orElseThrow();
    assertThat(created.getOwner().getId()).isEqualTo(member.getId());
    assertThat(created.getStatus()).isEqualTo(RefineryOrderStatus.OPEN);
    assertThat(created.getOwningOrgUnit().getId()).isEqualTo(Squadron.IRIDIUM_ID);
    RefineryOrder untouched = refineryOrderRepository.findById(victim.getId()).orElseThrow();
    assertThat(untouched.getOwner().getId()).isEqualTo(otherMember.getId());
    assertThat(untouched.getStatus()).isEqualTo(RefineryOrderStatus.IN_PROGRESS);
  }

  @Test
  void refineryOrderDtoUpdateKeepsOwnerStaffelAndPathIdentity() throws Exception {
    joinIridium(member);
    Location station = refineryStation();
    List<Map<String, Object>> goods = goods();
    RefineryOrder victim = new RefineryOrder();
    victim.setOwner(otherMember);
    victim.setLocation(station);
    victim.setStartedAt(Instant.now());
    victim.setDurationMinutes(10L);
    victim = refineryOrderRepository.save(victim);
    RefineryOrder own = new RefineryOrder();
    own.setOwner(member);
    own.setOwningOrgUnit(squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow());
    own.setLocation(station);
    own.setStartedAt(Instant.now());
    own.setDurationMinutes(10L);
    own = refineryOrderRepository.saveAndFlush(own);

    memberPut(
        "/api/v1/refinery-orders/" + own.getId(),
        body(
            "id", victim.getId(),
            "owner", Map.of("id", otherMember.getId()),
            "owningSquadron", Map.of("id", UUID.randomUUID()),
            "location", Map.of("id", station.getId()),
            "durationMinutes", 90,
            "goods", goods,
            "version", own.getVersion()));

    RefineryOrder updated = refineryOrderRepository.findById(own.getId()).orElseThrow();
    assertThat(updated.getOwner().getId()).isEqualTo(member.getId());
    assertThat(updated.getOwningOrgUnit().getId()).isEqualTo(Squadron.IRIDIUM_ID);
    assertThat(updated.getDurationMinutes()).isEqualTo(90L);
    RefineryOrder untouched = refineryOrderRepository.findById(victim.getId()).orElseThrow();
    assertThat(untouched.getOwner().getId()).isEqualTo(otherMember.getId());
    assertThat(untouched.getDurationMinutes()).isEqualTo(10L);
  }

  private User saveUser(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    return userRepository.save(user);
  }

  private void joinIridium(User user) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), Squadron.IRIDIUM_ID));
    membership.setUser(user);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
  }

  private Location refineryStation() {
    SpaceStation spaceStation = new SpaceStation();
    spaceStation.setName("Dual-Use Station");
    spaceStation.setHasRefineryTerminal(true);
    spaceStationRepository.save(spaceStation);
    Location location = new Location();
    location.setName("Dual-Use Refinery");
    location.setSpaceStation(spaceStation);
    return locationRepository.save(location);
  }

  private List<Map<String, Object>> goods() {
    Material ore = new Material();
    ore.setName("Dual-Use Ore");
    ore.setType(MaterialType.RAW);
    ore = materialRepository.save(ore);
    return List.of(
        body(
            "inputMaterial", Map.of("id", ore.getId()),
            "inputQuantity", 32,
            "outputMaterial", Map.of("id", ore.getId()),
            "outputQuantity", 32,
            "quality", 100));
  }

  private static Map<String, Object> body(Object... keysAndValues) {
    if (keysAndValues.length % 2 != 0) {
      throw new IllegalArgumentException(
          "body needs key-value pairs, got " + keysAndValues.length + " arguments");
    }
    Map<String, Object> body = new HashMap<>();
    for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
      body.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return body;
  }

  private static UUID id(JsonNode node) {
    return UUID.fromString(node.get("id").asString());
  }

  private JsonNode adminPost(String path, Map<String, Object> body) throws Exception {
    return send(post(path), body, this::asAdmin);
  }

  private JsonNode adminPut(String path, Map<String, Object> body) throws Exception {
    return send(put(path), body, this::asAdmin);
  }

  private JsonNode memberPost(String path, Map<String, Object> body) throws Exception {
    return send(post(path), body, this::asMember);
  }

  private JsonNode memberPut(String path, Map<String, Object> body) throws Exception {
    return send(put(path), body, this::asMember);
  }

  private JsonNode send(
      MockHttpServletRequestBuilder request,
      Map<String, Object> body,
      Function<MockHttpServletRequestBuilder, MockHttpServletRequestBuilder> caller)
      throws Exception {
    String response =
        mockMvc
            .perform(
                caller
                    .apply(request)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().is2xxSuccessful())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return response.isEmpty() ? json.createObjectNode() : json.readTree(response);
  }

  private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
    return request.with(
        jwt()
            .jwt(builder -> builder.subject(admin.getId().toString()))
            .authorities(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
  }

  private MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request) {
    return request.with(
        jwt()
            .jwt(builder -> builder.subject(member.getId().toString()))
            .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
  }
}
