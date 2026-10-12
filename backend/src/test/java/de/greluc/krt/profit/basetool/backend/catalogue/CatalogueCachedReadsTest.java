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

package de.greluc.krt.profit.basetool.backend.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.controller.CityController;
import de.greluc.krt.profit.basetool.backend.controller.FrequencyTypeController;
import de.greluc.krt.profit.basetool.backend.controller.JobTypeController;
import de.greluc.krt.profit.basetool.backend.controller.LocationController;
import de.greluc.krt.profit.basetool.backend.controller.ManufacturerController;
import de.greluc.krt.profit.basetool.backend.controller.MaterialCategoryController;
import de.greluc.krt.profit.basetool.backend.controller.MaterialController;
import de.greluc.krt.profit.basetool.backend.controller.OutpostController;
import de.greluc.krt.profit.basetool.backend.controller.PoiController;
import de.greluc.krt.profit.basetool.backend.controller.RefiningMethodController;
import de.greluc.krt.profit.basetool.backend.controller.ShipTypeController;
import de.greluc.krt.profit.basetool.backend.controller.StarSystemController;
import de.greluc.krt.profit.basetool.backend.controller.TerminalController;
import de.greluc.krt.profit.basetool.backend.model.City;
import de.greluc.krt.profit.basetool.backend.model.FrequencyType;
import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.Outpost;
import de.greluc.krt.profit.basetool.backend.model.Poi;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.StarSystem;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.StarSystemDto;
import de.greluc.krt.profit.basetool.backend.repository.CityRepository;
import de.greluc.krt.profit.basetool.backend.repository.FrequencyTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialCategoryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.OutpostRepository;
import de.greluc.krt.profit.basetool.backend.repository.PoiRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.StarSystemRepository;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import de.greluc.krt.profit.basetool.backend.service.RefiningMethodService;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins what the catalogue's cached reads return and what they cost, against real Postgres and
 * through the controllers (REQ-DATA-011, REQ-DATA-022; plan §7.6, Phase 4 catalogue).
 *
 * <p>Every cached read is called cold and then warm: the cold call must hit the database and show
 * the seeded row field by field, the warm call must prepare no statement at all and answer the same
 * JSON. A mutator must evict its cache so that the next read is cold again and shows the change.
 */
@SpringBootTest
class CatalogueCachedReadsTest {

  private static final int BIG_PAGE = 1000;

  /** The one statement a controller's read-write transaction prepares for the change source. */
  private static final long TRANSACTION_STAMP_STATEMENTS = 1;

  private final JsonMapper mapper = JsonMapper.builder().build();

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  private final Deque<Runnable> cleanup = new ArrayDeque<>();

  private final Instant startedAt = Instant.now();

  @Autowired private CityController cityController;
  @Autowired private FrequencyTypeController frequencyTypeController;
  @Autowired private JobTypeController jobTypeController;
  @Autowired private LocationController locationController;
  @Autowired private ManufacturerController manufacturerController;
  @Autowired private MaterialCategoryController materialCategoryController;
  @Autowired private MaterialController materialController;
  @Autowired private OutpostController outpostController;
  @Autowired private PoiController poiController;
  @Autowired private RefiningMethodController refiningMethodController;
  @Autowired private ShipTypeController shipTypeController;
  @Autowired private StarSystemController starSystemController;
  @Autowired private TerminalController terminalController;
  @Autowired private RefiningMethodService refiningMethodService;
  @Autowired private DataSource dataSource;
  @Autowired private CityRepository cityRepository;
  @Autowired private FrequencyTypeRepository frequencyTypeRepository;
  @Autowired private JobTypeRepository jobTypeRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private ManufacturerRepository manufacturerRepository;
  @Autowired private MaterialCategoryRepository materialCategoryRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private OutpostRepository outpostRepository;
  @Autowired private PoiRepository poiRepository;
  @Autowired private RefiningMethodRepository refiningMethodRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private StarSystemRepository starSystemRepository;
  @Autowired private TerminalRepository terminalRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private CacheManager cacheManager;

  private UUID cityId;
  private UUID locationId;
  private UUID outpostId;
  private UUID poiId;
  private UUID terminalId;
  private UUID starSystemId;
  private UUID refiningMethodId;
  private UUID manufacturerId;
  private UUID shipTypeId;
  private UUID categoryId;
  private UUID rawMaterialId;
  private UUID refinedMaterialId;
  private UUID parentJobTypeId;
  private UUID childJobTypeId;
  private UUID inactiveJobTypeId;
  private UUID activeFrequencyId;
  private UUID inactiveFrequencyId;

