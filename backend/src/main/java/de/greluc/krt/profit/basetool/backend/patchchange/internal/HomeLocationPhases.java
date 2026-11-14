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
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
   * Describes a patch change's phase for the history.
   *
   * @param record the patch change
   * @param latestPromptId the latest patch change with the prompt on, or {@code null}
   * @return the phase, or {@code null} when the patch change did not ask for a home location
   */
  @Transactional(readOnly = true)
  public @Nullable PatchChangePhaseDto phaseOf(
      @NotNull PatchChange record, @Nullable UUID latestPromptId) {
    if (!record.isHomeLocationPrompt()) {
      return null;
    }
    PatchChangePhaseState state;
    if (record.getPhaseEndedAt() != null) {
      state = PatchChangePhaseState.ENDED;
    } else if (Objects.equals(record.getId(), latestPromptId)) {
      state = PatchChangePhaseState.OPEN;
    } else {
      state = PatchChangePhaseState.SUPERSEDED;
    }
    return new PatchChangePhaseDto(
        state,
        countAsked(record.getLiveAt()),
        relocations.countRelocated(record.getId(), record.getLiveAt(), ApprovalStatus.ACTIVE));
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
