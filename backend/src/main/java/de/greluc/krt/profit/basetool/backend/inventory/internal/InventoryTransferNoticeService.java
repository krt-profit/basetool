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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.events.InventoryTransferredFromUserEvent;
import de.greluc.krt.profit.basetool.backend.inventory.api.events.InventoryTransferredToUserEvent;
import de.greluc.krt.profit.basetool.backend.inventory.api.events.TransferredLot;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Builds transfer notifications from immutable move snapshots. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryTransferNoticeService {
  private static final String UNKNOWN_NAME = "—";
  private final UserRepository userRepository;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Announces the owner changes of one transfer action (REQ-INV-055): one {@link
   * InventoryTransferredToUserEvent} per new owner and one {@link
   * InventoryTransferredFromUserEvent} per previous owner and new owner, each listing its lots in
   * booking order. Moves that keep the owner announce nothing, and the actor is never a recipient.
   *
   * @param actorId the member making the transfer
   * @param moves the rows the action moved, in booking order
   */
  public void publishTransferNotifications(
      @NotNull UUID actorId, @NotNull List<TransferMove> moves) {
    final Map<UUID, List<TransferMove>> toNewOwner = new LinkedHashMap<>();
    final Map<List<UUID>, List<TransferMove>> fromPreviousOwner = new LinkedHashMap<>();
    for (TransferMove move : moves) {
      final UUID fromId = move.from().getId();
      final UUID toId = move.to().getId();
      if (fromId.equals(toId)) {
        continue;
      }
      if (!toId.equals(actorId)) {
        toNewOwner.computeIfAbsent(toId, _ -> new ArrayList<>()).add(move);
      }
      if (!fromId.equals(actorId)) {
        fromPreviousOwner.computeIfAbsent(List.of(fromId, toId), _ -> new ArrayList<>()).add(move);
      }
    }
    if (toNewOwner.isEmpty() && fromPreviousOwner.isEmpty()) {
      return;
    }
    final String actorName = actorName(actorId, moves);
    toNewOwner.forEach((newOwnerId, owned) ->
        eventPublisher.publishEvent(
            new InventoryTransferredToUserEvent(
                newOwnerId,
                actorId,
                actorName,
                owned.getFirst().sourceRowId(),
                owned.stream().map(TransferMove::toLot).toList())));
    fromPreviousOwner.forEach((pair, owned) ->
        eventPublisher.publishEvent(
            new InventoryTransferredFromUserEvent(
                pair.getFirst(),
                actorId,
                actorName,
                effectiveName(owned.getFirst().to()),
                owned.getFirst().sourceRowId(),
                owned.stream().map(TransferMove::fromLot).toList())));
  }

  /**
   * Resolves the actor's effective name, from the moved rows' owners when the actor is one of them
   * and from the user table otherwise.
   *
   * @param actorId the member making the transfer
   * @param moves the moves of the action
   * @return the actor's effective name, or an em dash when the actor is unknown
   */
  @NotNull
  public String actorName(@NotNull UUID actorId, @NotNull List<TransferMove> moves) {
    for (TransferMove move : moves) {
      if (move.from().getId().equals(actorId)) {
        return effectiveName(move.from());
      }
      if (move.to().getId().equals(actorId)) {
        return effectiveName(move.to());
      }
    }
    return userRepository
        .findById(actorId)
        .map(InventoryTransferNoticeService::effectiveName)
        .orElse(UNKNOWN_NAME);
  }

  /**
   * A member's name as notifications show it.
   *
   * @param user the member
   * @return the effective name, or an em dash when the member has none
   */
  @NotNull
  private static String effectiveName(@NotNull User user) {
    return Objects.requireNonNullElse(
        StringNormalization.trimToNull(user.getEffectiveName()), UNKNOWN_NAME);
  }

  /**
   * One row a transfer action moved, snapshotted before the source row is reduced or deleted.
   *
   * @param sourceRowId the moved source row
   * @param from the row's owner before the move
   * @param to the row's owner after the move
   * @param name the row's catalogue name
   * @param amount the moved amount
   * @param piece whether the amount counts pieces
   * @param quality the row's quality, or {@code null}
   * @param fromLocation the source location's name, or {@code null}
   * @param toLocation the target location's name, or {@code null}
   */
  record TransferMove(
      @NotNull UUID sourceRowId,
      @NotNull User from,
      @NotNull User to,
      @NotNull String name,
      double amount,
      boolean piece,
      @Nullable Integer quality,
      @Nullable String fromLocation,
      @Nullable String toLocation) {

    /**
     * Snapshots a move of {@code item}.
     *
     * @param item the source row, read before it is reduced or deleted
     * @param sourceRowId the source row's id
     * @param from the row's owner before the move
     * @param to the row's owner after the move
     * @param amount the moved amount
     * @param fromLocation the source location, or {@code null}
     * @param toLocation the target location, or {@code null}
     * @return the move
     */
    @NotNull
    static TransferMove of(
        @NotNull InventoryItem item,
        @NotNull UUID sourceRowId,
        @NotNull User from,
        @NotNull User to,
        double amount,
        @Nullable Location fromLocation,
        @Nullable Location toLocation) {
      return new TransferMove(
          sourceRowId,
          from,
          to,
          InventoryAuditLabels.catalogName(item),
          amount,
          item.countsWholeUnits(),
          item.getQuality(),
          fromLocation != null ? fromLocation.getName() : null,
          toLocation != null ? toLocation.getName() : null);
    }

    /**
     * The lot as the new owner sees it, at its target location.
     *
     * @return the lot
     */
    @NotNull
    TransferredLot toLot() {
      return new TransferredLot(name, amount, piece, quality, toLocation);
    }

    /**
     * The lot as the previous owner sees it, at its source location.
     *
     * @return the lot
     */
    @NotNull
    TransferredLot fromLot() {
      return new TransferredLot(name, amount, piece, quality, fromLocation);
    }
  }
}
