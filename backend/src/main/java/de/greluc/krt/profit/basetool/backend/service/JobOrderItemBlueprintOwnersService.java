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

import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderBlueprintOwnerDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemBlueprintOwnersDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderRequiredBlueprintDto;
import de.greluc.krt.profit.basetool.backend.model.projection.BlueprintOwnerProduct;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the item job-order <em>blueprint-coverage</em> view: for an {@code ITEM} order, who
 * among the members of the order's responsible (processing) squadron/SK owns the blueprint for the
 * items the order requests (or for any cosmetic variant of them), and which concrete blueprint each
 * member holds.
 *
 * <p>{@link PersonalBlueprint} carries no org-unit column — it is a per-user aggregate keyed by the
 * Keycloak {@code sub}. This service bridges a job order's required items to org-unit members
 * entirely in Java, mirroring {@link PersonalBlueprintOverviewService}: it reduces each item line's
 * chosen-blueprint output name to its <em>match key</em> via {@link
 * BlueprintVariantFamilyResolver}, resolves the responsible org unit's member ids to their {@code
 * sub} form (the {@code owner_sub} stored on {@link PersonalBlueprint} equals {@code User.id}),
 * loads the members' owned blueprints, and matches them by the same key. The coverage count is the
 * distinct members owning any matching blueprint; each owner row lists the concrete variants they
 * hold.
 *
 * <p>The match key honours the order's per-order counting toggle ({@code
 * countBlueprintsWithVariants}, REQ-ORDERS-021): when on (the default), the key is the variant
 * family key, so a base item and its cosmetic variants — {@code Fresnel Energy LMG} ↔ {@code
 * Fresnel "Molten" Energy LMG} — collapse onto one required family (magazines stay atomic); when
 * off, the key is the exact normalized name, so an order for one specific variant counts only
 * owners of that exact blueprint and excludes the family's other variants.
 *
 * <p>Member identity never leaves the service except as a display name — owners are exposed only
 * via {@link User#getEffectiveName()} (never the {@code sub} or e-mail), preserving the
 * data-isolation rule. The endpoint above this service is gated members-only ({@code
 * @ownerScopeService.canSeeJobOrderBlueprintOwners}), so a non-member viewing an otherwise-public
 * SK order never reaches this aggregation.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobOrderItemBlueprintOwnersService {

  private final JobOrderRepository jobOrderRepository;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;
  private final PersonalBlueprintRepository personalBlueprintRepository;
  private final UserRepository userRepository;
  private final BlueprintVariantFamilyResolver familyResolver;

  /**
   * Builds the blueprint-coverage view for the given order. {@code MATERIAL} orders (and item
   * orders whose lines resolve to no blueprint product) yield an empty view. The owner grouping is
   * restricted to the order's responsible org unit's members and to the order's required variant
   * families, so neither foreign members nor a member's unrelated blueprints are exposed; a member
   * who owns a cosmetic variant of a required item is counted, and their owned variant is surfaced.
   *
   * @param jobOrderId the job order to inspect; never {@code null}
   * @return the coverage view (required families with variant-inclusive owner counts + owning
   *     members with the concrete variant blueprints they hold); never {@code null}
   * @throws EntityNotFoundException when the order id is unknown
   */
  @NotNull
  public JobOrderItemBlueprintOwnersDto getBlueprintOwners(@NotNull UUID jobOrderId) {
    JobOrder order =
        jobOrderRepository
            .findByIdWithItemBlueprints(jobOrderId)
            .orElseThrow(() -> new EntityNotFoundException("Job order not found: " + jobOrderId));

    // Per-order counting toggle (REQ-ORDERS-021, issue #822): when true the coverage counts
    // cosmetic
    // variants of an ordered item via the variant family key (the historic behaviour); when false
    // it
    // matches blueprints exactly, so when a specific variant is requested only owners of that exact
    // blueprint count and the other variants of the same family are excluded. Both the required
    // side
    // here and the owned side below derive their key through familyResolver.matchKey(name,
    // countWithVariants) so the two stay symmetric.
    boolean countWithVariants = order.isCountBlueprintsWithVariants();

    // Required products: match key -> (ordered display name, variant-inclusive flag). Each item
    // line's chosen-blueprint output name is reduced to its match key (variant family key when the
    // toggle is on, exact normalized name when off), so in variant mode a base item and its
    // cosmetic
    // variants collapse onto one required row. Lines whose output name resolves to nothing are
    // skipped; a MATERIAL order has no item lines. A row is variant-inclusive only when the toggle
    // is
    // on AND the line is not an atomic magazine (a magazine is always matched exactly).
    Map<String, RequiredFamily> requiredByFamily = new LinkedHashMap<>();
    for (JobOrderItem item : order.getItems()) {
      String outputName = item.getBlueprint() == null ? null : item.getBlueprint().getOutputName();
      String matchKey = familyResolver.matchKey(outputName, countWithVariants);
      if (matchKey.isEmpty()) {
        continue;
      }
      String displayName = item.getGameItem() != null ? item.getGameItem().getName() : outputName;
      boolean variantInclusive = countWithVariants && !familyResolver.isMagazine(outputName);
      requiredByFamily.putIfAbsent(matchKey, new RequiredFamily(displayName, variantInclusive));
    }
    if (requiredByFamily.isEmpty()) {
      return new JobOrderItemBlueprintOwnersDto(List.of(), List.of());
    }

    // Owner ids of the responsible org unit's members, unioned with the users who opted into
    // global blueprint sharing (REQ-INV-018) so an opted-in owner is counted toward this order's
    // coverage even when they are not a member of the responsible org unit. A null responsible
    // (legacy pre-backfill row) contributes no members; the endpoint gate already rejects such
    // orders, but global sharers would still be considered here.
    OrgUnit responsible = order.getResponsibleOrgUnit();
    Set<UUID> memberSubs = new LinkedHashSet<>();
    if (responsible != null) {
      memberSubs.addAll(
          orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(
              Set.of(responsible.getId())));
    }
    Set<UUID> ownerSubs = new LinkedHashSet<>(memberSubs);
    ownerSubs.addAll(userRepository.findIdsBySharingBlueprintsGlobally());

    // Load the members' owned blueprints and keep the ones whose match key is required. The match
    // key is a Java-computed reduction of the product name (no SQL form), so the match runs in
    // memory
    // over the members' rows. The per-owner set carries the ACTUAL owned variant names (so a lead
    // sees which variant each member holds), and the per-key owner set drives the coverage count
    // (distinct members owning any matching blueprint).
    Map<UUID, Set<String>> ownedNamesByOwnerId = new LinkedHashMap<>();
    Map<String, Set<UUID>> ownersByFamily = new HashMap<>();
    if (!ownerSubs.isEmpty()) {
      for (BlueprintOwnerProduct bp :
          personalBlueprintRepository.findOwnerProductByOwnerSubIn(ownerSubs)) {
        String matchKey = familyResolver.matchKey(bp.productName(), countWithVariants);
        if (!requiredByFamily.containsKey(matchKey)) {
          continue;
        }
        UUID ownerId = bp.ownerSub();
        ownedNamesByOwnerId
            .computeIfAbsent(ownerId, id -> new LinkedHashSet<>())
            .add(bp.productName());
        ownersByFamily.computeIfAbsent(matchKey, k -> new LinkedHashSet<>()).add(ownerId);
      }
    }

    List<JobOrderRequiredBlueprintDto> requiredBlueprints =
        requiredByFamily.entrySet().stream()
            .map(
                e ->
                    new JobOrderRequiredBlueprintDto(
                        e.getKey(),
                        e.getValue().displayName(),
                        ownersByFamily.getOrDefault(e.getKey(), Set.of()).size(),
                        e.getValue().variantInclusive()))
            .sorted(
                Comparator.comparing(
                        JobOrderRequiredBlueprintDto::productName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(JobOrderRequiredBlueprintDto::productKey))
            .toList();

    List<JobOrderBlueprintOwnerDto> owners = buildOwners(ownedNamesByOwnerId, memberSubs);
    return new JobOrderItemBlueprintOwnersDto(requiredBlueprints, owners);
  }

  /**
   * Resolves the grouped owner ids to display-name rows. Each owner row carries the display names
   * of the actual blueprints that member owns and which matched a required family — the concrete
   * variant they hold, not the ordered base. Owners whose id no longer resolves to a {@link User}
   * are dropped; the remaining rows are sorted by member name.
   *
   * @param ownedNamesByOwnerId owner id → the owned blueprint display names that matched a required
   *     family
   * @param memberSubs the {@code app_user.id}s of the responsible org unit's members, used to flag
   *     each owner as a unit member ({@code true}) or a global sharer who is not a member ({@code
   *     false}, REQ-INV-018) so the UI can mark the latter
   * @return the owning-member rows, sorted case-insensitively by name; never {@code null}
   */
  @NotNull
  private List<JobOrderBlueprintOwnerDto> buildOwners(
      @NotNull Map<UUID, Set<String>> ownedNamesByOwnerId, @NotNull Set<UUID> memberSubs) {
    if (ownedNamesByOwnerId.isEmpty()) {
      return List.of();
    }
    Map<UUID, String> nameById =
        userRepository.findAllById(ownedNamesByOwnerId.keySet()).stream()
            .collect(Collectors.toMap(User::getId, User::getEffectiveName));
    return ownedNamesByOwnerId.entrySet().stream()
        .filter(e -> nameById.containsKey(e.getKey()))
        .map(
            e ->
                new JobOrderBlueprintOwnerDto(
                    nameById.get(e.getKey()),
                    e.getValue().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(),
                    memberSubs.contains(e.getKey())))
        .sorted(
            Comparator.comparing(
                JobOrderBlueprintOwnerDto::ownerName, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /**
   * One required variant family while assembling the coverage view: the ordered item's display name
   * (a variant name when a variant was ordered) and whether the row folds in cosmetic variants
   * (true for a weapon family, false for an atomic magazine).
   *
   * @param displayName the ordered item's display name shown on the coverage row
   * @param variantInclusive whether the coverage count includes owners of cosmetic variants
   */
  private record RequiredFamily(@NotNull String displayName, boolean variantInclusive) {}
}
