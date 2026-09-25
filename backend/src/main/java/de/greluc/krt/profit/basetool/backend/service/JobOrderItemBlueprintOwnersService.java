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

import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderBlueprintOwnerDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemBlueprintOwnersDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderRequiredBlueprintDto;
import de.greluc.krt.profit.basetool.backend.model.projection.BlueprintOwnerProduct;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
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
 * Assembles the blueprint-coverage view of an {@code ITEM} job order: which members of the
 * responsible org unit own a blueprint for the requested items (or a cosmetic variant of them), and
 * which one each holds.
 *
 * <p>Items are matched by {@link BlueprintVariantFamilyResolver} key, per the order's {@code
 * countBlueprintsWithVariants} toggle (REQ-ORDERS-021): variant family when on, exact name when
 * off. Owners are exposed only by display name.
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
   * Builds the blueprint-coverage view for the given order, restricted to the responsible org
   * unit's members and the order's required families. {@code MATERIAL} orders and item orders
   * without a blueprint product yield an empty view.
   *
   * @param jobOrderId the job order to inspect; never {@code null}
   * @return the required families with owner counts and the owning members with their variants;
   *     never {@code null}
   * @throws NotFoundException when the order id is unknown
   */
  @NotNull
  public JobOrderItemBlueprintOwnersDto getBlueprintOwners(@NotNull UUID jobOrderId) {
    JobOrder order =
        Entities.require(
            jobOrderRepository.findByIdWithItemBlueprints(jobOrderId),
            () -> "Job order not found: " + jobOrderId);

    boolean countWithVariants = order.isCountBlueprintsWithVariants();

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

    OrgUnit responsible = order.getResponsibleOrgUnit();
    Set<UUID> memberSubs = new LinkedHashSet<>();
    if (responsible != null) {
      memberSubs.addAll(
          orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(
              Set.of(responsible.getId())));
    }
    Set<UUID> ownerUserIds = new LinkedHashSet<>(memberSubs);
    ownerUserIds.addAll(userRepository.findIdsBySharingBlueprintsGlobally());

    Map<UUID, Set<String>> ownedNamesByOwnerId = new LinkedHashMap<>();
    Map<String, Set<UUID>> ownersByFamily = new HashMap<>();
    if (!ownerUserIds.isEmpty()) {
      for (BlueprintOwnerProduct bp :
          personalBlueprintRepository.findOwnerProductByOwnerUserIdIn(ownerUserIds)) {
        String matchKey = familyResolver.matchKey(bp.productName(), countWithVariants);
        if (!requiredByFamily.containsKey(matchKey)) {
          continue;
        }
        UUID ownerId = bp.ownerUserId();
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
   * Resolves the grouped owner ids to display-name rows listing the concrete blueprints each owner
   * holds; ids that no longer resolve to a {@link User} are dropped.
   *
   * @param ownedNamesByOwnerId owner id to the owned blueprint names that matched a required family
   * @param memberSubs ids of the responsible org unit's members, used to flag global sharers who
   *     are not members (REQ-INV-018)
   * @return the owner rows, sorted case-insensitively by name; never {@code null}
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
   * One required variant family of the coverage view.
   *
   * @param displayName the ordered item's display name shown on the coverage row
   * @param variantInclusive whether the coverage count includes owners of cosmetic variants
   */
  private record RequiredFamily(@NotNull String displayName, boolean variantInclusive) {}
}
