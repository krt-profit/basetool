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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.backend.model.City;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionCrew;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.RefineryGood;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.CityRepository;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialCategoryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.JobTypeService;
import de.greluc.krt.profit.basetool.backend.service.LocationService;
import de.greluc.krt.profit.basetool.backend.service.MaterialService;
import de.greluc.krt.profit.basetool.backend.service.ShipTypeService;
import de.greluc.krt.profit.basetool.backend.support.Permissions;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Drives the main read endpoints of aggregates with lazy {@code @ManyToOne} associations through
 * the real HTTP stack with no test transaction, as in production with open-in-view disabled.
 *
 * <ul>
 *   <li>Each endpoint answers 200 twice, the second time from the caches where present.
 *   <li>Its statement count does not grow between a small and a large seed.
 * </ul>
 *
 * <p>Seeding and clean-up commit through a {@link TransactionTemplate}; {@link #cleanUp()} removes
 * all seeded data.
 */
@SpringBootTest
@ActiveProfiles("test")
class LazyToOneReadPathsTest {

  @Autowired private WebApplicationContext context;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private CacheManager cacheManager;
  @Autowired private UserRepository userRepository;
  @Autowired private OrgUnitMembershipRepository membershipRepository;
  @Autowired private CityRepository cityRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private ManufacturerRepository manufacturerRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private ShipRepository shipRepository;
  @Autowired private MaterialCategoryRepository materialCategoryRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private JobTypeRepository jobTypeRepository;
  @Autowired private MissionRepository missionRepository;
  @Autowired private RefineryOrderRepository refineryOrderRepository;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MaterialService materialService;
  @Autowired private LocationService locationService;
  @Autowired private ShipTypeService shipTypeService;
  @Autowired private JobTypeService jobTypeService;

  /**
   * An administrator with every permission constant, so no endpoint is skipped for a missing grant:
   * the subject here is the read path, not the authorization matrix.
   */
  private static final List<GrantedAuthority> ADMIN_AUTHORITIES = adminAuthorities();

  @MockitoBean private JwtDecoder jwtDecoder;

  private MockMvc mockMvc;

  /** Everything one seed created, in creation order, so {@link #cleanUp()} can undo it. */
  private final List<Seed> seeds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @AfterEach
  void cleanUp() {
    for (Seed seed : seeds.reversed()) {
      transactionTemplate.executeWithoutResult(status -> seed.delete());
    }
    seeds.clear();
    clearCaches();
  }

  @Test
  void everyReadPathAnswersTwiceAndCostsTheSameForThreeAndTwelveRows() throws Exception {
    Seed small = seed(3);
    Map<String, Long> smallCounts = measureAll(small);
    Seed large = seed(12);
    Map<String, Long> largeCounts = measureAll(large);

    StringBuilder table =
        new StringBuilder("\n[BE-PERF-11] statements per request, 3 vs 12 rows\n");
    smallCounts.forEach(
        (endpoint, count) ->
            table
                .append(
                    String.format("  %-48s %4d  %4d", endpoint, count, largeCounts.get(endpoint)))
                .append('\n'));
    System.out.println(table);

    for (Map.Entry<String, Long> entry : smallCounts.entrySet()) {
      assertThat(largeCounts.get(entry.getKey()))
          .as("statements for %s must not grow with the rows (per-row lazy load?)", entry.getKey())
          .isLessThanOrEqualTo(entry.getValue());
    }
  }

  /**
   * Verifies that each cached entity is stored with a fully initialised graph: cached methods are
   * called through their beans, and associations are then read without a session.
   */
  @Test
  void aCachedEntityIsCompleteBeforeTheCacheStoresIt() {
    Seed seed = seed(2);
    clearCaches();

    Material material = materialService.getMaterial(seed.rawMaterialIds.getFirst());
    assertThat(material.getCategory().getName()).startsWith("Lazy Ores");
    assertThat(material.getRefinedMaterial().getName()).startsWith("Lazy Refined");
    assertThat(material.getRefinedMaterial().getCategory().getName()).startsWith("Lazy Ores");

    materialService
        .getAllMaterials(PageRequest.of(0, 500))
        .forEach(
            m -> {
              if (m.getCategory() != null) {
                assertThat(m.getCategory().getName()).isNotNull();
              }
              if (m.getRefinedMaterial() != null) {
                assertThat(m.getRefinedMaterial().getName()).isNotNull();
              }
            });

    assertThat(locationService.getLocation(seed.locationId).getCity().getName())
        .startsWith("Lazy City");
    assertThat(shipTypeService.getShipType(seed.shipTypeId).getManufacturer().getName())
        .startsWith("Lazy Works");
    shipTypeService
        .getAllShipTypes(PageRequest.of(0, 500), true)
        .forEach(
            t -> {
              if (t.getManufacturer() != null) {
                assertThat(t.getManufacturer().getName()).isNotNull();
              }
            });
    jobTypeService
        .getJobTypes(null)
        .forEach(
            j -> {
              if (j.getParent() != null) {
                assertThat(j.getParent().getName()).isNotNull();
              }
            });
  }

  /**
   * Calls every endpoint cold (caches cleared) and then warm for one seed; both calls must answer
   * 200.
   *
   * @param seed the data the endpoints read
   * @return endpoint label to the statement count of its cold call, in a stable order
   * @throws Exception if MockMvc fails
   */
  private Map<String, Long> measureAll(Seed seed) throws Exception {
    Map<String, Function<Seed, String>> endpoints = new LinkedHashMap<>();
    endpoints.put("GET /missions/{id}", s -> "/api/v1/missions/" + s.missionId);
    endpoints.put("GET /missions", s -> "/api/v1/missions?page=0&size=50");
    endpoints.put("GET /refinery-orders/all", s -> "/api/v1/refinery-orders/all?page=0&size=50");
    endpoints.put(
        "GET /refinery-orders/{id}", s -> "/api/v1/refinery-orders/" + s.orderIds.getFirst());
    endpoints.put("GET /refinery-orders/my-orders", s -> "/api/v1/refinery-orders/my-orders");
    endpoints.put("GET /hangar/ships", s -> "/api/v1/hangar/ships?page=0&size=50");
    endpoints.put("GET /hangar/my-ships", s -> "/api/v1/hangar/my-ships");
    endpoints.put("GET /inventory/my-inventory", s -> "/api/v1/inventory/my-inventory");
    endpoints.put("GET /inventory/all", s -> "/api/v1/inventory/all?page=0&size=50");
    endpoints.put("GET /materials", s -> "/api/v1/materials?page=0&size=200");
    endpoints.put("GET /materials/{id}", s -> "/api/v1/materials/" + s.rawMaterialIds.getFirst());
    endpoints.put("GET /ship-types", s -> "/api/v1/ship-types?page=0&size=200");
    endpoints.put("GET /ship-types/{id}", s -> "/api/v1/ship-types/" + s.shipTypeId);
    endpoints.put("GET /locations/{id}", s -> "/api/v1/locations/" + s.locationId);
    endpoints.put("GET /job-types", s -> "/api/v1/job-types");

    Map<String, Long> counts = new LinkedHashMap<>();
    for (Map.Entry<String, Function<Seed, String>> endpoint : endpoints.entrySet()) {
      String url = endpoint.getValue().apply(seed);
      clearCaches();
      Statistics stats = statistics();
      stats.clear();
      call(url, seed);
      counts.put(endpoint.getKey(), stats.getPrepareStatementCount());
      call(url, seed);
    }
    return counts;
  }

  private void call(String url, Seed seed) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get(url)
                    .with(
                        jwt()
                            .jwt(token -> token.subject(seed.adminId.toString()))
                            .authorities(ADMIN_AUTHORITIES)))
            .andReturn();
    assertThat(result.getResponse().getStatus())
        .as(
            "%s answered %s: %s",
            url, result.getResponse().getStatus(), result.getResolvedException())
        .isEqualTo(200);
  }

  private static List<GrantedAuthority> adminAuthorities() {
    List<GrantedAuthority> authorities = new ArrayList<>();
    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
    for (Field field : Permissions.class.getFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
        try {
          authorities.add(new SimpleGrantedAuthority((String) field.get(null)));
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    return List.copyOf(authorities);
  }

  private void clearCaches() {
    cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
  }

  private Statistics statistics() {
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    return stats;
  }

  /**
   * Seeds a self-contained data set with every lazy association populated: an admin with {@code
   * rows} ships, inventory rows and refinery orders, and a mission with {@code rows} participants,
   * units and crew seats.
   *
   * @param rows how many rows of each kind
   * @return the ids of what was created
   */
  private Seed seed(int rows) {
    Seed seed = new Seed();
    seeds.add(seed);
    transactionTemplate.executeWithoutResult(
        status -> {
          String tag = UUID.randomUUID().toString().substring(0, 8);
          User admin = newUser("lazy-admin-" + tag, seed);
          seed.adminId = admin.getId();

          City city = new City();
          city.setName("Lazy City " + tag);
          seed.cityId = cityRepository.save(city).getId();
          Location location = new Location();
          location.setName("Lazy Station " + tag);
          location.setCity(city);
          seed.locationId = locationRepository.save(location).getId();

          Manufacturer manufacturer = new Manufacturer();
          manufacturer.setName("Lazy Works " + tag);
          manufacturer.setAbbreviation("LW" + tag.substring(0, 3));
          seed.manufacturerId = manufacturerRepository.save(manufacturer).getId();
          ShipType shipType = new ShipType();
          shipType.setName("Lazy Hull " + tag);
          shipType.setManufacturer(manufacturer);
          seed.shipTypeId = shipTypeRepository.save(shipType).getId();

          MaterialCategory category = new MaterialCategory();
          category.setName("Lazy Ores " + tag);
          seed.categoryId = materialCategoryRepository.save(category).getId();

          JobType pilot = new JobType();
          pilot.setName("Lazy Pilot " + tag);
          pilot.setArchetype(JobTypeArchetype.CREW);
          seed.jobTypeIds.add(jobTypeRepository.save(pilot).getId());

          Mission mission = new Mission();
          mission.setName("Lazy Mission " + tag);
          mission.setStatus("PLANNED");
          mission.setOwningOrgUnit(squadronRepository.findById(Squadron.IRIDIUM_ID).orElseThrow());

          for (int i = 0; i < rows; i++) {
            Material refined = new Material();
            refined.setName("Lazy Refined " + tag + "-" + i);
            refined.setType(MaterialType.REFINED);
            refined.setCategory(category);
            materialRepository.save(refined);
            Material raw = new Material();
            raw.setName("Lazy Raw " + tag + "-" + i);
            raw.setType(MaterialType.RAW);
            raw.setCategory(category);
            raw.setRefinedMaterial(refined);
            materialRepository.save(raw);
            seed.materialIds.add(raw.getId());
            seed.materialIds.add(refined.getId());
            seed.rawMaterialIds.add(raw.getId());

            Ship ship = new Ship();
            ship.setName("Lazy Ship " + tag + "-" + i);
            ship.setShipType(shipType);
            ship.setInsurance("LTI");
            ship.setLocation(location);
            ship.setOwner(admin);
            seed.shipIds.add(shipRepository.save(ship).getId());

            InventoryItem item = new InventoryItem();
            item.setUser(admin);
            item.setMaterial(raw);
            item.setLocation(location);
            item.setQuality(500);
            item.setAmount(10.0);
            seed.inventoryIds.add(inventoryItemRepository.save(item).getId());

            RefineryOrder order = new RefineryOrder();
            order.setOwner(admin);
            order.setLocation(location);
            order.setMission(mission);
            order.setDurationMinutes(60L);
            order.setExpenses(100.0);
            RefineryGood good = new RefineryGood();
            good.setInputMaterial(raw);
            good.setInputQuantity(100);
            good.setOutputMaterial(refined);
            good.setOutputQuantity(50);
            good.setRefineryOrder(order);
            order.getGoods().add(good);
            seed.pendingOrders.add(order);

            User member = newUser("lazy-member-" + tag + "-" + i, seed);
            MissionParticipant participant = new MissionParticipant();
            participant.setMission(mission);
            participant.setUser(member);
            participant.setDesiredMissionJobType(pilot);
            participant.setPlannedMissionJobType(pilot);
            mission.getParticipants().add(participant);

            MissionUnit unit = new MissionUnit();
            unit.setMission(mission);
            unit.setShipType(shipType);
            unit.setShip(ship);
            unit.setResponsibleUser(member);
            unit.setName("Lazy Unit " + i);
            MissionCrew crew = new MissionCrew();
            crew.setMissionUnit(unit);
            crew.setParticipant(participant);
            unit.getCrew().add(crew);
            mission.getAssignedUnits().add(unit);
          }
          seed.missionId = missionRepository.save(mission).getId();
          for (RefineryOrder order : seed.pendingOrders) {
            seed.orderIds.add(refineryOrderRepository.save(order).getId());
          }
          seed.pendingOrders.clear();
        });
    return seed;
  }

  private User newUser(String username, Seed seed) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user = userRepository.save(user);
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), Squadron.IRIDIUM_ID));
    membership.setUser(user);
    membership.setKind(OrgUnitKind.SQUADRON);
    membership.setJoinedAt(Instant.now());
    membershipRepository.save(membership);
    seed.userIds.add(user.getId());
    return user;
  }

  /** The ids one {@link #seed(int)} call created, and how to remove them again. */
  private final class Seed {
    private UUID adminId;
    private UUID cityId;
    private UUID locationId;
    private UUID manufacturerId;
    private UUID shipTypeId;
    private UUID categoryId;
    private UUID missionId;
    private final List<UUID> jobTypeIds = new ArrayList<>();
    private final List<UUID> materialIds = new ArrayList<>();
    private final List<UUID> rawMaterialIds = new ArrayList<>();
    private final List<UUID> shipIds = new ArrayList<>();
    private final List<UUID> inventoryIds = new ArrayList<>();
    private final List<UUID> orderIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private final List<RefineryOrder> pendingOrders = new ArrayList<>();

    /** Deletes children before parents; runs inside one transaction. */
    private void delete() {
      refineryOrderRepository.deleteAllById(orderIds);
      inventoryItemRepository.deleteAllById(inventoryIds);
      if (missionId != null) {
        missionRepository.deleteById(missionId);
      }
      missionRepository.flush();
      shipRepository.deleteAllById(shipIds);
      materialRepository.deleteAllById(rawMaterialIds);
      materialRepository.flush();
      materialRepository.deleteAllById(materialIds);
      materialCategoryRepository.deleteById(categoryId);
      jobTypeRepository.deleteAllById(jobTypeIds);
      shipTypeRepository.deleteById(shipTypeId);
      manufacturerRepository.deleteById(manufacturerId);
      locationRepository.deleteById(locationId);
      cityRepository.deleteById(cityId);
      for (UUID userId : userIds) {
        membershipRepository.deleteById(new OrgUnitMembershipId(userId, Squadron.IRIDIUM_ID));
      }
      membershipRepository.flush();
      userRepository.deleteAllById(userIds);
    }
  }
}
