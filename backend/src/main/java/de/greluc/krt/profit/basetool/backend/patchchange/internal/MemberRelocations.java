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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRelocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRelocation;
import de.greluc.krt.profit.basetool.backend.patchchange.api.HomeLocationChoiceRefusedException;
import de.greluc.krt.profit.basetool.backend.patchchange.api.HomeLocationChoiceRefusedException.Reason;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchChangeExchangeView;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchChangeExchangeWriter;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchChangeWrites;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a member's relocation after the home-location choice (REQ-PATCH-022, REQ-PATCH-025,
 * REQ-PATCH-026, REQ-PATCH-052, ADR-0383): one transaction per member, all or nothing, exactly once
 * per phase. The member's stock rows and ships from before the phase's live time move in place to
 * the chosen home location, and the exchange feed attributes every change to the phase's patch
 * change.
 */
@Service
@RequiredArgsConstructor
public class MemberRelocations implements PatchChangeExchangeWriter {

  private final PatchChangeRepository repository;
  private final PatchChangeRelocationRepository relocations;
  private final HomeLocationPhases phases;
  private final HomeLocationOffer offer;
  private final StockRelocation stock;
  private final ShipRelocation ships;
  private final AuditRecorder auditRecorder;
  private final PatchChangeLiveSync liveSync;
  private final PlatformTransactionManager transactionManager;

  /**
   * Stores a member's choice in the open phase and relocates the member.
   *
   * @param member the member who relocates
   * @param locationId the chosen home location
   * @param chooser who chose
   * @return what moved
   * @throws HomeLocationChoiceRefusedException without an open phase, when the phase does not ask
   *     the member, when the member chose before, or when the location is not a curated home
   *     location on offer
   */
  public @NotNull RelocationResultDto relocate(
      @NotNull UUID member, @NotNull UUID locationId, @NotNull RelocationChooser chooser) {
    return relocate(member, locationId, chooser, null);
  }

  @Override
  public @NotNull Chosen chooseHomeLocation(
      @NotNull UUID member, @NotNull UUID locationId, @NotNull String clientId) {
    RelocationResultDto result = relocate(member, locationId, RelocationChooser.CLIENT, clientId);
    HomeLocationOptionDto location = result.location();
    return new Chosen(
        new PatchChangeExchangeView.Place(location.id(), location.name(), location.system()),
        result.rows(),
        result.ships());
  }

  /**
   * Stores a member's choice in the open phase and relocates the member, in its own transaction
   * attributed to the phase's patch change.
   *
   * @param member the member who relocates
   * @param locationId the chosen home location
   * @param chooser who chose
   * @param clientId the exchange client that chose, or {@code null} for a choice on the web
   * @return what moved
   */
  private @NotNull RelocationResultDto relocate(
      @NotNull UUID member,
      @NotNull UUID locationId,
      @NotNull RelocationChooser chooser,
      @Nullable String clientId) {
    HomeLocationPhase phase = phases.open().orElseThrow(MemberRelocations::phaseNotOpen);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    return PatchChangeWrites.runFor(
        phase.id(),
        () ->
            Objects.requireNonNull(
                transaction.execute(_ ->
                    relocateWithinTransaction(phase.id(), member, locationId, chooser, clientId))));
  }

  /**
   * Checks the phase and the member under the history's shared lock, claims the relocation, moves
   * the stock and the ships and records the result.
   *
   * @param phaseId the phase the writes are attributed to
   * @param member the member
   * @param locationId the chosen home location
   * @param chooser who chose
   * @param clientId the exchange client that chose, or {@code null} for a choice on the web
   * @return what moved
   */
  private @NotNull RelocationResultDto relocateWithinTransaction(
      @NotNull UUID phaseId,
      @NotNull UUID member,
      @NotNull UUID locationId,
      @NotNull RelocationChooser chooser,
      @Nullable String clientId) {
    repository.lockHistoryShared();
    HomeLocationPhase phase =
        repository
            .findFirstByHomeLocationPromptTrueOrderByLiveAtDesc()
            .filter(record -> record.getPhaseEndedAt() == null)
            .filter(record -> record.getId().equals(phaseId))
            .map(HomeLocationPhase::of)
            .orElseThrow(MemberRelocations::phaseNotOpen);
    HomeLocationOptionDto chosen = HomeLocationOffer.toOption(offer.requireOffered(locationId));
    PatchChangeRelocation.Key key = new PatchChangeRelocation.Key(phase.id(), member);
    if (phases.pendingAmong(phase, List.of(member)).isEmpty()) {
      throw new HomeLocationChoiceRefusedException(
          relocations.existsById(key) ? Reason.ALREADY_CHOSEN : Reason.NOT_ASKED);
    }
    if (relocations.claim(phase.id(), member, chosen.id(), chooser.name(), Instant.now()) == 0) {
      throw new HomeLocationChoiceRefusedException(Reason.ALREADY_CHOSEN);
    }
    int rows = stock.relocate(member, phase.liveAt(), chosen.id());
    int shipCount = ships.relocate(member, phase.liveAt(), chosen.id());
    relocations.recordMoved(phase.id(), member, rows, shipCount);
    AuditDetails details =
        AuditDetails.of("patchChange", phase.id())
            .with("label", phase.label())
            .with("location", chosen.name())
            .with("rows", rows)
            .with("ships", shipCount)
            .with("chosenBy", chooser);
    auditRecorder.record(
        AuditEventType.PATCH_CHANGE_RELOCATED,
        member,
        null,
        member,
        clientId == null ? details : details.with("client", clientId));
    liveSync.historyChanged();
    return new RelocationResultDto(chosen, rows, shipCount);
  }

  /**
   * The refusal of a choice without an open phase.
   *
   * @return the refusal
   */
  private static @NotNull HomeLocationChoiceRefusedException phaseNotOpen() {
    return new HomeLocationChoiceRefusedException(Reason.PHASE_NOT_OPEN);
  }
}
