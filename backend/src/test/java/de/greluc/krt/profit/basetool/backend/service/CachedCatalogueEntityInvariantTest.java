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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.architecture.fixture.CachedEntityFixtures.PlantedCityCommands;
import de.greluc.krt.profit.basetool.backend.architecture.fixture.CachedEntityFixtures.PlantedCityQueries;
import de.greluc.krt.profit.basetool.backend.model.AbstractEntity;
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
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.StarSystem;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.model.dto.JobTypeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialCreateDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.backend.repository.CityRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.repository.OutpostRepository;
import de.greluc.krt.profit.basetool.backend.repository.PoiRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.hibernate.Hibernate;
import org.hibernate.proxy.HibernateProxy;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.CrudRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ClassUtils;

/**
 * Holds the cached-entity invariant of REQ-DATA-022 for every service with a {@code @Cacheable}
 * method: no mutator edits an instance a cache has handed out.
 *
 * <p>Each case reads through the cache proxy, runs one mutator through its proxy, and compares
 * every field of every instance the read returned. The test runs without a surrounding transaction,
 * so the cached instances are detached exactly as in production. The case list must name every
 * mutating method of those services, so a new mutator without a case fails. The harness is proven
 * able to fail on the planted split of {@code CachedEntityFixtures}.
 */
@SpringBootTest
class CachedCatalogueEntityInvariantTest {

  private static final Pageable ALL = PageRequest.of(0, 500);

  /** Declares the planted query/command split as beans of this test's context only. */
  @TestConfiguration
  static class PlantedSplit {

    /**
     * The cached query half.
     *
     * @param cityRepository the repository behind the cache
     * @return the bean
     */
    @Bean
    PlantedCityQueries plantedCityQueries(CityRepository cityRepository) {
      return new PlantedCityQueries(cityRepository);
    }

    /**
     * The command half that edits the cached instance.
     *
     * @param queries the cached query half
     * @param cityRepository the repository the command saves through
     * @return the bean
     */
    @Bean
    PlantedCityCommands plantedCityCommands(
        PlantedCityQueries queries, CityRepository cityRepository) {
      return new PlantedCityCommands(queries, cityRepository);
    }
  }

  /**
   * One mutator under test.
   *
   * @param read the cached read whose instances must survive
   * @param mutation the mutator call
   */
  private record Case(@NotNull Supplier<?> read, @NotNull Runnable mutation) {}

  @Autowired private ApplicationContext context;
  @Autowired private CacheManager cacheManager;
  @Autowired private DataSource dataSource;
  @Autowired private CityService cityService;
  @Autowired private FrequencyTypeService frequencyTypeService;
  @Autowired private JobTypeService jobTypeService;
  @Autowired private LocationService locationService;
  @Autowired private ManufacturerService manufacturerService;
  @Autowired private MaterialCategoryService materialCategoryService;
  @Autowired private MaterialService materialService;
  @Autowired private OutpostService outpostService;
  @Autowired private PoiService poiService;
  @Autowired private RefiningMethodService refiningMethodService;
  @Autowired private RoleService roleService;
  @Autowired private ShipTypeService shipTypeService;
  @Autowired private SquadronService squadronService;
  @Autowired private StarSystemService starSystemService;
  @Autowired private TerminalService terminalService;
  @Autowired private CityRepository cityRepository;
  @Autowired private OutpostRepository outpostRepository;
  @Autowired private PoiRepository poiRepository;
  @Autowired private TerminalRepository terminalRepository;
  @Autowired private ManufacturerRepository manufacturerRepository;
  @Autowired private ShipTypeRepository shipTypeRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private PlantedCityQueries plantedCityQueries;
  @Autowired private PlantedCityCommands plantedCityCommands;

  private final Deque<Runnable> cleanup = new ArrayDeque<>();

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  private final Instant startedAt = Instant.now();

  @AfterEach
  void removeFixtures() {
    while (!cleanup.isEmpty()) {
      cleanup.pop().run();
    }
    new JdbcTemplate(dataSource)
        .update(
            """
            DELETE FROM audit_event WHERE actor_user_id IS NULL AND occurred_at >= ?
              AND event_type IN ('REFINERY_METHOD_CREATED', 'REFINERY_METHOD_UPDATED',
                                 'REFINERY_METHOD_DELETED', 'ROLE_PERMISSIONS_CHANGED')
            """,
            Timestamp.from(startedAt));
    clearCaches();
  }

