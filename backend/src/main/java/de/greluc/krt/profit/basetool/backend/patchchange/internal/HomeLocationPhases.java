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

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.kernel.RequestMemo;
import de.greluc.krt.profit.basetool.backend.patchchange.api.HomeLocationGateCheck;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeRelocationRepository.PhaseCounts;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers who a home-location phase asks and who must still choose (REQ-PATCH-021, REQ-PATCH-024).
 * Only the latest patch change with the prompt on can hold an open phase: a later one with the
 * prompt on supersedes it and carries the lock over, one without the prompt leaves it open, and an
 * admin may end it early.
 */
@Service
@RequiredArgsConstructor
public class HomeLocationPhases implements HomeLocationGateCheck {

  /** The open phase, read once per request; nothing in one request opens or ends it twice. */
  private static final RequestMemo.Key<Optional<HomeLocationPhase>> OPEN_PHASE =
      RequestMemo.Key.of(HomeLocationPhases.class, "openPhase");

  private final PatchChangeRepository repository;
  private final PatchChangeRelocationRepository relocations;

  /**
   * Returns the open home-location phase.
   *
   * @return the phase, or empty when none is open
   */
  @Transactional(readOnly = true)
  public @NotNull Optional<HomeLocationPhase> open() {
    Optional<HomeLocationPhase> memo = RequestMemo.getIfBound(OPEN_PHASE, this::readOpen);
    return memo != null ? memo : readOpen();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean mustChoose(@NotNull UUID userId) {
    return open().map(phase -> !pendingAmong(phase, List.of(userId)).isEmpty()).orElse(false);
  }

  /**
   * Returns the members among the given ones who must still choose in a phase.
   *
   * @param phase the phase
   * @param users the members to look at
   * @return the pending members
   */
  @Transactional(readOnly = true)
  public @NotNull @Unmodifiable Set<UUID> pendingAmong(
      @NotNull HomeLocationPhase phase, @NotNull Collection<UUID> users) {
    if (users.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(
        relocations.findPending(
            Set.copyOf(users), phase.id(), phase.liveAt(), ApprovalStatus.ACTIVE));
  }

  /**
   * Returns every member who must still choose in a phase.
   *
   * @param phase the phase
   * @return the pending members
   */
  @Transactional(readOnly = true)
  public @NotNull @Unmodifiable List<UUID> allPending(@NotNull HomeLocationPhase phase) {
    return List.copyOf(
        relocations.findAllPending(phase.id(), phase.liveAt(), ApprovalStatus.ACTIVE));
  }

  /**
   * Counts the members a phase with a live time asks.
   *
   * @param liveAt the live time
   * @return how many active members' accounts are older than it
   */
  @Transactional(readOnly = true)
  public long countAsked(@NotNull Instant liveAt) {
    return relocations.countAsked(liveAt, ApprovalStatus.ACTIVE);
  }

  /**
   * Counts the members who must still choose in the open phase.
   *
   * @return the pending members, {@code 0} while no phase is open
   */
  @Transactional(readOnly = true)
  public long countPending() {
    return readOpen()
        .map(phase ->
            countAsked(phase.liveAt())
                - relocations.countRelocated(phase.id(), phase.liveAt(), ApprovalStatus.ACTIVE))
        .orElse(0L);
  }

  /**
   * Describes the phases of patch changes for the history, counting every phase's members in one
   * statement.
   *
   * @param records the patch changes, flushed
   * @param latestPromptId the latest patch change with the prompt on, or {@code null}
   * @return the phase by patch change id; a patch change that did not ask for a home location is
   *     absent
   */
  @Transactional(readOnly = true)
  public @NotNull @Unmodifiable Map<UUID, PatchChangePhaseDto> phasesOf(
      @NotNull Collection<PatchChange> records, @Nullable UUID latestPromptId) {
    List<PatchChange> prompted =
        records.stream().filter(PatchChange::isHomeLocationPrompt).toList();
    if (prompted.isEmpty()) {
      return Map.of();
    }
    Map<UUID, PhaseCounts> counts =
        relocations
            .countPhases(prompted.stream().map(PatchChange::getId).toList(), ApprovalStatus.ACTIVE)
            .stream()
            .collect(Collectors.toMap(PhaseCounts::getPatchChangeId, Function.identity()));
    return prompted.stream()
        .collect(
            Collectors.toUnmodifiableMap(
                PatchChange::getId,
                record ->
                    phase(
                        record,
                        latestPromptId,
                        Objects.requireNonNull(counts.get(record.getId()), "phase counts"))));
  }

  /**
   * Describes one patch change's phase.
   *
   * @param record the patch change, which asked for a home location
   * @param latestPromptId the latest patch change with the prompt on, or {@code null}
   * @param counts its asked and relocated members
   * @return the phase
   */
  private static @NotNull PatchChangePhaseDto phase(
      @NotNull PatchChange record, @Nullable UUID latestPromptId, @NotNull PhaseCounts counts) {
    PatchChangePhaseState state;
    if (record.getPhaseEndedAt() != null) {
      state = PatchChangePhaseState.ENDED;
    } else if (Objects.equals(record.getId(), latestPromptId)) {
      state = PatchChangePhaseState.OPEN;
    } else {
      state = PatchChangePhaseState.SUPERSEDED;
    }
    return new PatchChangePhaseDto(state, counts.getAsked(), counts.getRelocated());
  }

  /**
   * Returns the latest patch change with the prompt on.
   *
   * @return its id, or {@code null} when none asked
   */
  @Transactional(readOnly = true)
  public @Nullable UUID latestPromptId() {
    return repository
        .findFirstByHomeLocationPromptTrueOrderByLiveAtDesc()
        .map(PatchChange::getId)
        .orElse(null);
  }

  /**
   * Reads the open phase from the history.
   *
   * @return the phase, or empty when none is open
   */
  private @NotNull Optional<HomeLocationPhase> readOpen() {
    return repository
        .findFirstByHomeLocationPromptTrueOrderByLiveAtDesc()
        .filter(record -> record.getPhaseEndedAt() == null)
        .map(HomeLocationPhase::of);
  }
}
