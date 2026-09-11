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
 * The hand-written mirrors, checked against the contract they mirror (ADR-0161 §8.2).
 *
 * <p><b>The problem.</b> {@code frontend/model/dto} restates every backend response shape by hand.
 * Nothing made the two agree, and when they stop agreeing the symptom is a field that arrives
 * {@code null} on a rendered page — Spring Boot disables {@code FAIL_ON_UNKNOWN_PROPERTIES}, so a
 * field the backend sends and the mirror does not declare is dropped in silence. That is the
 * maintainability cost gRPC was proposed to remove, and a generator removes it without a protocol.
 *
 * <p><b>What this test is, and what it is not.</b> The generator now runs against the same {@code
 * openapi.json} the Android app generates from, and this compares its output to the mirrors field
 * by field, turning drift into a failing build <em>today</em>. It does not turn it into a
 * <em>compile</em> failure — that needs the mirrors replaced by generated types, which moves every
 * accessor call site (records against classes with getters, {@code Set} against {@code List},
 * {@code Instant} against {@code OffsetDateTime}) and discards Javadoc a schema cannot carry. §8.2
 * asks for exactly this order: prove the generator agrees before deleting anything.
 *
 * <p><b>Names, not types.</b> The comparison is on property names. Types are out of scope here
 * because the two sides make different, defensible mappings of one schema, and asserting those
 * would be asserting the generator's configuration rather than the contract. Types and nullability
 * are frozen where a shipped client cannot be redeployed away from them, by {@code
 * ExternalContractTest} (REQ-API-009). A missing or misspelled field is what this one is for.
 *
 * <p><b>It found two on the day it was written</b>, both recorded in {@link #KNOWN_DRIFT}.
 */
class GeneratedDtoAgreementTest {

  /** Where the hand-written mirrors live. */
  private static final String MIRROR_PACKAGE = "de.greluc.krt.profit.basetool.frontend.model.dto";

  /** Where the generator writes. */
  private static final String GENERATED_PACKAGE =
      "de.greluc.krt.profit.basetool.frontend.contract.model";

  /**
   * Mirrors whose backend schema carries a different name, and the schema each one mirrors.
   *
   * <p>Every entry was established by field-set comparison rather than by reading the name: each
   * pair below shares its <em>entire</em> property set, which is not something two unrelated types
   * do. Without this map fifteen real mirrors would be silently skipped as "frontend-only" and the
   * guard would cover the easy 94% while missing the families most likely to drift — promotion and
   * personal inventory, both young and both still moving.
   */
  private static final Map<String, String> ALIASES =
      Map.ofEntries(
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
   * Types under {@code model/dto} that mirror nothing, with the reason each one is there.
   *
   * <p>An explicit list rather than a heuristic, because "no schema of this name" is precisely what
   * a <em>removed</em> backend type looks like too. Anything not named here and not aliased above
   * must have a counterpart, so a deletion on the backend surfaces as a failure rather than as one
   * more silently-skipped entry.
   */
  private static final Set<String> FRONTEND_ONLY =
      Set.of(
          // View models assembled in the frontend from one or more backend responses.
          "AuditRowView",
          "MatrixGridDto",
          "NotificationViewDto",
          "NotificationPageSliceDto",
          "StagedHandoff",
          // The generic page envelope. The backend's spec has no generic: springdoc expands it into
          // one concrete PageResponseXxx schema per payload type, so there is nothing to compare a
          // single generic record against.
          "PageResponse",
          // Form-backing objects the templates bind to. They carry Jakarta validation, exist for
          // Thymeleaf, and are mapped to a backend request before they are sent -- so the generator
          // has nothing to say about them and a generated replacement would be wrong rather than
          // merely different.
          "BereichCreateRequest",
          "MaterialUpdateAjaxRequest",
          "MissionActualTimeUpdateRequest",
          "OrganisationsleitungCreateRequest");

  /**
   * Drift that existed before this guard did, frozen so it fails on anything new.
   *
   * <p>Both entries are dated 2026-09-10 and were found by the first run of this test. Neither is
   * fixed here: each needs a decision that belongs to the area it touches, not to the change that
   * introduced the guard. Removing an entry is the fix; adding one needs the same justification.
   *
   * <p><b>They are recorded outside this constant as well</b>, so a test literal is not the only
   * place they exist: {@code docs/WIRE_PROTOCOL_EVALUATION.md} §8.2 names both with their
   * consequences, and ADR-0161's consequences section carries them into the decision record.
   *
   * <ul>
   *   <li>{@code RefineryOrderListDto} / {@code endsAt} — the backend sends it and the mirror
   *       <em>recomputes</em> it, in {@code getEndsAt()}, as {@code startedAt + durationMinutes}.
   *       Not a dropped field so much as a second implementation of one, and the two would diverge
   *       silently the day the server's answer stops being that sum.
   *   <li>{@code PromotionTopicDto} / {@code owningSquadron} — sent by the backend, not declared by
   *       the mirror, so the promotion UI cannot render the owning squadron of a topic even though
   *       the data arrives. This is the failure mode in its pure form.
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
    // Without this, "make the guard pass" has an easy wrong answer: move the failing name into
    // FRONTEND_ONLY. The ceiling is what makes that a visible decision rather than a one-line diff
    // nobody reads, and the KNOWN_DRIFT floor keeps the two open findings from being quietly
    // dropped instead of resolved.
    assertThat(FRONTEND_ONLY)
        .as(
            "frontend-only types. A genuine new view model raises this AND gets a reason beside its"
                + " name; a backend type that stopped existing does NOT belong here")
        .hasSize(10);
    assertThat(ALIASES)
        .as(
            "mirror-to-schema aliases. A rename on either side raises this and moves the entry, and"
                + " each one is established by an identical property set rather than by the name")
        .hasSize(16);
    assertThat(KNOWN_DRIFT)
        .as(
            "pre-existing drifts, frozen with their reasons. This number goes DOWN when one is"
                + " fixed; it goes up only with the same justification the two entries carry")
        .hasSize(2);
  }

  /**
   * Finds every concrete non-enum type in a package.
   *
   * <p>Restricted to the {@code main} output directory. The test source set holds classes in the
   * same package — {@code MatrixGridDtoTest}, {@code FrontendDtoContractTest} — and a scan that
   * picked them up would report each as a mirror with no schema, which is true and useless.
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
   * The property names a type carries, whichever shape it is written in.
   *
   * <p>A record answers with its components, a generated model with its instance fields. Both come
   * out as the same set of names, which is the only thing compared.
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