  @Test
  @DisplayName("no mutator of a cached service edits an instance the cache handed out")
  void noMutatorEditsACachedInstance() {
    Map<String, Supplier<Case>> cases = cases();
    assertThat(new TreeSet<>(cases.keySet()))
        .as(
            """
            Mutating methods of services with a @Cacheable method, against the cases of this \
            test. Add a case for every new mutator: read through the cache, then mutate.\
            """)
        .isEqualTo(mutatorsOfCachedServices());

    Map<String, List<String>> violations = new TreeMap<>();
    cases.forEach(
        (name, setup) -> {
          List<String> changes = changesMadeBy(setup.get());
          if (!changes.isEmpty()) {
            violations.put(name, changes);
          }
        });
    assertThat(violations)
        .as(
            """
            Mutators that edited an instance a cache had handed out. Every reader shares that \
            instance, and a failed write leaves it edited in the cache. Load the entity from the \
            repository inside the write, never through a cached getter of another bean.\
            """)
        .isEmpty();
  }

  @Test
  @DisplayName("proof: a command editing another bean's cached instance is reported")
  void aPlantedSplitEditingTheCachedInstanceIsReported() {
    City city = new City();
    city.setName("G19 planted " + tag);
    city.setHasLoadingDock(false);
    UUID id = track(cityRepository, cityRepository.save(city)).getId();

    List<String> changes =
        changesMadeBy(
            new Case(
                () -> plantedCityQueries.find(id),
                () -> plantedCityCommands.toggleLoadingDock(id)));

    assertThat(changes).anySatisfy(change -> assertThat(change).contains("hasLoadingDock"));
  }

  private Map<String, Supplier<Case>> cases() {
    Map<String, Supplier<Case>> cases = new LinkedHashMap<>();
    cases.put(
        "CityService#setLoadingDockOverride",
        () -> {
          UUID id = city();
          return new Case(
              () -> cityService.getCity(id), () -> cityService.setLoadingDockOverride(id, true));
        });
    cases.put(
        "CityService#clearLoadingDockOverride",
        () -> {
          UUID id = city();
          return new Case(
              () -> cityService.getCity(id), () -> cityService.clearLoadingDockOverride(id));
        });
    cases.put(
        "OutpostService#setLoadingDockOverride",
        () -> {
          UUID id = outpost();
          return new Case(
              () -> outpostService.getOutpost(id),
              () -> outpostService.setLoadingDockOverride(id, true));
        });
    cases.put(
        "OutpostService#clearLoadingDockOverride",
        () -> {
          UUID id = outpost();
          return new Case(
              () -> outpostService.getOutpost(id),
              () -> outpostService.clearLoadingDockOverride(id));
        });
    cases.put(
        "PoiService#setLoadingDockOverride",
        () -> {
          UUID id = poi();
          return new Case(
              () -> poiService.getPoi(id), () -> poiService.setLoadingDockOverride(id, true));
        });
    cases.put(
        "PoiService#clearLoadingDockOverride",
        () -> {
          UUID id = poi();
          return new Case(
              () -> poiService.getPoi(id), () -> poiService.clearLoadingDockOverride(id));
        });
    terminalCases(cases);
    cases.put(
        "ManufacturerService#updateManufacturerVisibility",
        () -> {
          UUID id = manufacturer();
          return new Case(
              () -> manufacturerService.getManufacturer(id),
              () -> manufacturerService.updateManufacturerVisibility(id, true));
        });
    cases.put(
        "ShipTypeService#updateShipTypeVisibility",
        () -> {
          UUID id = shipType();
          return new Case(
              () -> shipTypeService.getShipType(id),
              () -> shipTypeService.updateShipTypeVisibility(id, true));
        });
    frequencyTypeCases(cases);
    jobTypeCases(cases);
    locationCases(cases);
    materialCategoryCases(cases);
    materialCases(cases);
    refiningMethodCases(cases);
    roleCases(cases);
    squadronCases(cases);
    starSystemCases(cases);
    return cases;
  }

