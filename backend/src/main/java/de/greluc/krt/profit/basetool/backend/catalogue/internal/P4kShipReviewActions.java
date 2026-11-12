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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Proposes ship identities corroborated by the SC Wiki and shows recorded P4K variants. */
@Component
@RequiredArgsConstructor
class P4kShipReviewActions implements P4kReviewActionProvider {

  private final ShipTypeRepository ships;
  private final ShipTypeP4kVariantRepository variants;
  private final P4kReviewCaseRepository cases;
  private final P4kShipClassAliases classAliases;

  @Override
  public boolean supports(@NotNull P4kKind kind) {
    return kind == P4kKind.SHIP;
  }

  @Override
  @NotNull
  public P4kReviewOptions options(@NotNull P4kReviewCaseDto review, @Nullable String recordPath) {
    P4kReviewCase pending =
        cases.findById(review.id()).filter(P4kReviewCase::isShipRepairPending).orElse(null);
    if (pending != null) {
      UUID id = Objects.requireNonNull(pending.getShipRepairRowId());
      String baseClass = Objects.requireNonNull(pending.getShipRepairBaseClass());
      P4kReviewCandidate cancel =
          new P4kReviewCandidate(
              P4kReviewAction.CANCEL_SHIP_REPAIR,
              id,
              null,
              null,
              baseClass,
              pending.getShipRepairVariantClass(),
              pending.getShipRepairVariantGuid(),
              pending.getGuid(),
              List.of(),
              List.of(P4kReviewEvidence.P4K_VARIANT));
      return new P4kReviewOptions(
          P4kReviewReadiness.WAITING_FOR_IMPORT, baseClass, List.of(cancel));
    }
    UUID guid = review.guid();
    Optional<ShipType> external = ships.findByExternalUuid(guid);
    Optional<ShipType> owner = ships.findByP4kUuid(guid);
    Optional<ShipTypeP4kVariant> incomingVariant = variants.findById(guid);
    String className = review.className();
    List<ShipType> classMatches =
        className == null || className.isBlank()
            ? List.of()
            : ships.findByClassNameOrUexAlias(className.strip());
    Optional<ShipType> attachable =
        external.filter(ship ->
            canAttach(ship, review, owner.isPresent(), incomingVariant.orElse(null), classMatches));
    if (attachable.isPresent()) {
      ShipType ship = attachable.orElseThrow();
      return new P4kReviewOptions(
          P4kReviewReadiness.ACTIONABLE,
          review.nameEn(),
          List.of(
              candidate(P4kReviewAction.ATTACH_P4K, ship, review, incomingVariant.isPresent())));
    }

    Map<UUID, ShipType> rows = new LinkedHashMap<>();
    external.ifPresent(ship -> rows.put(ship.getId(), ship));
    owner.ifPresent(ship -> rows.put(ship.getId(), ship));
    List<UUID> caseRows =
        Stream.of(review.rowId(), review.otherRowId()).filter(Objects::nonNull).distinct().toList();
    if (!caseRows.isEmpty()) {
      ships.findAllById(caseRows).forEach(ship -> rows.put(ship.getId(), ship));
    }
    classMatches.forEach(ship -> rows.put(ship.getId(), ship));
    UUID baseGuid = incomingVariant.map(ShipTypeP4kVariant::getBaseUuid).orElse(null);
    if (baseGuid != null) {
      ships
          .findByP4kUuid(baseGuid)
          .or(() -> ships.findByExternalUuid(baseGuid))
          .ifPresent(ship -> rows.put(ship.getId(), ship));
    }
    List<UUID> shipGuids =
        rows.values().stream()
            .map(ShipType::getP4kUuid)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    Map<UUID, ShipTypeP4kVariant> recordedVariants =
        shipGuids.isEmpty()
            ? Map.of()
            : variants.findAllById(shipGuids).stream()
                .collect(Collectors.toMap(ShipTypeP4kVariant::getVariantUuid, Function.identity()));
    List<P4kReviewCandidate> candidates = new ArrayList<>();
    for (ShipType ship : rows.values()) {
      UUID heldGuid = ship.getP4kUuid();
      ShipTypeP4kVariant variant = heldGuid == null ? null : recordedVariants.get(heldGuid);
      boolean stampedVariant = isStampedVariant(ship, guid, variant);
      boolean related =
          stampedVariant
              || (baseGuid != null
                  && (baseGuid.equals(heldGuid) || baseGuid.equals(ship.getExternalUuid())));
      boolean repairable =
          canQueueRepair(
              ship,
              review,
              stampedVariant,
              owner.isPresent(),
              incomingVariant.isPresent(),
              cases.existsByShipRepairRowId(ship.getId()),
              classMatches);
      P4kReviewAction action =
          repairable ? P4kReviewAction.CORRECT_P4K_GUID : P4kReviewAction.INSPECT;
      candidates.add(candidate(action, ship, review, related));
    }
    P4kReviewReadiness readiness =
        candidates.isEmpty() ? P4kReviewReadiness.BLOCKED : P4kReviewReadiness.CHECK_REQUIRED;
    if (candidates.stream()
        .anyMatch(candidate -> candidate.action() == P4kReviewAction.CORRECT_P4K_GUID)) {
      readiness = P4kReviewReadiness.ACTIONABLE;
    }
    return new P4kReviewOptions(readiness, review.nameEn(), List.copyOf(candidates));
  }

