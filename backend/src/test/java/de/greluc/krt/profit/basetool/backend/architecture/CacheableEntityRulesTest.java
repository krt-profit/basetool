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

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaWildcardType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import de.greluc.krt.profit.basetool.backend.architecture.fixture.CachedEntityFixtures;
import jakarta.persistence.Entity;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

/**
 * Structural rules for cached entities (REQ-DATA-022): the target state that a cache holds read
 * models, never an {@code @Entity}, and the invariant that no class edits an entity it obtained
 * from another class's cache.
 *
 * <p>Both rules are proven able to fail on {@link CachedEntityFixtures}.
 */
class CacheableEntityRulesTest {

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.backend");

  private static final JavaClasses FIXTURES =
      new ClassFileImporter().importPackagesOf(CachedEntityFixtures.class);

  /** Today's count of {@code @Cacheable} methods. */
  private static final int CACHEABLE_FLOOR = 33;

  private static final String CATALOGUE_ENTITY_CACHE =
      "A master-data cache holding the catalogue entity itself; it becomes a read model in"
          + " Phase 4 (plan §5.6). Safe meanwhile only because its own mutators load through"
          + " self-invocation, which the cached-entity invariant test holds.";

  /**
   * The {@code @Cacheable} methods that still return an entity, keyed {@code
   * Class#method(ParameterTypes)}, each with the reason. The list may only shrink.
   */
  static final Map<String, String> ENTITY_CACHES =
      entityCaches(
          "CityService#getAllCities(Pageable)",
          "CityService#getCity(UUID)",
          "FrequencyTypeService#getAllFrequencyTypes(Boolean,Pageable)",
          "FrequencyTypeService#getFrequencyType(UUID)",
          "JobTypeService#getJobTypes(JobTypeArchetype)",
          "JobTypeService#getJobTypes(JobTypeArchetype,Pageable,boolean)",
          "LocationService#getAllLocations(Pageable,boolean)",
          "LocationService#getHomeLocations()",
          "LocationService#getLocation(UUID)",
          "LocationService#getRefineryLocations()",
          "ManufacturerService#getAllManufacturers(Pageable,boolean)",
          "ManufacturerService#getManufacturer(UUID)",
          "MaterialCategoryService#findAll()",
          "MaterialCategoryService#findById(UUID)",
          "MaterialService#getAllMaterials(Pageable)",
          "MaterialService#getMaterial(UUID)",
          "MaterialService#getVisibleMaterials(Pageable)",
          "OutpostService#getAllOutposts(Pageable)",
          "OutpostService#getOutpost(UUID)",
          "PoiService#getAllPois(Pageable)",
          "PoiService#getPoi(UUID)",
          "RefiningMethodService#getAllRefiningMethods(Pageable)",
          "RefiningMethodService#getRefiningMethod(UUID)",
          "RoleService#getAllRoles(Pageable)",
          "ShipTypeService#getAllShipTypes(Pageable,boolean)",
          "ShipTypeService#getShipType(UUID)",
          "SquadronService#getAllSquadrons(Pageable,boolean)",
          "SquadronService#getAllSquadrons(boolean)",
          "StarSystemService#getAllStarSystems(Pageable)",
          "StarSystemService#getStarSystem(UUID)",
          "TerminalService#getAllTerminals(Pageable)",
          "TerminalService#getTerminal(UUID)");

  @Test
  @DisplayName("no @Cacheable method returns an entity, except the listed catalogue caches")
  void noCacheReturnsAnEntity() {
    List<JavaMethod> cacheable = cacheableMethods(PRODUCTION);
    assertThat(cacheable)
        .as("@Cacheable methods (an emptied selection would pass vacuously)")
        .hasSizeGreaterThanOrEqualTo(CACHEABLE_FLOOR);
    assertThat(entityReturningCaches(cacheable))
        .as(
            """
            @Cacheable methods returning an entity, against ENTITY_CACHES. A cache shares one \
            instance between every caller; cache a read model (a record) instead. The list may \
            only shrink.\
            """)
        .isEqualTo(new TreeSet<>(ENTITY_CACHES.keySet()));
  }