  private void terminalCases(Map<String, Supplier<Case>> cases) {
    cases.put(
        "TerminalService#updateTerminalVisibility",
        () -> {
          UUID id = terminal();
          return new Case(
              () -> terminalService.getTerminal(id),
              () -> terminalService.updateTerminalVisibility(id, true));
        });
    cases.put(
        "TerminalService#setLoadingDockOverride",
        () -> {
          UUID id = terminal();
          return new Case(
              () -> terminalService.getTerminal(id),
              () -> terminalService.setLoadingDockOverride(id, true));
        });
    cases.put(
        "TerminalService#clearLoadingDockOverride",
        () -> {
          UUID id = terminal();
          return new Case(
              () -> terminalService.getTerminal(id),
              () -> terminalService.clearLoadingDockOverride(id));
        });
    cases.put(
        "TerminalService#setAutoLoadOverride",
        () -> {
          UUID id = terminal();
          return new Case(
              () -> terminalService.getTerminal(id),
              () -> terminalService.setAutoLoadOverride(id, true));
        });
    cases.put(
        "TerminalService#clearAutoLoadOverride",
        () -> {
          UUID id = terminal();
          return new Case(
              () -> terminalService.getTerminal(id),
              () -> terminalService.clearAutoLoadOverride(id));
        });
  }

  private void frequencyTypeCases(Map<String, Supplier<Case>> cases) {
    cases.put(
        "FrequencyTypeService#createFrequencyType",
        () -> {
          frequencyType();
          return new Case(
              () -> frequencyTypeService.getAllFrequencyTypes(null, ALL),
              () -> trackFrequencyType(frequencyTypeService.createFrequencyType(newFrequency())));
        });
    cases.put(
        "FrequencyTypeService#updateFrequencyType",
        () -> {
          UUID id = frequencyType();
          FrequencyType details = newFrequency();
          details.setActive(false);
          return new Case(
              () -> frequencyTypeService.getFrequencyType(id),
              () -> frequencyTypeService.updateFrequencyType(id, details));
        });
    cases.put(
        "FrequencyTypeService#deleteFrequencyType",
        () -> {
          UUID id = frequencyType();
          return new Case(
              () -> frequencyTypeService.getFrequencyType(id),
              () -> frequencyTypeService.deleteFrequencyType(id));
        });
    cases.put(
        "FrequencyTypeService#activateFrequencyType",
        () -> {
          UUID id = frequencyType();
          return new Case(
              () -> frequencyTypeService.getFrequencyType(id),
              () -> frequencyTypeService.activateFrequencyType(id));
        });
    cases.put(
        "FrequencyTypeService#reorderFrequencyTypes",
        () -> {
          UUID first = frequencyType();
          UUID second = frequencyType();
          return new Case(
              () -> frequencyTypeService.getAllFrequencyTypes(null, ALL),
              () -> frequencyTypeService.reorderFrequencyTypes(List.of(second, first)));
        });
  }

  private void jobTypeCases(Map<String, Supplier<Case>> cases) {
    cases.put(
        "JobTypeService#createJobType",
        () -> {
          jobType();
          return new Case(
              () -> jobTypeService.getJobTypes(null),
              () -> trackJobType(jobTypeService.createJobType(newJobType())));
        });
    cases.put(
        "JobTypeService#updateJobType",
        () -> {
          UUID id = jobType();
          return new Case(
              () -> jobTypeService.getJobTypes(null),
              () ->
                  jobTypeService.updateJobType(
                      id,
                      new JobTypeDto(
                          id,
                          "G19 job renamed " + tag,
                          "changed",
                          JobTypeArchetype.CREW,
                          null,
                          true,
                          true,
                          false,
                          versionOf(jobTypeService.getJobTypes(null), id))));
        });
    cases.put(
        "JobTypeService#deleteJobType",
        () -> {
          UUID id = jobType();
          return new Case(
              () -> jobTypeService.getJobTypes(null), () -> jobTypeService.deleteJobType(id));
        });
    cases.put(
        "JobTypeService#activateJobType",
        () -> {
          UUID id = jobType();
          return new Case(
              () -> jobTypeService.getJobTypes(JobTypeArchetype.CREW),
              () -> jobTypeService.activateJobType(id));
        });
  }

