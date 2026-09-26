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

package de.greluc.krt.profit.basetool.frontend.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * Compares the property names of the hand-written {@code frontend/model/dto} mirrors with the types
 * generated from {@code openapi.json} (ADR-0161).
 *
 * <p>Only names are compared; types and nullability are covered by {@code ExternalContractTest}
 * (REQ-API-009). Existing drift is listed in {@link #KNOWN_DRIFT}.
 */
class GeneratedDtoAgreementTest {

  /** Where the hand-written mirrors live. */
  private static final String MIRROR_PACKAGE = "de.greluc.krt.profit.basetool.frontend.model.dto";

  /** Where the generator writes. */
  private static final String GENERATED_PACKAGE =
      "de.greluc.krt.profit.basetool.frontend.contract.model";

  /** Mirrors whose backend schema has a different name, mapped to the schema each one mirrors. */
  private static final Map<String, String> ALIASES =
      Map.ofEntries(
          Map.entry("AdminDeletionRequestDto", "DeletionRequestDto"),
          Map.entry("PersonSearchResultDto", "PersonSearchResult"),
          Map.entry("DefaultBlueprintDto", "DefaultBlueprintResponse"),
          Map.entry("MaterialCreateAjaxRequest", "MaterialCreateDto"),
          Map.entry("MemberEvaluationDto", "MemberEvaluationResponse"),
          Map.entry("NotificationCountResponse", "NotificationUnreadCountDto"),
          Map.entry("PersonalBlueprintBatchResultDto", "PersonalBlueprintBatchResult"),
          Map.entry("PersonalBlueprintBulkDeleteResultDto", "PersonalBlueprintBulkDeleteResult"),
          Map.entry("PersonalBlueprintDto", "PersonalBlueprintResponse"),
          Map.entry("PersonalBlueprintRecipeDto", "PersonalBlueprintRecipeResponse"),
          Map.entry("PersonalInventoryItemDto", "PersonalInventoryItemResponse"),
          Map.entry("PromotionCategoryDto", "PromotionCategoryResponse"),
          Map.entry("PromotionEligibilityDto", "PromotionEligibilityResponse"),
          Map.entry("PromotionLevelContentDto", "PromotionLevelContentResponse"),
          Map.entry("PromotionRequirementCheckDto", "PromotionRequirementCheckResponse"),
          Map.entry("PromotionTopicDto", "PromotionTopicResponse"),
          Map.entry("RankRequirementDto", "RankRequirementResponse"),
          Map.entry("UserAttributesUpdateDto", "UserAttributesRequest"));

  /**
   * Types under {@code model/dto} that mirror no backend schema. Any other type without a
   * counterpart fails the test.
   */
  private static final Set<String> FRONTEND_ONLY =
      Set.of(
          "AuditRowView",
          "MatrixGridDto",
          "NotificationViewDto",
          "NotificationPageSliceDto",
          "StagedHandoff",
          "PageResponse",
          "BereichCreateRequest",
          "MaterialUpdateAjaxRequest",
          "MissionActualTimeUpdateRequest",
          "OrganisationsleitungCreateRequest",
          "DefaultBlueprintAddSelectionRequest",
          "DefaultBlueprintAddResultDto");

  /**
   * Known drift between mirror and schema, frozen so anything new fails; removing an entry is the
   * fix.
   *
   * <ul>
   *   <li>{@code RefineryOrderListDto} / {@code endsAt} — sent by the backend, recomputed by the
   *       mirror as {@code startedAt + durationMinutes}.
   *   <li>{@code PromotionTopicDto} / {@code owningSquadron} — sent by the backend, not declared by
   *       the mirror.
   * </ul>
   */
  private static final Map<String, Set<String>> KNOWN_DRIFT =
      Map.of(
          "RefineryOrderListDto", Set.of("endsAt"),
          "PromotionTopicDto", Set.of("owningSquadron"));

  @Test
  @DisplayName("every mirrored DTO carries exactly the fields the contract declares")
  void mirrorsAgreeWithTheGeneratedModels() {
    Map<String, Class<?>> generated = scan(GENERATED_PACKAGE);
    Map<String, Class<?>> mirrors = scan(MIRROR_PACKAGE);

    assertThat(generated)
        .as("the generator produced no models, which would make every case below vacuous")
        .hasSizeGreaterThan(200);
    assertThat(mirrors)
        .as("the mirrors disappeared, which this test cannot be right about")
        .hasSizeGreaterThan(200);

    Map<String, String> problems = new TreeMap<>();
    for (Map.Entry<String, Class<?>> mirror : mirrors.entrySet()) {
      String name = mirror.getKey();
      if (FRONTEND_ONLY.contains(name)) {
        continue;
      }
      Class<?> counterpart = generated.get(ALIASES.getOrDefault(name, name));
      if (counterpart == null) {
        problems.put(
            name,
            "no schema of this name in openapi.json — either the backend removed it, or it is a "
                + "frontend-only type and belongs in FRONTEND_ONLY, or it is named differently "
                + "there and belongs in ALIASES");
        continue;
      }
      Set<String> mine = propertiesOf(mirror.getValue());
      Set<String> theirs = new TreeSet<>(propertiesOf(counterpart));
      theirs.removeAll(KNOWN_DRIFT.getOrDefault(name, Set.of()));
      if (!mine.equals(theirs)) {
        Set<String> onlyMine = new TreeSet<>(mine);
        onlyMine.removeAll(theirs);
        Set<String> onlyTheirs = new TreeSet<>(theirs);
        onlyTheirs.removeAll(mine);
        problems.put(name, "mirror-only " + onlyMine + ", contract-only " + onlyTheirs);
      }
    }

    assertThat(problems)
        .as(
            "the hand-written mirrors and the contract disagree. A contract-only field is one the "
                + "backend sends and this page will read as null; a mirror-only field is one the "
                + "backend never sends. The contract is the backend's — fix the mirror")
        .isEmpty();
  }

  @Test
  @DisplayName("the two exception lists still describe something, and cannot quietly grow")
  void theExceptionListsHaveAFloorAndACeiling() {
    assertThat(FRONTEND_ONLY)
        .as(
            "frontend-only types. A genuine new view model raises this AND gets a reason beside its"
                + " name; a backend type that stopped existing does NOT belong here")
        .hasSize(12);
    assertThat(ALIASES)
        .as(
            "mirror-to-schema aliases. A rename on either side raises this and moves the entry, and"
                + " each one is established by an identical property set rather than by the name")
        .hasSize(18);
    assertThat(KNOWN_DRIFT)
        .as(
            "pre-existing drifts, frozen with their reasons. This number goes DOWN when one is"
                + " fixed; it goes up only with the same justification the two entries carry")
        .hasSize(2);
  }

  /**
   * Finds every concrete non-enum type in a package, restricted to the {@code main} output
   * directory.
   *
   * @param packageName the package to scan
   * @return simple name to class, for every candidate found
   */
  private static Map<String, Class<?>> scan(String packageName) {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));
    Map<String, Class<?>> found = new LinkedHashMap<>();
    for (BeanDefinition definition : scanner.findCandidateComponents(packageName)) {
      String name = definition.getBeanClassName();
      if (name == null || name.contains("$") || name.endsWith("Test")) {
        continue;
      }
      try {
        Class<?> type = Class.forName(name);
        if (!type.isEnum()) {
          found.put(type.getSimpleName(), type);
        }
      } catch (ClassNotFoundException e) {
        throw new AssertionError("scanned a class that cannot be loaded: " + name, e);
      }
    }
    return found;
  }

  /**
   * The property names of a type: a record's components or a class's instance fields.
   *
   * @param type the type to read
   * @return its property names
   */
  private static Set<String> propertiesOf(Class<?> type) {
    Set<String> names = new TreeSet<>();
    if (type.isRecord()) {
      for (RecordComponent component : type.getRecordComponents()) {
        names.add(component.getName());
      }
      return names;
    }
    for (Field field : type.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
        names.add(field.getName());
      }
    }
    return names;
  }
}
