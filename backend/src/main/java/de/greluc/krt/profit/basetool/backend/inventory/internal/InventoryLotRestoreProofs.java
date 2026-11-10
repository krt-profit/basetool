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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.LegacyJournalQuantityReference;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityComposition;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityEvidence;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Validates restored physical evidence against every retained current counterpart. */
@Component
@RequiredArgsConstructor
final class InventoryLotRestoreProofs {
  private final InventoryItemRepository rows;
  private final ObjectMapper objectMapper;

  /** Returns the typed identities of every actual part the accepted program can add. */
  Set<InventoryQuantityEvidenceLockRepository.Identity> additions(
      InventoryPoolRestoreProgram prefix, InventoryLotBatchProgram program) {
    var required = new HashSet<InventoryQuantityEvidenceLockRepository.Identity>();
    prefix.steps().forEach(step -> required.addAll(identities(additions(step).parts())));
    program.steps().values().stream()
        .flatMap(step -> step.mutations().stream())
        .filter(InventoryLotBatchProgram.RestoredBookIn.class::isInstance)
        .map(InventoryLotBatchProgram.RestoredBookIn.class::cast)
        .forEach(booking -> required.addAll(identities(booking.quantity().parts())));
    return Set.copyOf(required);
  }

  /** Selects only parts newly introduced by one actual named historical mutation. */
  static StockQuantityComposition additions(InventoryPoolRestoreProgram.Step step) {
    var target = step.decision().targetState();
    return !step.decision().changes() || target == null
        ? StockQuantityComposition.EMPTY
        : InventoryQuantityComposition.subtract(
            Objects.requireNonNull(target.completeQuantity()),
            step.before()
                .map(InventoryPoolRestoreFacts.Entry::quantity)
                .orElse(StockQuantityComposition.EMPTY));
  }

  /** Retains the identity domain separately from every raw numeric advisory-key representation. */
  static Set<InventoryQuantityEvidenceLockRepository.Identity> identities(
      Collection<StockQuantityEvidence> parts) {
    return parts.stream()
        .map(part ->
            switch (part) {
              case StockQuantityEvidence.KnownPhysical known ->
                  new InventoryQuantityEvidenceLockRepository.Identity(
                      InventoryQuantityEvidenceLockRepository.Kind.PHYSICAL,
                      known.quantity().origin());
              case LegacyJournalQuantityReference reference ->
                  new InventoryQuantityEvidenceLockRepository.Identity(
                      InventoryQuantityEvidenceLockRepository.Kind.JOURNAL_REFERENCE,
                      reference.journalReference());
            })
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  /** Declares proof observations without requiring proposed historical targets to be disjoint. */
  List<StockQuantityEvidence> interest(
      InventoryPoolRestoreProgram prefix, InventoryLotBatchProgram program) {
    var parts = new ArrayList<StockQuantityEvidence>();
    program.finalQuantities().values().forEach(quantity -> parts.addAll(quantity.parts()));
    prefix.steps().stream()
        .filter(step -> step.decision().changes() && step.decision().targetState() != null)
        .forEach(step ->
            parts.addAll(
                Objects.requireNonNull(
                        Objects.requireNonNull(step.decision().targetState()).completeQuantity())
                    .parts()));
    return List.copyOf(parts);
  }

  /** Returns the exact current physical rows overlapping the declared proof in one scalar query. */
  Set<UUID> overlapping(List<StockQuantityEvidence> parts) {
    return parts.isEmpty()
        ? Set.of()
        : Set.copyOf(rows.findQuantityEvidenceRowIds(objectMapper.writeValueAsString(parts)));
  }

  /** Returns actual current holder/lot observations without granting material write authority. */
  Set<Long> lots(Map<UUID, Optional<InventoryPoolRestoreFacts.Entry>> facts) {
    return facts.values().stream()
        .flatMap(Optional::stream)
        .map(InventoryPoolRestoreFacts.Entry::row)
        .map(row ->
            InventoryLotLockRepository.lotKey(
                row.getUser().getId(),
                StockLot.key(
                    row.getMaterial() == null ? null : row.getMaterial().getId(),
                    row.getGameItem() == null ? null : row.getGameItem().getId(),
                    row.getLocation().getId(),
                    row.getQuality(),
                    row.getStolen())))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  /** Checks the complete final world after the final accepted and refused prefix decisions. */
  void requireUnique(
      Map<UUID, Optional<InventoryPoolRestoreFacts.Entry>> current,
      InventoryPoolRestoreProgram prefix,
      InventoryLotBatchProgram program,
      Set<InventoryLotBatchProgram.RowKey> personalWorld) {
    var retained = new HashMap<InventoryLotBatchProgram.RowKey, StockQuantityComposition>();
    current.forEach((id, value) ->
        value.ifPresent(
            entry -> retained.put(new InventoryLotBatchProgram.Existing(id), entry.quantity())));
    for (var step : prefix.steps()) {
      if (!step.decision().changes()) {
        continue;
      }
      var key = new InventoryLotBatchProgram.Existing(step.intent().request().entryId());
      retained.remove(key);
      var target = step.decision().targetState();
      if (target != null) {
        retained.put(key, Objects.requireNonNull(target.completeQuantity()));
      }
    }
    personalWorld.forEach(retained::remove);
    retained.putAll(program.finalQuantities());
    try {
      InventoryQuantityComposition.validated(
          new StockQuantityComposition(
              retained.values().stream().flatMap(quantity -> quantity.parts().stream()).toList()));
    } catch (IllegalArgumentException _) {
      throw new BusinessConflictException("error.inventory.concurrent_scope_changed");
    }
  }
}