  private void locationCases(Map<String, Supplier<Case>> cases) {
    cases.put(
        "LocationService#createLocation",
        () -> {
          location();
          return new Case(
              () -> locationService.getAllLocations(ALL, true),
              () -> trackLocation(locationService.createLocation(newLocation())));
        });
    cases.put(
        "LocationService#updateLocation",
        () -> {
          UUID id = location();
          return new Case(
              () -> locationService.getLocation(id),
              () ->
                  locationService.updateLocation(
                      id,
                      new LocationDto(
                          id,
                          "G19 location renamed " + tag,
                          "changed",
                          true,
                          true,
                          locationService.getLocation(id).getVersion())));
        });
    cases.put(
        "LocationService#deleteLocation",
        () -> {
          UUID id = location();
          return new Case(
              () -> locationService.getLocation(id), () -> locationService.deleteLocation(id));
        });
  }

  private void materialCategoryCases(Map<String, Supplier<Case>> cases) {
    cases.put(
        "MaterialCategoryService#create",
        () -> {
          materialCategory();
          return new Case(
              materialCategoryService::findAll,
              () -> trackMaterialCategory(materialCategoryService.create(newMaterialCategory())));
        });
    cases.put(
        "MaterialCategoryService#update",
        () -> {
          UUID id = materialCategory();
          return new Case(
              () -> materialCategoryService.findById(id),
              () -> materialCategoryService.update(id, newMaterialCategory()));
        });
    cases.put(
        "MaterialCategoryService#delete",
        () -> {
          UUID id = materialCategory();
          return new Case(
              () -> materialCategoryService.findById(id), () -> materialCategoryService.delete(id));
        });
  }

  private void materialCases(Map<String, Supplier<Case>> cases) {
    cases.put(
        "MaterialService#createMaterial",
        () -> {
          material();
          return new Case(
              () -> materialService.getAllMaterials(ALL),
              () -> trackMaterial(materialService.createMaterial(newMaterial())));
        });
    cases.put(
        "MaterialService#updateMaterial",
        () -> {
          UUID id = material();
          return new Case(
              () -> materialService.getMaterial(id),
              () -> {
                Material details = new Material();
                details.setName("G19 material renamed " + tag);
                details.setType(MaterialType.REFINED);
                details.setQuantityType(QuantityType.SCU);
                details.setDescription("changed");
                details.setIsManualRawMaterial(false);
                details.setIsJobOrder(true);
                details.setIsVisible(false);
                materialService.updateMaterial(id, details);
              });
        });
    cases.put(
        "MaterialService#deleteMaterial",
        () -> {
          UUID id = material();
          return new Case(
              () -> materialService.getMaterial(id), () -> materialService.deleteMaterial(id));
        });
  }

  private void refiningMethodCases(Map<String, Supplier<Case>> cases) {
    cases.put(
        "RefiningMethodService#createRefiningMethod",
        () -> {
          refiningMethod();
          return new Case(
              () -> refiningMethodService.getAllRefiningMethods(ALL),
              () ->
                  trackRefiningMethod(
                      refiningMethodService.createRefiningMethod(newRefiningMethod())));
        });
    cases.put(
        "RefiningMethodService#updateRefiningMethod",
        () -> {
          UUID id = refiningMethod();
          return new Case(
              () -> refiningMethodService.getRefiningMethod(id),
              () -> refiningMethodService.updateRefiningMethod(id, newRefiningMethod()));
        });
    cases.put(
        "RefiningMethodService#deleteRefiningMethod",
        () -> {
          UUID id = refiningMethod();
          return new Case(
              () -> refiningMethodService.getRefiningMethod(id),
              () -> refiningMethodService.deleteRefiningMethod(id));
        });
  }

  private void roleCases(Map<String, Supplier<Case>> cases) {
    cases.put(
        "RoleService#updatePermissions",
        () -> {
          String name = role();
          return new Case(
              () -> roleService.getAllRoles(ALL),
              () -> roleService.updatePermissions(name, new HashSet<>(Set.of("g19.permission"))));
        });
    cases.put(
        "RoleService#updateRoleDescription",
        () -> {
          String name = role();
          return new Case(
              () -> roleService.getAllRoles(ALL),
              () -> roleService.updateRoleDescription(name, "changed"));
        });
  }