  @BeforeEach
  void seedAndSignInAsAdmin() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("catalogue-cached-reads")
                    .header("alg", "none")
                    .subject(UUID.randomUUID().toString())
                    .build(),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    transactionTemplate.executeWithoutResult(status -> seed());
    clearCaches();
  }

  @AfterEach
  void signOutAndCleanUp() {
    SecurityContextHolder.clearContext();
    transactionTemplate.executeWithoutResult(
        status -> {
          while (!cleanup.isEmpty()) {
            cleanup.pop().run();
          }
        });
    new JdbcTemplate(dataSource)
        .update(
            """
            DELETE FROM audit_event WHERE actor_user_id IS NULL AND occurred_at >= ?
              AND event_type = 'REFINERY_METHOD_UPDATED'
            """,
            Timestamp.from(startedAt));
    clearCaches();
  }

  @Test
  @DisplayName("every cached read shows its row, and a warm read costs no statement")
  void everyCachedReadShowsItsRowAndAWarmReadCostsNoStatement() {
    Statistics stats = statistics();
    for (Read read : reads()) {
      clearCaches();
      stats.clear();
      JsonNode cold = json(read.call().get());
      long coldStatements = stats.getPrepareStatementCount();
      stats.clear();
      JsonNode warm = json(read.call().get());
      long warmStatements = stats.getPrepareStatementCount();

      assertThat(warmStatements)
          .as("%s warm statements", read.label())
          .isLessThanOrEqualTo(TRANSACTION_STAMP_STATEMENTS);
      assertThat(coldStatements)
          .as("%s cold statements", read.label())
          .isGreaterThan(warmStatements);
      assertThat(stats.getQueryExecutionCount())
          .as("%s warm queries", read.label())
          .isLessThanOrEqualTo(TRANSACTION_STAMP_STATEMENTS);
      assertThat(stats.getEntityLoadCount()).as("%s warm entity loads", read.label()).isZero();
      assertThat(warm).as("%s warm equals cold", read.label()).isEqualTo(cold);
      read.pin().accept(cold);
    }
  }

  @Test
  @DisplayName("a mutator evicts its cache and the next read shows the change")
  void aMutatorEvictsItsCacheAndTheNextReadShowsTheChange() {
    Statistics stats = statistics();
    for (Eviction eviction : evictions()) {
      clearCaches();
      eviction.read().get();
      stats.clear();
      eviction.read().get();
      long warmStatements = stats.getPrepareStatementCount();
      assertThat(warmStatements)
          .as("%s is cached before the mutation", eviction.label())
          .isLessThanOrEqualTo(TRANSACTION_STAMP_STATEMENTS);

      eviction.mutation().run();

      stats.clear();
      JsonNode after = json(eviction.read().get());
      assertThat(stats.getPrepareStatementCount())
          .as("%s is read cold after the mutation", eviction.label())
          .isGreaterThan(warmStatements);
      eviction.pinAfter().accept(after);
    }
  }

  private record Read(String label, Supplier<Object> call, Consumer<JsonNode> pin) {}

  private record Eviction(
      String label, Supplier<Object> read, Runnable mutation, Consumer<JsonNode> pinAfter) {}

  private List<Read> reads() {
    List<Read> reads = new ArrayList<>();
    reads.add(
        read(
            "cities page",
            () -> cityController.getAllCities(0, BIG_PAGE, null),
            cityId,
            Map.of(
                "/name",
                "Cat City " + tag,
                "/hasLoadingDock",
                true,
                "/hasLoadingDockOverridden",
                false,
                "/starSystemName",
                "Cat Sys")));
    reads.add(
        read(
            "city by id",
            () -> cityController.getCity(cityId),
            cityId,
            Map.of("/name", "Cat City " + tag, "/planetName", "Cat Planet")));
    reads.add(
        read(
            "outposts page",
            () -> outpostController.getAllOutposts(0, BIG_PAGE, null),
            outpostId,
            Map.of("/name", "Cat Outpost " + tag, "/hasLoadingDock", false)));
    reads.add(
        read(
            "outpost by id",
            () -> outpostController.getOutpost(outpostId),
            outpostId,
            Map.of("/name", "Cat Outpost " + tag)));
    reads.add(
        read(
            "pois page",
            () -> poiController.getAllPois(0, BIG_PAGE, null),
            poiId,
            Map.of("/name", "Cat Poi " + tag, "/hasLoadingDock", true)));
    reads.add(
        read(
            "poi by id",
            () -> poiController.getPoi(poiId),
            poiId,
            Map.of("/name", "Cat Poi " + tag)));
    reads.add(
        read(
            "terminals page",
            () -> terminalController.getAllTerminals(0, BIG_PAGE, null),
            terminalId,
            Map.of(
                "/name", "Cat Terminal " + tag,
                "/nickname", "CT",
                "/hidden", false,
                "/hasLoadingDock", true,
                "/isAutoLoad", false,
                "/isAutoLoadOverridden", false)));
    reads.add(
        read(
            "terminal by id",
            () -> terminalController.getTerminal(terminalId),
            terminalId,
            Map.of("/name", "Cat Terminal " + tag, "/cityName", "Cat City")));
    reads.add(
        read(
            "star systems page",
            () -> starSystemController.getAllStarSystems(0, BIG_PAGE, null),
            starSystemId,
            Map.of(
                "/name", "Cat System " + tag, "/description", "cat description", "/version", 0)));
    reads.add(
        read(
            "star system by id",
            () -> starSystemController.getStarSystem(starSystemId),
            starSystemId,
            Map.of("/name", "Cat System " + tag, "/factionName", "Cat Faction")));
    reads.add(
        read(
            "refining methods page",
            () -> refiningMethodController.getAllRefiningMethods(0, BIG_PAGE, null),
            refiningMethodId,
            Map.of("/name", "Cat Method " + tag, "/code", "CM" + tag, "/ratingYield", 3)));
    reads.add(
        read(
            "refining method by id",
            () -> refiningMethodController.getRefiningMethod(refiningMethodId),
            refiningMethodId,
            Map.of("/name", "Cat Method " + tag, "/ratingSpeed", 5)));
    reads.add(
        read(
            "manufacturers page",
            () -> manufacturerController.getAllManufacturers(0, BIG_PAGE, null, true),
            manufacturerId,
            Map.of("/name", "Cat Works " + tag, "/abbreviation", "C" + tag, "/hidden", false)));
    reads.add(
        read(
            "manufacturer by id",
            () -> manufacturerController.getManufacturer(manufacturerId),
            manufacturerId,
            Map.of("/name", "Cat Works " + tag)));
    reads.add(
        read(
            "ship types page",
            () -> shipTypeController.getAllShipTypes(0, BIG_PAGE, null, true),
            shipTypeId,
            Map.of(
                "/name",
                "Cat Hull " + tag,
                "/scu",
                42,
                "/hidden",
                false,
                "/manufacturer/id",
                manufacturerId.toString(),
                "/manufacturer/name",
                "Cat Works " + tag)));
    reads.add(
        read(
            "ship type by id",
            () -> shipTypeController.getShipType(shipTypeId),
            shipTypeId,
            Map.of("/name", "Cat Hull " + tag, "/manufacturer/abbreviation", "C" + tag)));
    reads.add(
        read(
            "locations page",
            () -> locationController.getAllLocations(0, BIG_PAGE, null, true),
            locationId,
            Map.of(
                "/name",
                "Cat Location " + tag,
                "/description",
                "cat location",
                "/hidden",
                false,
                "/homeLocation",
                true,
                "/version",
                0)));
    reads.add(
        read(
            "location by id",
            () -> locationController.getLocation(locationId),
            locationId,
            Map.of("/name", "Cat Location " + tag)));
    reads.add(
        read(
            "refinery locations",
            () -> locationController.getRefineryLocations(),
            locationId,
            Map.of("/name", "Cat Location " + tag)));
    reads.add(
        read(
            "home locations",
            () -> locationController.getHomeLocations(),
            locationId,
            Map.of("/name", "Cat Location " + tag, "/homeLocation", true)));
    reads.add(
        read(
            "material categories",
            () -> materialCategoryController.getAll(),
            categoryId,
            Map.of("/name", "Cat Ores " + tag, "/version", 0)));
    reads.add(
        read(
            "material category by id",
            () -> materialCategoryController.getById(categoryId),
            categoryId,
            Map.of("/name", "Cat Ores " + tag)));
    reads.add(
        read(
            "materials page",
            () -> materialController.getAllMaterials(false, true, 0, BIG_PAGE, null),
            rawMaterialId,
            materialPin()));
    reads.add(
        read(
            "visible materials page",
            () -> materialController.getAllMaterials(false, false, 0, BIG_PAGE, null),
            rawMaterialId,
            materialPin()));
    reads.add(
        read(
            "material by id",
            () -> materialController.getMaterial(rawMaterialId),
            rawMaterialId,
            materialPin()));
    reads.add(
        read(
            "job types",
            () -> jobTypeController.getAllJobTypes(null, 0, BIG_PAGE, null, false),
            childJobTypeId,
            Map.of(
                "/name",
                "Cat Child " + tag,
                "/archetype",
                "CREW",
                "/parentId",
                parentJobTypeId.toString(),
                "/active",
                true,
                "/version",
                0)));
    reads.add(
        read(
            "job types of an archetype",
            () -> jobTypeController.getAllJobTypes(JobTypeArchetype.CREW, 0, BIG_PAGE, null, true),
            inactiveJobTypeId,
            Map.of("/name", "Cat Inactive " + tag, "/active", false)));
    reads.add(
        read(
            "frequency types",
            () -> frequencyTypeController.getAllFrequencyTypes(0, BIG_PAGE, null, null),
            activeFrequencyId,
            Map.of("/name", "Cat Freq " + tag, "/active", true, "/version", 0)));
    reads.add(
        read(
            "frequency type by id",
            () -> frequencyTypeController.getFrequencyType(inactiveFrequencyId),
            inactiveFrequencyId,
            Map.of("/name", "Cat Idle " + tag, "/active", false)));
    return reads;
  }

  private Map<String, Object> materialPin() {
    Map<String, Object> pin = new LinkedHashMap<>();
    pin.put("/name", "Cat Raw " + tag);
    pin.put("/type", "RAW");
    pin.put("/quantityType", "SCU");
    pin.put("/category/name", "Cat Ores " + tag);
    pin.put("/refinedMaterial/name", "Cat Refined " + tag);
    pin.put("/refinedMaterial/category/name", "Cat Ores " + tag);
    pin.put("/version", 0);
    return pin;
  }

  private List<Eviction> evictions() {
    List<Eviction> evictions = new ArrayList<>();
    evictions.add(
        new Eviction(
            "cities",
            () -> cityController.getCity(cityId),
            () -> cityController.setLoadingDockOverride(cityId, false),
            node ->
                pinFields(
                    node, Map.of("/hasLoadingDock", false, "/hasLoadingDockOverridden", true))));
    evictions.add(
        new Eviction(
            "outposts",
            () -> outpostController.getOutpost(outpostId),
            () -> outpostController.setLoadingDockOverride(outpostId, true),
            node ->
                pinFields(
                    node, Map.of("/hasLoadingDock", true, "/hasLoadingDockOverridden", true))));
    evictions.add(
        new Eviction(
            "pois",
            () -> poiController.getPoi(poiId),
            () -> poiController.setLoadingDockOverride(poiId, false),
            node ->
                pinFields(
                    node, Map.of("/hasLoadingDock", false, "/hasLoadingDockOverridden", true))));
    evictions.add(
        new Eviction(
            "terminals",
            () -> terminalController.getTerminal(terminalId),
            () -> terminalController.setAutoLoadOverride(terminalId, true),
            node -> pinFields(node, Map.of("/isAutoLoad", true, "/isAutoLoadOverridden", true))));
    evictions.add(
        new Eviction(
            "manufacturers",
            () -> manufacturerController.getManufacturer(manufacturerId),
            () -> manufacturerController.updateManufacturerVisibility(manufacturerId, true),
            node -> pinFields(node, Map.of("/hidden", true))));
    evictions.add(
        new Eviction(
            "ship types",
            () -> shipTypeController.getShipType(shipTypeId),
            () -> shipTypeController.updateShipTypeVisibility(shipTypeId, true),
            node -> pinFields(node, Map.of("/hidden", true))));
    evictions.add(
        new Eviction(
            "locations",
            () -> locationController.getLocation(locationId),
            () ->
                locationController.updateLocation(
                    locationId,
                    new LocationDto(
                        locationId, "Cat Location Renamed " + tag, "changed", false, false, 0L)),
            node ->
                pinFields(
                    node,
                    Map.of(
                        "/name",
                        "Cat Location Renamed " + tag,
                        "/description",
                        "changed",
                        "/homeLocation",
                        false,
                        "/version",
                        1))));
    evictions.add(
        new Eviction(
            "star systems",
            () -> starSystemController.getStarSystem(starSystemId),
            () ->
                starSystemController.updateStarSystem(
                    starSystemId,
                    new StarSystemDto(
                        starSystemId,
                        null,
                        "Cat System Renamed " + tag,
                        "changed",
                        null,
                        null,
                        null,
                        null,
                        0L)),
            node -> pinFields(node, Map.of("/name", "Cat System Renamed " + tag))));
    evictions.add(
        new Eviction(
            "refining methods",
            () -> refiningMethodController.getRefiningMethod(refiningMethodId),
            this::renameRefiningMethodWithoutActor,
            node -> pinFields(node, Map.of("/name", "Cat Method Renamed " + tag))));
    evictions.add(
        new Eviction(
            "material categories",
            () -> materialCategoryController.getById(categoryId),
            () ->
                materialCategoryController.update(
                    categoryId, new MaterialCategoryDto(categoryId, "Cat Ores Renamed " + tag, 0L)),
            node -> pinFields(node, Map.of("/name", "Cat Ores Renamed " + tag))));
    evictions.add(
        new Eviction(
            "materials",
            () -> materialController.getMaterial(rawMaterialId),
            () ->
                materialController.updateMaterial(
                    rawMaterialId,
                    new MaterialDto(
                        rawMaterialId,
                        "Cat Raw Renamed " + tag,
                        "RAW",
                        "SCU",
                        "changed",
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        null,
                        true,
                        null)),
            node -> pinFields(node, Map.of("/name", "Cat Raw Renamed " + tag))));
    evictions.add(
        new Eviction(
            "job types",
            () -> jobTypeController.getAllJobTypes(null, 0, BIG_PAGE, null, true),
            () -> jobTypeController.activateJobType(inactiveJobTypeId),
            node -> pinRow(node, inactiveJobTypeId, Map.of("/active", true))));
    evictions.add(
        new Eviction(
            "frequency types",
            () -> frequencyTypeController.getFrequencyType(inactiveFrequencyId),
            () -> frequencyTypeController.activateFrequencyType(inactiveFrequencyId),
            node -> pinFields(node, Map.of("/active", true))));
    return evictions;
  }

  private void renameRefiningMethodWithoutActor() {
    var admin = SecurityContextHolder.getContext().getAuthentication();
    SecurityContextHolder.clearContext();
    try {
      RefiningMethod details = new RefiningMethod();
      details.setName("Cat Method Renamed " + tag);
      details.setDescription("changed");
      refiningMethodService.updateRefiningMethod(refiningMethodId, details);
    } finally {
      SecurityContextHolder.getContext().setAuthentication(admin);
    }
  }

  private Read read(String label, Supplier<Object> call, UUID id, Map<String, Object> expected) {
    return new Read(label, call, node -> pinRow(node, id, expected));
  }

  private void pinRow(JsonNode result, UUID id, Map<String, Object> expected) {
    pinFields(rowOf(result, id), expected);
  }

  private void pinFields(JsonNode row, Map<String, Object> expected) {
    expected.forEach(
        (pointer, value) ->
            assertThat(row.at(pointer))
                .as("%s of %s", pointer, row)
                .isEqualTo(mapper.valueToTree(value)));
  }

  private JsonNode rowOf(JsonNode result, UUID id) {
    JsonNode rows = result.has("content") ? result.get("content") : result;
    if (rows.isObject()) {
      assertThat(rows.get("id").asString()).isEqualTo(id.toString());
      return rows;
    }
    for (JsonNode row : rows) {
      if (id.toString().equals(row.get("id").asString())) {
        return row;
      }
    }
    throw new AssertionError("row " + id + " is not in " + result.toString().substring(0, 300));
  }

  private JsonNode json(Object value) {
    return mapper.readTree(mapper.writeValueAsString(value));
  }

  private void clearCaches() {
    cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
  }

  private Statistics statistics() {
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    return stats;
  }

  private void seed() {
    StarSystem starSystem = new StarSystem();
    starSystem.setName("Cat System " + tag);
    starSystem.setDescription("cat description");
    starSystem.setFactionName("Cat Faction");
    starSystemId = starSystemRepository.save(starSystem).getId();
    cleanup.push(() -> starSystemRepository.deleteById(starSystemId));

    City city = new City();
    city.setName("Cat City " + tag);
    city.setStarSystemName("Cat Sys");
    city.setPlanetName("Cat Planet");
    city.setHasLoadingDock(true);
    city.setHasRefineryTerminal(true);
    cityId = cityRepository.save(city).getId();
    cleanup.push(() -> cityRepository.deleteById(cityId));

    Location location = new Location();
    location.setName("Cat Location " + tag);
    location.setDescription("cat location");
    location.setHomeLocation(true);
    location.setCity(city);
    locationId = locationRepository.save(location).getId();
    cleanup.push(() -> locationRepository.deleteById(locationId));

    Outpost outpost = new Outpost();
    outpost.setName("Cat Outpost " + tag);
    outpost.setHasLoadingDock(false);
    outpostId = outpostRepository.save(outpost).getId();
    cleanup.push(() -> outpostRepository.deleteById(outpostId));

    Poi poi = new Poi();
    poi.setName("Cat Poi " + tag);
    poi.setHasLoadingDock(true);
    poiId = poiRepository.save(poi).getId();
    cleanup.push(() -> poiRepository.deleteById(poiId));

    Terminal terminal = new Terminal();
    terminal.setName("Cat Terminal " + tag);
    terminal.setNickname("CT");
    terminal.setCityName("Cat City");
    terminal.setHasLoadingDock(true);
    terminal.setIsAutoLoad(false);
    terminalId = terminalRepository.save(terminal).getId();
    cleanup.push(() -> terminalRepository.deleteById(terminalId));

    RefiningMethod method = new RefiningMethod();
    method.setName("Cat Method " + tag);
    method.setCode("CM" + tag);
    method.setRatingYield(3);
    method.setRatingCost(4);
    method.setRatingSpeed(5);
    refiningMethodId = refiningMethodRepository.save(method).getId();
    cleanup.push(() -> refiningMethodRepository.deleteById(refiningMethodId));

    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName("Cat Works " + tag);
    manufacturer.setAbbreviation("C" + tag);
    manufacturerId = manufacturerRepository.save(manufacturer).getId();
    ShipType shipType = new ShipType();
    shipType.setName("Cat Hull " + tag);
    shipType.setScu(42);
    shipType.setManufacturer(manufacturer);
    shipTypeId = shipTypeRepository.save(shipType).getId();
    cleanup.push(() -> manufacturerRepository.deleteById(manufacturerId));
    cleanup.push(() -> shipTypeRepository.deleteById(shipTypeId));

    MaterialCategory category = new MaterialCategory();
    category.setName("Cat Ores " + tag);
    categoryId = materialCategoryRepository.save(category).getId();
    cleanup.push(() -> materialCategoryRepository.deleteById(categoryId));
    Material refined = new Material();
    refined.setName("Cat Refined " + tag);
    refined.setType(MaterialType.REFINED);
    refined.setQuantityType(QuantityType.SCU);
    refined.setCategory(category);
    refinedMaterialId = materialRepository.save(refined).getId();
    Material raw = new Material();
    raw.setName("Cat Raw " + tag);
    raw.setType(MaterialType.RAW);
    raw.setQuantityType(QuantityType.SCU);
    raw.setCategory(category);
    raw.setRefinedMaterial(refined);
    rawMaterialId = materialRepository.save(raw).getId();
    cleanup.push(() -> materialRepository.deleteById(refinedMaterialId));
    cleanup.push(() -> materialRepository.deleteById(rawMaterialId));

    JobType parent = new JobType();
    parent.setName("Cat Parent " + tag);
    parent.setArchetype(JobTypeArchetype.CREW);
    parentJobTypeId = jobTypeRepository.save(parent).getId();
    JobType child = new JobType();
    child.setName("Cat Child " + tag);
    child.setArchetype(JobTypeArchetype.CREW);
    child.setParent(parent);
    childJobTypeId = jobTypeRepository.save(child).getId();
    JobType inactive = new JobType();
    inactive.setName("Cat Inactive " + tag);
    inactive.setArchetype(JobTypeArchetype.CREW);
    inactive.setActive(false);
    inactiveJobTypeId = jobTypeRepository.save(inactive).getId();
    cleanup.push(() -> jobTypeRepository.deleteById(parentJobTypeId));
    cleanup.push(() -> jobTypeRepository.deleteById(childJobTypeId));
    cleanup.push(() -> jobTypeRepository.deleteById(inactiveJobTypeId));

    FrequencyType active = new FrequencyType();
    active.setName("Cat Freq " + tag);
    activeFrequencyId = frequencyTypeRepository.save(active).getId();
    FrequencyType idle = new FrequencyType();
    idle.setName("Cat Idle " + tag);
    idle.setActive(false);
    inactiveFrequencyId = frequencyTypeRepository.save(idle).getId();
    cleanup.push(() -> frequencyTypeRepository.deleteById(activeFrequencyId));
    cleanup.push(() -> frequencyTypeRepository.deleteById(inactiveFrequencyId));
  }
}