  @Override
  public @NotNull Map<UUID, P4kReviewReadiness> summarize(
      @NotNull List<P4kReviewCaseSource> reviews) {
    List<ShipType> stored = ships.findAll();
    P4kRowIndex<ShipType> index =
        new P4kRowIndex<>(
            stored, ShipType::getExternalUuid, ShipType::getClassName, ShipType::getName);
    Map<String, List<ShipType>> aliases = classAliases.byClassName(stored);
    Map<UUID, ShipTypeP4kVariant> recordedVariants =
        variants.findAll().stream()
            .collect(Collectors.toMap(ShipTypeP4kVariant::getVariantUuid, Function.identity()));
    List<P4kReviewCase> pending = cases.findByShipRepairRowIdIsNotNull();
    Set<UUID> pendingCases =
        pending.stream().map(P4kReviewCase::getId).collect(Collectors.toUnmodifiableSet());
    Set<UUID> pendingRows =
        pending.stream()
            .map(P4kReviewCase::getShipRepairRowId)
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());

    Map<UUID, P4kReviewReadiness> result = new HashMap<>();
    for (P4kReviewCaseSource entry : reviews) {
      P4kReviewCaseDto review = entry.review();
      if (pendingCases.contains(review.id())) {
        result.put(review.id(), P4kReviewReadiness.WAITING_FOR_IMPORT);
        continue;
      }
      UUID guid = review.guid();
      ShipType wiki = index.byWikiUuid(guid).orElse(null);
      if (wiki == null || review.reason() != P4kReviewReason.GUID_CONFLICT) {
        continue;
      }
      String className = review.className();
      List<ShipType> matches =
          className == null || className.isBlank()
              ? List.of()
              : P4kShipClassAliases.classMatches(index, aliases, className);
      boolean claimed = index.byP4kUuid(guid).isPresent();
      boolean variant = recordedVariants.containsKey(guid);
      UUID currentGuid = wiki.getP4kUuid();
      ShipTypeP4kVariant current = currentGuid == null ? null : recordedVariants.get(currentGuid);
      if (canAttach(wiki, review, claimed, recordedVariants.get(guid), matches)
          || canQueueRepair(
              wiki,
              review,
              isStampedVariant(wiki, guid, current),
              claimed,
              variant,
              pendingRows.contains(wiki.getId()),
              matches)) {
        result.put(review.id(), P4kReviewReadiness.ACTIONABLE);
      }
    }
    return Map.copyOf(result);
  }

  private static boolean canAttach(
      ShipType ship,
      P4kReviewCaseDto review,
      boolean claimed,
      @Nullable ShipTypeP4kVariant variant,
      List<ShipType> matches) {
    return !claimed
        && (variant == null
            || Objects.equals(
                P4kMatchKeys.fold(variant.getClassName()), P4kMatchKeys.fold(review.className())))
        && ship.getP4kUuid() == null
        && isEligibleWikiRow(ship, review, matches)
        && exactClass(ship, review);
  }

  private static boolean canQueueRepair(
      ShipType ship,
      P4kReviewCaseDto review,
      boolean stampedVariant,
      boolean claimed,
      boolean variant,
      boolean pending,
      List<ShipType> matches) {
    String className = review.className();
    return stampedVariant
        && !variant
        && !claimed
        && !pending
        && className != null
        && !className.isBlank()
        && !exactClass(ship, review)
        && isEligibleWikiRow(ship, review, matches);
  }

  private static boolean isEligibleWikiRow(
      ShipType ship, P4kReviewCaseDto review, List<ShipType> matches) {
    return review.reason() == P4kReviewReason.GUID_CONFLICT
        && review.guid().equals(ship.getExternalUuid())
        && ship.getVersion() != null
        && !ship.isHidden()
        && !ship.isP4kRetired()
        && wikiLive(ship)
        && matches.stream().allMatch(match -> ship.getId().equals(match.getId()));
  }

  private static boolean isStampedVariant(
      ShipType ship, UUID baseGuid, @Nullable ShipTypeP4kVariant variant) {
    return variant != null
        && baseGuid.equals(variant.getBaseUuid())
        && Objects.equals(
            P4kMatchKeys.fold(variant.getClassName()), P4kMatchKeys.fold(ship.getClassName()));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void apply(
      @NotNull P4kReviewCaseDto review,
      @NotNull P4kReviewCandidate candidate,
      @NotNull UUID adminUserId) {
    if (candidate.action() == P4kReviewAction.CANCEL_SHIP_REPAIR) {
      Entities.require(cases.findById(review.id()), "Ship review case not found")
          .cancelShipRepair();
      return;
    }
    Long version = candidate.targetVersion();
    if (version == null
        || (candidate.action() != P4kReviewAction.ATTACH_P4K
            && candidate.action() != P4kReviewAction.CORRECT_P4K_GUID)) {
      throw new BadRequestException("The ship identity is unavailable for attachment.");
    }
    UUID id = candidate.targetId();
    ShipType ship = Entities.require(ships.findById(id), "Ship type not found");
    OptimisticLock.checkRequired(ship.getVersion(), version, ShipType.class, id);
    if (candidate.action() == P4kReviewAction.CORRECT_P4K_GUID) {
      P4kReviewCase pending =
          Entities.require(cases.findById(review.id()), "Ship review case not found");
      pending.queueShipRepair(
          id,
          Objects.requireNonNull(ship.getP4kUuid()),
          Objects.requireNonNull(ship.getClassName()),
          Objects.requireNonNull(review.className()).strip(),
          adminUserId);
      return;
    }
    ship.setP4kUuid(review.guid());
    ships.saveAndFlush(ship);
  }

  /**
   * Identifies a live Wiki-backed ship even when UEX also carries its external UUID.
   *
   * @param ship the catalogue row
   * @return whether the SC Wiki owns a current copy
   */
  static boolean wikiLive(@NotNull ShipType ship) {
    return (ship.getSourceSystems() == GameItemSourceSystem.WIKI_ONLY
            || ship.getSourceSystems() == GameItemSourceSystem.BOTH)
        && ship.getScwikiDeletedAt() == null;
  }

  /**
   * Compares the stored and imported engine class names.
   *
   * @param ship the catalogue row
   * @param review the incoming record
   * @return whether both name the same nonblank class
   */
  private static boolean exactClass(@NotNull ShipType ship, @NotNull P4kReviewCaseDto review) {
    String key = P4kMatchKeys.fold(review.className());
    return key != null && !key.isEmpty() && key.equals(P4kMatchKeys.fold(ship.getClassName()));
  }

  /**
   * Presents the observed identities and class evidence for a catalogue row.
   *
   * @param action the permitted action
   * @param ship the catalogue row
   * @param review the incoming record
   * @param relatedVariant whether a recorded variant links the row to the reviewed GUID
   * @return the proposal or inspection target
   */
  private static P4kReviewCandidate candidate(
      @NotNull P4kReviewAction action,
      @NotNull ShipType ship,
      @NotNull P4kReviewCaseDto review,
      boolean relatedVariant) {
    List<P4kReviewEvidence> evidence = new ArrayList<>();
    if (review.guid().equals(ship.getExternalUuid()) && wikiLive(ship)) {
      evidence.add(P4kReviewEvidence.EXACT_SCWIKI_UUID);
    }
    if (exactClass(ship, review)) {
      evidence.add(P4kReviewEvidence.EXACT_CLASS);
    }
    if (review.guid().equals(ship.getP4kUuid())) {
      evidence.add(P4kReviewEvidence.EXACT_P4K_GUID);
    } else if (ship.getP4kUuid() != null) {
      evidence.add(P4kReviewEvidence.OTHER_P4K_GUID);
    }
    if (ship.getId().equals(review.rowId()) || ship.getId().equals(review.otherRowId())) {
      evidence.add(P4kReviewEvidence.CURRENT_ROW);
    }
    if (relatedVariant) {
      evidence.add(P4kReviewEvidence.P4K_VARIANT);
    }
    Integer uexId = ship.getUexVehicleId();
    return new P4kReviewCandidate(
        action,
        ship.getId(),
        ship.getVersion(),
        null,
        ship.getName(),
        ship.getClassName(),
        ship.getP4kUuid(),
        wikiLive(ship) ? ship.getExternalUuid() : null,
        uexId == null ? List.of() : List.of(uexId),
        List.copyOf(evidence));
  }
}