  private void squadronCases(Map<String, Supplier<Case>> cases) {
    cases.put(
        "SquadronService#createSquadron",
        () -> {
          squadron();
          return new Case(
              () -> squadronService.getAllSquadrons(true),
              () -> trackSquadron(squadronService.createSquadron(newSquadron())));
        });
    cases.put(
        "SquadronService#updateSquadron",
        () -> {
          UUID id = squadron();
          return new Case(
              () -> squadronService.getAllSquadrons(true),
              () ->
                  squadronService.updateSquadron(
                      id,
                      new SquadronDto(
                          id,
                          "G19 squadron renamed " + tag,
                          "R" + tag.substring(0, 4),
                          "changed",
                          true,
                          false,
                          false,
                          versionOf(squadronService.getAllSquadrons(true), id))));
        });
    cases.put(
        "SquadronService#deleteSquadron",
        () -> {
          UUID id = squadron();
          return new Case(
              () -> squadronService.getAllSquadrons(true),
              () -> squadronService.deleteSquadron(id));
        });
    cases.put(
        "SquadronService#activateSquadron",
        () -> {
          UUID id = squadron();
          return new Case(
              () -> squadronService.getAllSquadrons(true),
              () -> squadronService.activateSquadron(id));
        });
    cases.put(
        "SquadronService#setPromotionEnabled",
        () -> {
          UUID id = squadron();
          return new Case(
              () -> squadronService.getAllSquadrons(true),
              () -> squadronService.setPromotionEnabled(id, true));
        });
    cases.put(
        "SquadronService#setProfitEligible",
        () -> {
          UUID id = squadron();
          return new Case(
              () -> squadronService.getAllSquadrons(true),
              () -> squadronService.setProfitEligible(id, true));
        });
  }

  private void starSystemCases(Map<String, Supplier<Case>> cases) {
    cases.put(
        "StarSystemService#createStarSystem",
        () -> {
          starSystem();
          return new Case(
              () -> starSystemService.getAllStarSystems(ALL),
              () -> trackStarSystem(starSystemService.createStarSystem(newStarSystem())));
        });
    cases.put(
        "StarSystemService#updateStarSystem",
        () -> {
          UUID id = starSystem();
          return new Case(
              () -> starSystemService.getStarSystem(id),
              () -> starSystemService.updateStarSystem(id, newStarSystem()));
        });
    cases.put(
        "StarSystemService#deleteStarSystem",
        () -> {
          UUID id = starSystem();
          return new Case(
              () -> starSystemService.getStarSystem(id),
              () -> starSystemService.deleteStarSystem(id));
        });
  }

  /**
   * Reads through the cache, runs the mutation, and lists every field of a returned instance that
   * changed, as {@code Type#id.field: before -> after}.
   *
   * @param testCase the read and the mutation
   * @return the changes; empty when the cached instances survived
   */
  private List<String> changesMadeBy(Case testCase) {
    clearCaches();
    Object cached = testCase.read().get();
    List<Object> instances = entitiesIn(cached);
    if (instances.isEmpty()) {
      return List.of("the cached read returned no entity to watch");
    }
    List<Map<String, Object>> before = instances.stream().map(this::state).toList();
    testCase.mutation().run();
    List<String> changes = new ArrayList<>();
    for (int i = 0; i < instances.size(); i++) {
      Map<String, Object> after = state(instances.get(i));
      Map<String, Object> was = before.get(i);
      String label = instances.get(i).getClass().getSimpleName() + "#" + idOf(instances.get(i));
      for (String field : was.keySet()) {
        if (!Objects.equals(was.get(field), after.get(field))) {
          changes.add(label + "." + field + ": " + was.get(field) + " -> " + after.get(field));
        }
      }
    }
    return changes;
  }

  private static List<Object> entitiesIn(Object cached) {
    List<Object> found = new ArrayList<>();
    if (cached instanceof Page<?> page) {
      page.getContent().forEach(e -> collect(e, found));
    } else if (cached instanceof Collection<?> collection) {
      collection.forEach(e -> collect(e, found));
    } else if (cached instanceof Optional<?> optional) {
      optional.ifPresent(e -> collect(e, found));
    } else {
      collect(cached, found);
    }
    return found;
  }

  private static void collect(Object candidate, List<Object> found) {
    if (candidate instanceof AbstractEntity<?>) {
      found.add(candidate);
    }
  }