  @Test
  @DisplayName("no class edits an entity it obtained from another class's cache")
  void noClassEditsAnotherClassesCachedEntity() {
    assertThat(foreignCachedEntityEdits(PRODUCTION))
        .as(
            """
            Methods that call another class's @Cacheable method and a setter on the entity it \
            returns. Through the proxy the call returns the shared cached instance, and the edit \
            corrupts the cache for every reader, also when the write then fails. Load the entity \
            from the repository inside the write instead.\
            """)
        .isEmpty();
  }

  @Test
  @DisplayName("proof: a planted entity cache is reported")
  void aPlantedEntityCacheIsReported() {
    assertThat(entityReturningCaches(cacheableMethods(FIXTURES)))
        .containsExactly("PlantedCityQueries#find(UUID)");
  }

  @Test
  @DisplayName("proof: a planted command editing another class's cached entity is reported")
  void aPlantedForeignCachedEntityEditIsReported() {
    assertThat(foreignCachedEntityEdits(FIXTURES))
        .containsExactly(
            "PlantedCityCommands#toggleLoadingDock(UUID) edits City from"
                + " PlantedCityQueries#find(UUID)");
  }

  private static Map<String, String> entityCaches(String... keys) {
    Map<String, String> caches = new TreeMap<>();
    for (String key : keys) {
      caches.put(key, CATALOGUE_ENTITY_CACHE);
    }
    return caches;
  }

  private static List<JavaMethod> cacheableMethods(JavaClasses classes) {
    return classes.stream()
        .flatMap(c -> c.getMethods().stream())
        .filter(m -> m.isAnnotatedWith(Cacheable.class))
        .toList();
  }

  private static Set<String> entityReturningCaches(List<JavaMethod> cacheable) {
    return cacheable.stream()
        .filter(m -> !entitiesIn(m.getReturnType()).isEmpty())
        .map(CacheableEntityRulesTest::key)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Set<String> foreignCachedEntityEdits(JavaClasses classes) {
    Set<String> edits = new TreeSet<>();
    for (JavaClass c : classes) {
      for (JavaMethod method : c.getMethods()) {
        for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
          Optional<JavaMethod> target = call.getTarget().resolveMember();
          if (target.isEmpty()
              || !target.get().isAnnotatedWith(Cacheable.class)
              || target.get().getOwner().equals(c)) {
            continue;
          }
          for (JavaClass entity : entitiesIn(target.get().getReturnType())) {
            if (callsASetterOn(method, entity)) {
              edits.add(
                  key(method) + " edits " + entity.getSimpleName() + " from " + key(target.get()));
            }
          }
        }
      }
    }
    return edits;
  }

  private static boolean callsASetterOn(JavaMethod method, JavaClass entity) {
    return method.getMethodCallsFromSelf().stream()
        .anyMatch(
            call ->
                call.getName().startsWith("set")
                    && (call.getTargetOwner().isAssignableTo(entity.getName())
                        || entity.isAssignableTo(call.getTargetOwner().getName())));
  }

  /**
   * Returns the entity classes a declared type is or contains as a type argument, at any depth.
   *
   * @param type the declared type
   * @return the entity classes found
   */
  private static Set<JavaClass> entitiesIn(@NotNull JavaType type) {
    Set<JavaClass> found = new LinkedHashSet<>();
    collectEntities(type, found);
    return found;
  }

  private static void collectEntities(JavaType type, Set<JavaClass> found) {
    if (type instanceof JavaParameterizedType parameterized) {
      parameterized.getActualTypeArguments().forEach(t -> collectEntities(t, found));
    } else if (type instanceof JavaWildcardType wildcard) {
      wildcard.getUpperBounds().forEach(t -> collectEntities(t, found));
    }
    JavaClass erasure = type.toErasure();
    if (erasure.isArray()) {
      collectEntities(erasure.getComponentType(), found);
      return;
    }
    for (JavaClass c = erasure; c != null; c = c.getRawSuperclass().orElse(null)) {
      if (c.isAnnotatedWith(Entity.class)) {
        found.add(erasure);
        return;
      }
    }
  }

  private static String key(JavaMethod method) {
    return method.getOwner().getSimpleName()
        + "#"
        + method.getName()
        + method.getRawParameterTypes().stream()
            .map(JavaClass::getSimpleName)
            .collect(Collectors.joining(",", "(", ")"));
  }
}
