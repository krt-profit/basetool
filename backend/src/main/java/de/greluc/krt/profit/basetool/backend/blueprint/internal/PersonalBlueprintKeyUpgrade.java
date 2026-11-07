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

package de.greluc.krt.profit.basetool.backend.blueprint.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.blueprint.api.ProductKeyChangeParticipant;
import de.greluc.krt.profit.basetool.backend.blueprint.api.ProductKeys;
import de.greluc.krt.profit.basetool.backend.blueprint.api.ResolvedProduct;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ProducedItemGuidFollower;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves the blueprint products from their fallback name keys onto their produced items' GUID keys
 * after a P4K APPLY (REQ-INV-070, REQ-P4K-029, ADR-0343). A name key that resolves to exactly one
 * GUID product moves in one step everywhere it is held: the aliases, the default set, every
 * participant's references, then the members' rows. A member who already holds the GUID row keeps
 * one row: the earliest acquisition date, the GUID row's note or else the fallback row's, and the
 * merge is audited. A member's name key that resolves to no GUID product or to several stays and is
 * reported for the review list; a later run that resolves it moves it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PersonalBlueprintKeyUpgrade implements ProducedItemGuidFollower {

  private final BlueprintProductService productService;
  private final PersonalBlueprintRepository personalRepository;
  private final DefaultBlueprintRepository defaultRepository;
  private final DefaultBlueprintKeyService defaultKeyService;
  private final BlueprintExternalAliasRepository aliasRepository;
  private final GameItemRepository gameItemRepository;
  private final List<ProductKeyChangeParticipant> participants;
  private final AuditRecorder auditRecorder;

  /**
   * Moves every name key that resolves to one GUID product and reports the members' name keys that
   * do not; joins the run's transaction.
   *
   * @param apply whether to move the keys; a preview only reports
   * @return the members' name keys left on their fallback, sorted by key
   */
  @Override
  @NotNull
  @Transactional(propagation = Propagation.MANDATORY)
  public List<UnresolvedProductKey> followProducedItemGuids(boolean apply) {
    Set<String> memberKeys = nameKeys(personalRepository.findAllDistinctProductKeys());
    Set<String> heldKeys = new TreeSet<>(memberKeys);
    heldKeys.addAll(nameKeys(defaultRepository.findAllProductKeys()));
    heldKeys.addAll(nameKeys(aliasRepository.findAllDistinctProductKeys()));
    participants.forEach(p -> heldKeys.addAll(nameKeys(p.referencedProductKeys())));

    Map<String, List<ResolvedProduct>> byName = productService.productsByNameKey();
    Tally tally = new Tally();
    List<UnresolvedProductKey> unresolved = new ArrayList<>();
    for (String nameKey : heldKeys) {
      List<ResolvedProduct> candidates = byName.getOrDefault(nameKey, List.of());
      ResolvedProduct target = candidates.size() == 1 ? candidates.getFirst() : null;
      if (target != null && ProductKeys.guidOf(target.productKey()) != null) {
        if (apply) {
          move(nameKey, target, tally);
        }
      } else if (memberKeys.contains(nameKey)) {
        unresolved.add(
            new UnresolvedProductKey(nameKey, productName(nameKey), candidates.size() > 1));
      }
    }
    if (tally.keys > 0) {
      defaultKeyService.refresh();
      auditRecorder.record(
          AuditEventType.BLUEPRINT_PRODUCT_KEYS_UPGRADED,
          null,
          null,
          null,
          AuditDetails.of("keys", tally.keys)
              .with("rows", tally.rows)
              .with("merged", tally.merged)
              .with("unresolved", unresolved.size()));
      log.info(
          "Blueprint product keys upgraded to produced-item GUIDs: keys={} rows={} merged={}"
              + " unresolved={}",
          tally.keys,
          tally.rows,
          tally.merged,
          unresolved.size());
    }
    return List.copyOf(unresolved);
  }

  /**
   * Moves one name key onto a GUID product everywhere it is held.
   *
   * @param nameKey the name key
   * @param target the GUID product
   * @param tally the counts of this run (mutated)
   */
  private void move(
      @NotNull String nameKey, @NotNull ResolvedProduct target, @NotNull Tally tally) {
    String guidKey = target.productKey();
    participants.forEach(p -> p.moveProductKey(nameKey, guidKey));
    aliasRepository.findAllByProductKey(nameKey).forEach(alias -> alias.setProductKey(guidKey));
    defaultRepository
        .findByProductKey(nameKey)
        .ifPresent(fallback -> {
          if (defaultRepository.existsByProductKey(guidKey)) {
            defaultRepository.delete(fallback);
          } else {
            fallback.setProductKey(guidKey);
          }
        });
    List<PersonalBlueprint> fallbacks = personalRepository.findAllByProductKey(nameKey);
    Map<UUID, PersonalBlueprint> guidRows = new HashMap<>();
    if (!fallbacks.isEmpty()) {
      personalRepository
          .findAllByProductKeyAndOwnerUserIdIn(
              guidKey, fallbacks.stream().map(PersonalBlueprint::getOwnerUserId).toList())
          .forEach(row -> guidRows.put(row.getOwnerUserId(), row));
    }
    for (PersonalBlueprint fallback : fallbacks) {
      PersonalBlueprint guidRow = guidRows.get(fallback.getOwnerUserId());
      if (guidRow == null) {
        fallback.setProductKey(guidKey);
        if (fallback.getOutputItem() == null && target.outputItemId() != null) {
          fallback.setOutputItem(
              gameItemRepository.getCanonicalReferenceById(target.outputItemId()));
        }
      } else {
        merge(fallback, guidRow);
        tally.merged++;
      }
      tally.rows++;
    }
    personalRepository.flush();
    tally.keys++;
  }

  /**
   * Merges a member's fallback row into the GUID row of the same product and audits it.
   *
   * @param fallback the row keyed by the name key, removed
   * @param guidRow the row keyed by the GUID key, kept
   */
  private void merge(@NotNull PersonalBlueprint fallback, @NotNull PersonalBlueprint guidRow) {
    guidRow.setAcquiredAt(earliest(guidRow.getAcquiredAt(), fallback.getAcquiredAt()));
    if (guidRow.getNote() == null || guidRow.getNote().isBlank()) {
      guidRow.setNote(fallback.getNote());
    }
    personalRepository.delete(fallback);
    personalRepository.flush();
    auditRecorder.record(
        AuditEventType.BLUEPRINT_PRODUCT_MERGED,
        guidRow.getId(),
        guidRow.getProductName(),
        guidRow.getOwnerUserId(),
        AuditDetails.of("from", fallback.getProductKey()).with("to", guidRow.getProductKey()));
  }

  /**
   * Returns the earlier of two acquisition dates.
   *
   * @param a one date, or {@code null}
   * @param b the other, or {@code null}
   * @return the earlier one; the present one when only one is; {@code null} when neither is
   */
  @Nullable
  private static Instant earliest(@Nullable Instant a, @Nullable Instant b) {
    if (a == null) {
      return b;
    }
    return b == null || a.isBefore(b) ? a : b;
  }

  /**
   * Reads the display name a member's row of a name key carries.
   *
   * @param nameKey the name key
   * @return the product name, or the key itself when no row names one
   */
  @NotNull
  private String productName(@NotNull String nameKey) {
    return personalRepository.findAllByProductKey(nameKey).stream()
        .map(PersonalBlueprint::getProductName)
        .findFirst()
        .orElse(nameKey);
  }

  /**
   * Keeps the name keys of a key set.
   *
   * @param keys product keys
   * @return the keys that are no GUID key
   */
  @NotNull
  private static Set<String> nameKeys(@NotNull Set<String> keys) {
    Set<String> names = new TreeSet<>();
    for (String key : keys) {
      if (key != null && !key.isEmpty() && ProductKeys.guidOf(key) == null) {
        names.add(key);
      }
    }
    return names;
  }

  /** The counts of one run. */
  private static final class Tally {
    private int keys;
    private int rows;
    private int merged;
  }
}