  private Map<String, Object> state(Object entity) {
    Map<String, Object> state = new TreeMap<>();
    for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
      for (Field field : c.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
          continue;
        }
        field.setAccessible(true);
        try {
          state.put(c.getSimpleName() + "." + field.getName(), describe(field.get(entity)));
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(field.toString(), e);
        }
      }
    }
    return state;
  }

  private static Object describe(Object value) {
    if (value == null) {
      return null;
    }
    if (!Hibernate.isInitialized(value)) {
      return "<not loaded>";
    }
    if (value instanceof HibernateProxy proxy) {
      return "proxy#" + proxy.getHibernateLazyInitializer().getIdentifier();
    }
    if (value instanceof AbstractEntity<?> entity) {
      return entity.getClass().getSimpleName() + "#" + entity.getId();
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(CachedCatalogueEntityInvariantTest::describe).toList();
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> described = new TreeMap<>();
      map.forEach((k, v) -> described.put(String.valueOf(k), describe(v)));
      return described;
    }
    return value;
  }

  private static Object idOf(Object entity) {
    return ((AbstractEntity<?>) entity).getId();
  }

  private static Long versionOf(Collection<? extends AbstractEntity<UUID>> entities, UUID id) {
    return entities.stream()
        .filter(e -> id.equals(e.getId()))
        .findFirst()
        .map(AbstractEntity::getVersion)
        .orElseThrow();
  }

  private Set<String> mutatorsOfCachedServices() {
    Set<String> mutators = new TreeSet<>();
    for (String name : context.getBeanDefinitionNames()) {
      Class<?> type = context.getType(name);
      if (type == null) {
        continue;
      }
      Class<?> target = ClassUtils.getUserClass(type);
      if (!target.getPackageName().startsWith("de.greluc.krt.profit.basetool.backend.service")
          || !hasCacheableMethod(target)) {
        continue;
      }
      for (Method method : target.getDeclaredMethods()) {
        if (Modifier.isPublic(method.getModifiers()) && isMutator(method, target)) {
          mutators.add(target.getSimpleName() + "#" + method.getName());
        }
      }
    }
    return mutators;
  }

  private static boolean hasCacheableMethod(Class<?> type) {
    for (Method method : type.getDeclaredMethods()) {
      if (AnnotatedElementUtils.hasAnnotation(method, Cacheable.class)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isMutator(Method method, Class<?> type) {
    if (AnnotatedElementUtils.hasAnnotation(method, CacheEvict.class)
        || AnnotatedElementUtils.hasAnnotation(method, CachePut.class)
        || AnnotatedElementUtils.hasAnnotation(method, Caching.class)) {
      return true;
    }
    Transactional transactional =
        Optional.ofNullable(AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class))
            .orElse(AnnotatedElementUtils.findMergedAnnotation(type, Transactional.class));
    return transactional != null
        && !transactional.readOnly()
        && !AnnotatedElementUtils.hasAnnotation(method, Cacheable.class);
  }

  private void clearCaches() {
    for (String name : cacheManager.getCacheNames()) {
      Objects.requireNonNull(cacheManager.getCache(name)).clear();
    }
  }

  private <E extends AbstractEntity<UUID>> E track(CrudRepository<E, UUID> repository, E entity) {
    UUID id = entity.getId();
    cleanup.push(() -> repository.findById(id).ifPresent(repository::delete));
    return entity;
  }

  private UUID city() {
    City city = new City();
    city.setName("G19 city " + tag);
    return track(cityRepository, cityRepository.save(city)).getId();
  }

  private UUID outpost() {
    Outpost outpost = new Outpost();
    outpost.setName("G19 outpost " + tag);
    return track(outpostRepository, outpostRepository.save(outpost)).getId();
  }

  private UUID poi() {
    Poi poi = new Poi();
    poi.setName("G19 poi " + tag);
    return track(poiRepository, poiRepository.save(poi)).getId();
  }

  private UUID terminal() {
    Terminal terminal = new Terminal();
    terminal.setName("G19 terminal " + tag);
    return track(terminalRepository, terminalRepository.save(terminal)).getId();
  }

  private UUID manufacturer() {
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName("G19 manufacturer " + tag);
    manufacturer.setAbbreviation("G" + tag.substring(0, 4));
    return track(manufacturerRepository, manufacturerRepository.save(manufacturer)).getId();
  }

  private UUID shipType() {
    ShipType shipType = new ShipType();
    shipType.setName("G19 ship type " + tag);
    return track(shipTypeRepository, shipTypeRepository.save(shipType)).getId();
  }

  private FrequencyType newFrequency() {
    FrequencyType frequency = new FrequencyType();
    frequency.setName("G19 frequency " + UUID.randomUUID());
    frequency.setActive(true);
    return frequency;
  }

  private UUID frequencyType() {
    return trackFrequencyType(frequencyTypeService.createFrequencyType(newFrequency())).getId();
  }

  private FrequencyType trackFrequencyType(FrequencyType frequency) {
    cleanup.push(() -> deleteRow("frequency_type", frequency.getId()));
    return frequency;
  }

  private JobType newJobType() {
    JobType jobType = new JobType();
    jobType.setName("G19 job " + UUID.randomUUID());
    jobType.setArchetype(JobTypeArchetype.CREW);
    return jobType;
  }

  private UUID jobType() {
    return trackJobType(jobTypeService.createJobType(newJobType())).getId();
  }

  private JobType trackJobType(JobType jobType) {
    cleanup.push(() -> deleteRow("job_type", jobType.getId()));
    return jobType;
  }

  private Location newLocation() {
    Location location = new Location();
    location.setName("G19 location " + UUID.randomUUID());
    return location;
  }

  private UUID location() {
    return trackLocation(locationService.createLocation(newLocation())).getId();
  }

  private Location trackLocation(Location location) {
    cleanup.push(() -> deleteRow("location", location.getId()));
    return location;
  }

  private MaterialCategory newMaterialCategory() {
    MaterialCategory category = new MaterialCategory();
    category.setName("G19 category " + UUID.randomUUID());
    return category;
  }

  private UUID materialCategory() {
    return trackMaterialCategory(materialCategoryService.create(newMaterialCategory())).getId();
  }

  private MaterialCategory trackMaterialCategory(MaterialCategory category) {
    cleanup.push(() -> deleteRow("material_category", category.getId()));
    return category;
  }

  private MaterialCreateDto newMaterial() {
    return new MaterialCreateDto(
        "G19 material " + UUID.randomUUID(),
        "RAW",
        "SCU",
        null,
        null,
        null,
        false,
        false,
        false,
        false,
        false);
  }

  private UUID material() {
    return trackMaterial(materialService.createMaterial(newMaterial())).getId();
  }

  private Material trackMaterial(Material material) {
    cleanup.push(() -> deleteRow("material", material.getId()));
    return material;
  }

  private RefiningMethod newRefiningMethod() {
    RefiningMethod method = new RefiningMethod();
    method.setName("G19 method " + UUID.randomUUID());
    return method;
  }

  private UUID refiningMethod() {
    return trackRefiningMethod(refiningMethodService.createRefiningMethod(newRefiningMethod()))
        .getId();
  }

  private RefiningMethod trackRefiningMethod(RefiningMethod method) {
    cleanup.push(() -> deleteRow("refining_method", method.getId()));
    return method;
  }

  private String role() {
    Role role = new Role();
    role.setName("G19_ROLE_" + UUID.randomUUID().toString().substring(0, 8));
    role.setCode(role.getName());
    Role saved = roleRepository.save(role);
    cleanup.push(() -> roleRepository.findById(saved.getId()).ifPresent(roleRepository::delete));
    return saved.getName();
  }

  private Squadron newSquadron() {
    Squadron squadron = new Squadron();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    squadron.setName("G19 squadron " + suffix);
    squadron.setShorthand("G" + suffix.substring(0, 5));
    return squadron;
  }

  private UUID squadron() {
    return trackSquadron(squadronService.createSquadron(newSquadron())).getId();
  }

  private Squadron trackSquadron(Squadron squadron) {
    cleanup.push(() -> deleteRow("org_unit", squadron.getId()));
    return squadron;
  }

  private StarSystem newStarSystem() {
    StarSystem starSystem = new StarSystem();
    starSystem.setName("G19 system " + UUID.randomUUID());
    return starSystem;
  }

  private UUID starSystem() {
    return trackStarSystem(starSystemService.createStarSystem(newStarSystem())).getId();
  }

  private StarSystem trackStarSystem(StarSystem starSystem) {
    cleanup.push(() -> deleteRow("star_system", starSystem.getId()));
    return starSystem;
  }

  private void deleteRow(String table, UUID id) {
    new JdbcTemplate(dataSource).update("DELETE FROM " + table + " WHERE id = ?", id);
  }
}
