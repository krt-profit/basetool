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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.*;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderStoreDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderStoreItemDto;
import de.greluc.krt.profit.basetool.backend.repository.*;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RefineryOrderTest {

  @Autowired private SquadronRepository squadronRepository;

  private Squadron iridium;

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @Autowired private RefineryOrderRepository refineryOrderRepository;

  @Autowired private LocationRepository locationRepository;

  @Autowired private StarSystemRepository starSystemRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private SpaceStationRepository spaceStationRepository;

  @Autowired private MissionRepository missionRepository;

  @Autowired private RefiningMethodRepository refiningMethodRepository;

  @Autowired private MaterialRepository materialRepository;

  @Autowired private InventoryItemRepository inventoryItemRepository;

  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @MockitoBean private JwtDecoder jwtDecoder;

  private User user1;
  private User adminUser;
  private Location station;
  private Mission mission;
  private RefiningMethod dinyx;
  private RefiningMethod ferron;
  private Material quantanium;
  private Material gold;

  @BeforeEach
  void setUp() {
    iridium = squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow();
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    user1 = new User();
    user1.setId(UUID.randomUUID());
    user1.setUsername("refineryUser");
    userRepository.save(user1);
    saveIridiumMembership(user1);

    adminUser = new User();
    adminUser.setId(UUID.randomUUID());
    adminUser.setUsername("refineryAdmin");
    userRepository.save(adminUser);
    saveIridiumMembership(adminUser);

    StarSystem system = new StarSystem();
    system.setName("Stanton");
    starSystemRepository.save(system);

    SpaceStation spaceStation = new SpaceStation();
    spaceStation.setName("ARC-L1 Station");
    spaceStation.setHasRefinery(true);
    spaceStationRepository.save(spaceStation);

    station = new Location();
    station.setName("ARC-L1");
    station.setSpaceStation(spaceStation);
    locationRepository.save(station);

    mission = new Mission();

    mission.setOwningOrgUnit(iridium);
    mission.setName("Mining Op");
    missionRepository.save(mission);

    dinyx = new RefiningMethod();
    dinyx.setName("Dinyx Solvation");
    dinyx = refiningMethodRepository.save(dinyx);

    ferron = new RefiningMethod();
    ferron.setName("Ferron Exchange");
    ferron = refiningMethodRepository.save(ferron);

    quantanium = new Material();
    quantanium.setName("Quantanium");
    quantanium.setType(MaterialType.RAW);
    quantanium = materialRepository.save(quantanium);

    gold = new Material();
    gold.setName("Gold");
    gold.setType(MaterialType.RAW);
    gold = materialRepository.save(gold);
  }

  /** Post-R9 D3 (V101): home Staffel via membership row. */
  private void saveIridiumMembership(User u) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(u.getId(), Squadron.IRIDIUM_ID));
    m.setUser(u);
    m.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(m);
  }

  @Test
  void testUserCreateAndManageRefineryOrder() throws Exception {
    RefineryOrder order = new RefineryOrder();
    order.setOwningOrgUnit(iridium);
    order.setLocation(station);
    order.setStartedAt(Instant.now());
    order.setDurationMinutes(120L);
    order.setRefiningMethod(dinyx);
    order.setExpenses(500.00);
    order.setMission(mission);

    Set<RefineryGood> goods = new HashSet<>();
    RefineryGood good1 = new RefineryGood();
    good1.setInputMaterial(quantanium);
    good1.setInputQuantity(32);
    good1.setOutputMaterial(quantanium);
    good1.setOutputQuantity(32);
    good1.setQuality(100);
    goods.add(good1);
    order.setGoods(goods);

    // Create
    String response =
        mockMvc
            .perform(
                post("/api/v1/refinery-orders")
                    .with(
                        jwt()
                            .jwt(builder -> builder.subject(user1.getId().toString()))
                            .authorities(
                                new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                                new SimpleGrantedAuthority("HANGAR_READ"),
                                new SimpleGrantedAuthority("HANGAR_WRITE"),
                                new SimpleGrantedAuthority("MISSION_READ"),
                                new SimpleGrantedAuthority("REFINERY_READ"),
                                new SimpleGrantedAuthority("REFINERY_WRITE")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(order)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    tools.jackson.databind.JsonNode jsonResponse = objectMapper.readTree(response);
    UUID savedId = UUID.fromString(jsonResponse.get("id").asString());
    RefineryOrder saved = refineryOrderRepository.findById(savedId).orElseThrow();
    assertNotNull(saved.getId());
    assertEquals(user1.getId(), saved.getOwner().getId());
    assertEquals("ARC-L1", saved.getLocation().getName());
    assertEquals(1, saved.getGoods().size());
    assertEquals("Quantanium", saved.getGoods().iterator().next().getInputMaterial().getName());
    assertEquals(mission.getId(), saved.getMission().getId());

    // Build a fresh detached payload for the update — mutating the managed `saved` entity
    // (returned by findById above) would dirty-mark it in the persistence context. The
    // controller's getRefineryOrder reloads via the same context, and the subsequent
    // explicit version check sees the bumped @Version, surfacing as a 409. Sending a
    // detached payload mirrors what the frontend actually does and dodges the
    // managed-entity quirk.
    RefineryOrder updatePayload = new RefineryOrder();
    updatePayload.setId(saved.getId());
    updatePayload.setVersion(saved.getVersion());
    updatePayload.setLocation(saved.getLocation());
    updatePayload.setStartedAt(saved.getStartedAt());
    updatePayload.setDurationMinutes(saved.getDurationMinutes());
    updatePayload.setExpenses(saved.getExpenses());
    updatePayload.setMission(saved.getMission());
    updatePayload.setOwner(saved.getOwner());
    updatePayload.setOwningOrgUnit(saved.getOwningOrgUnit());
    updatePayload.setRefiningMethod(ferron);
    RefineryGood good2 = new RefineryGood();
    good2.setInputMaterial(gold);
    good2.setInputQuantity(100);
    good2.setOutputMaterial(gold);
    good2.setOutputQuantity(100);
    good2.setQuality(100);
    updatePayload.setGoods(new HashSet<>(Set.of(good2)));

    mockMvc
        .perform(
            put("/api/v1/refinery-orders/" + saved.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user1.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                            new SimpleGrantedAuthority("HANGAR_READ"),
                            new SimpleGrantedAuthority("HANGAR_WRITE"),
                            new SimpleGrantedAuthority("MISSION_READ"),
                            new SimpleGrantedAuthority("REFINERY_READ"),
                            new SimpleGrantedAuthority("REFINERY_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatePayload)))
        .andExpect(status().isOk());

    RefineryOrder updated = refineryOrderRepository.findById(saved.getId()).orElseThrow();
    assertEquals("Ferron Exchange", updated.getRefiningMethod().getName());
    assertEquals(1, updated.getGoods().size());
    assertEquals("Gold", updated.getGoods().iterator().next().getInputMaterial().getName());

    // Delete
    mockMvc
        .perform(
            delete("/api/v1/refinery-orders/" + saved.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user1.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                            new SimpleGrantedAuthority("HANGAR_READ"),
                            new SimpleGrantedAuthority("HANGAR_WRITE"),
                            new SimpleGrantedAuthority("MISSION_READ"),
                            new SimpleGrantedAuthority("REFINERY_READ"),
                            new SimpleGrantedAuthority("REFINERY_WRITE"))))
        .andExpect(status().isOk());

    assertEquals(
        de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus.CANCELED,
        refineryOrderRepository.findById(saved.getId()).get().getStatus());
  }

  @Test
  void testAdminManageUserRefineryOrder() throws Exception {
    // User creates order
    RefineryOrder order = new RefineryOrder();
    order.setOwningOrgUnit(iridium);
    order.setLocation(station);
    order.setOwner(user1);
    order.setRefiningMethod(dinyx); // Set a method
    RefineryGood good = new RefineryGood();
    good.setInputMaterial(quantanium);
    good.setInputQuantity(100);
    good.setOutputMaterial(quantanium);
    good.setOutputQuantity(100);
    good.setQuality(100);
    good.setRefineryOrder(order);
    order.setGoods(new HashSet<>(Set.of(good)));
    order = refineryOrderRepository.save(order);

    // Admin updates it
    order.setRefiningMethod(ferron);
    mockMvc
        .perform(
            put("/api/v1/refinery-orders/users/" + user1.getId() + "/" + order.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(adminUser.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("ROLE_MANAGE"),
                            new SimpleGrantedAuthority("USER_MANAGE"),
                            new SimpleGrantedAuthority("MISSION_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE"),
                            new SimpleGrantedAuthority("REFINERY_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(order)))
        .andExpect(status().isOk());

    RefineryOrder updated = refineryOrderRepository.findById(order.getId()).orElseThrow();
    assertEquals("Ferron Exchange", updated.getRefiningMethod().getName());

    // Admin deletes it
    mockMvc
        .perform(
            delete("/api/v1/refinery-orders/users/" + user1.getId() + "/" + order.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(adminUser.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("ROLE_MANAGE"),
                            new SimpleGrantedAuthority("USER_MANAGE"),
                            new SimpleGrantedAuthority("MISSION_MANAGE"),
                            new SimpleGrantedAuthority("HANGAR_MANAGE"),
                            new SimpleGrantedAuthority("REFINERY_MANAGE"))))
        .andExpect(status().isOk());

    assertEquals(
        de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus.CANCELED,
        refineryOrderRepository.findById(order.getId()).get().getStatus());
  }

  @Test
  void testAccessControl() throws Exception {
    // User1 creates order
    RefineryOrder order = new RefineryOrder();
    order.setOwningOrgUnit(iridium);
    order.setLocation(station);
    order.setOwner(user1);
    RefineryGood good = new RefineryGood();
    good.setInputMaterial(quantanium);
    good.setInputQuantity(100);
    good.setOutputMaterial(quantanium);
    good.setOutputQuantity(100);
    good.setQuality(100);
    good.setRefineryOrder(order);
    order.setGoods(new HashSet<>(Set.of(good)));
    order = refineryOrderRepository.save(order);

    User user2 = new User();
    user2.setId(UUID.randomUUID());
    user2.setUsername("user2");
    userRepository.save(user2);
    saveIridiumMembership(user2);

    // User2 tries to update User1's order
    mockMvc
        .perform(
            put("/api/v1/refinery-orders/" + order.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user2.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                            new SimpleGrantedAuthority("HANGAR_READ"),
                            new SimpleGrantedAuthority("HANGAR_WRITE"),
                            new SimpleGrantedAuthority("MISSION_READ"),
                            new SimpleGrantedAuthority("REFINERY_READ"),
                            new SimpleGrantedAuthority("REFINERY_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(order)))
        .andExpect(status().isForbidden()); // AccessDeniedException

    // User2 tries to admin-update User1's order (should be forbidden 403)
    mockMvc
        .perform(
            put("/api/v1/refinery-orders/users/" + user1.getId() + "/" + order.getId())
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user2.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                            new SimpleGrantedAuthority("HANGAR_READ"),
                            new SimpleGrantedAuthority("HANGAR_WRITE"),
                            new SimpleGrantedAuthority("MISSION_READ"),
                            new SimpleGrantedAuthority("REFINERY_READ"),
                            new SimpleGrantedAuthority("REFINERY_WRITE"))) // No ROLE_ADMIN
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(order)))
        .andExpect(status().isForbidden());
  }

  @Test
  void testCreateRefineryOrder_WithNonRawMaterial_ShouldFail() throws Exception {
    Material refinedMaterial = new Material();
    refinedMaterial.setName("Refined Iron");
    refinedMaterial.setType(MaterialType.REFINED);
    refinedMaterial = materialRepository.save(refinedMaterial);

    RefineryOrder order = new RefineryOrder();

    order.setOwningOrgUnit(iridium);
    order.setLocation(station);
    order.setStartedAt(Instant.now());

    Set<RefineryGood> goods = new HashSet<>();
    RefineryGood good = new RefineryGood();
    good.setInputMaterial(refinedMaterial);
    good.setInputQuantity(50);
    good.setOutputMaterial(refinedMaterial);
    good.setOutputQuantity(50);
    good.setQuality(100);
    goods.add(good);
    order.setGoods(goods);

    mockMvc
        .perform(
            post("/api/v1/refinery-orders")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user1.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                            new SimpleGrantedAuthority("HANGAR_READ"),
                            new SimpleGrantedAuthority("HANGAR_WRITE"),
                            new SimpleGrantedAuthority("MISSION_READ"),
                            new SimpleGrantedAuthority("REFINERY_READ"),
                            new SimpleGrantedAuthority("REFINERY_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(order)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void testCreateRefineryOrder_WithNullMission_ShouldSucceed() throws Exception {
    RefineryOrder order = new RefineryOrder();
    order.setOwningOrgUnit(iridium);
    order.setLocation(station);
    order.setStartedAt(Instant.now());
    order.setDurationMinutes(120L);
    order.setRefiningMethod(dinyx);
    order.setExpenses(500.00);
    order.setMission(null); // Explicitly null

    Set<RefineryGood> goods = new HashSet<>();
    RefineryGood good1 = new RefineryGood();
    good1.setInputMaterial(quantanium);
    good1.setInputQuantity(32);
    good1.setOutputMaterial(quantanium);
    good1.setOutputQuantity(32);
    good1.setQuality(100);
    goods.add(good1);
    order.setGoods(goods);

    String response =
        mockMvc
            .perform(
                post("/api/v1/refinery-orders")
                    .with(
                        jwt()
                            .jwt(builder -> builder.subject(user1.getId().toString()))
                            .authorities(
                                new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                                new SimpleGrantedAuthority("HANGAR_READ"),
                                new SimpleGrantedAuthority("HANGAR_WRITE"),
                                new SimpleGrantedAuthority("MISSION_READ"),
                                new SimpleGrantedAuthority("REFINERY_READ"),
                                new SimpleGrantedAuthority("REFINERY_WRITE")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(order)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    tools.jackson.databind.JsonNode jsonResponse = objectMapper.readTree(response);
    UUID savedId = UUID.fromString(jsonResponse.get("id").asString());
    RefineryOrder saved = refineryOrderRepository.findById(savedId).orElseThrow();
    assertNotNull(saved.getId());
    assertNull(saved.getMission());
  }

  @Test
  void testStoreRefineryOrder_WithDecimalAmount() throws Exception {
    // User creates order
    RefineryOrder order = new RefineryOrder();
    order.setOwningOrgUnit(iridium);
    order.setLocation(station);
    order.setOwner(user1);
    order.setRefiningMethod(dinyx);
    order = refineryOrderRepository.save(order);

    RefineryOrderStoreItemDto itemDto =
        new RefineryOrderStoreItemDto(
            quantanium.getId(),
            station.getId(),
            100,
            32.543, // Decimal amount
            user1.getId(),
            null,
            null,
            null,
            null);
    RefineryOrderStoreDto storeDto = new RefineryOrderStoreDto(java.util.List.of(itemDto));

    mockMvc
        .perform(
            post("/api/v1/refinery-orders/" + order.getId() + "/store")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user1.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                            new SimpleGrantedAuthority("REFINERY_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(storeDto)))
        .andExpect(status().isOk());

    RefineryOrder stored = refineryOrderRepository.findById(order.getId()).orElseThrow();
    assertEquals(
        de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus.COMPLETED,
        stored.getStatus());

    java.util.List<InventoryItem> items =
        inventoryItemRepository
            .findMaterialRowsByUser(user1, org.springframework.data.domain.Pageable.unpaged())
            .getContent();
    boolean found = items.stream().anyMatch(i -> i.getAmount().equals(32.543));
    assertTrue(found, "Decimal amount should be stored correctly in inventory");
  }

  @Test
  void testStoreRefineryOrder_WithNoteAndAmountOverride() throws Exception {
    // Given: ein Raffinerieauftrag mit einem Output-Material
    RefineryOrder order = new RefineryOrder();
    order.setOwningOrgUnit(iridium);
    order.setLocation(station);
    order.setOwner(user1);
    order.setRefiningMethod(dinyx);
    Set<RefineryGood> goods = new HashSet<>();
    RefineryGood good = new RefineryGood();
    good.setInputMaterial(quantanium);
    good.setInputQuantity(100);
    good.setOutputMaterial(quantanium);
    good.setOutputQuantity(100);
    good.setQuality(100);
    goods.add(good);
    order.setGoods(goods);
    good.setRefineryOrder(order);
    order = refineryOrderRepository.save(order);
    UUID orderId = order.getId();

    // When: Nutzer ueberschreibt die Menge im Einlager-Dialog und ergaenzt eine Notiz
    RefineryOrderStoreItemDto itemDto =
        new RefineryOrderStoreItemDto(
            quantanium.getId(),
            station.getId(),
            100,
            42.125,
            user1.getId(),
            null,
            "Charge A - Tagesproduktion",
            null,
            null);
    RefineryOrderStoreDto storeDto = new RefineryOrderStoreDto(java.util.List.of(itemDto));

    mockMvc
        .perform(
            post("/api/v1/refinery-orders/" + orderId + "/store")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user1.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                            new SimpleGrantedAuthority("REFINERY_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(storeDto)))
        .andExpect(status().isOk());

    // Then: Notiz am InventoryItem persistiert, Raffinerieauftrag-Output ist auf neue Menge
    // angepasst
    java.util.List<InventoryItem> items =
        inventoryItemRepository
            .findMaterialRowsByUser(user1, org.springframework.data.domain.Pageable.unpaged())
            .getContent();
    InventoryItem persisted =
        items.stream()
            .filter(i -> i.getAmount() != null && i.getAmount().equals(42.125))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("InventoryItem mit Override-Menge nicht gefunden"));
    assertEquals(
        "Charge A - Tagesproduktion",
        persisted.getNote(),
        "Notiz aus Einlager-Dialog muss am InventoryItem persistiert werden");

    RefineryOrder stored = refineryOrderRepository.findById(order.getId()).orElseThrow();
    RefineryGood updated = stored.getGoods().iterator().next();
    // Quantanium is an SCU material (entity defaults to QuantityType.SCU; see issue #230 fix).
    // The user-entered 42.125 SCU is written back as units (centi-SCU): round(42.125 * 100) = 4213.
    assertEquals(
        4213,
        updated.getOutputQuantity().intValue(),
        "Manuell eingegebene Menge muss zurueck in RefineryGood.outputQuantity geschrieben werden"
            + " (Units, gerundet; 42.125 SCU -> 4213 Units)");
  }

  @Test
  void testStoreRefineryOrder_RejectsNegativeAmount() throws Exception {
    RefineryOrder order = new RefineryOrder();
    order.setOwningOrgUnit(iridium);
    order.setLocation(station);
    order.setOwner(user1);
    order.setRefiningMethod(dinyx);
    order = refineryOrderRepository.save(order);

    RefineryOrderStoreItemDto itemDto =
        new RefineryOrderStoreItemDto(
            quantanium.getId(), station.getId(), 100, -1.0, user1.getId(), null, null, null, null);
    RefineryOrderStoreDto storeDto = new RefineryOrderStoreDto(java.util.List.of(itemDto));

    mockMvc
        .perform(
            post("/api/v1/refinery-orders/" + order.getId() + "/store")
                .with(
                    jwt()
                        .jwt(builder -> builder.subject(user1.getId().toString()))
                        .authorities(
                            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                            new SimpleGrantedAuthority("REFINERY_WRITE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(storeDto)))
        .andExpect(status().isBadRequest());
  }
}
