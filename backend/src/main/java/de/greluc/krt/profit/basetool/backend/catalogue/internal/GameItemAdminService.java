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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin item list (REQ-P4K-048, ADR-0373): pages the catalogue's game items and hides one as not
 * obtainable in the game, or offers it again. A hidden item leaves every item picker and no new
 * write may take it ({@code SelectableGameItems}); its existing references stay. Each change records
 * one {@code CATALOGUE} audit event and reaches the other admins' item lists (REQ-FE-015).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GameItemAdminService {

  /** The holder name a change shows in the {@link SyncCoordinator} log lines. */
  static final String LOCK_LABEL = "Game item obtainability change";

  private final GameItemRepository gameItemRepository;
  private final AuditRecorder auditRecorder;
  private final CatalogueAdminLiveSync adminLiveSync;
  private final SyncCoordinator syncCoordinator;

  /** This service's proxy, so a change's transaction runs inside the lock. */
  private final ObjectProvider<GameItemAdminService> self;

  /**
   * Pages the game items whose name contains {@code query}: every item the P4K lists, or only the
   * ones an admin hid as not obtainable, the retired ones among them included.
   *
   * @param query a fragment of the item name, or {@code null} for all
   * @param unobtainableOnly whether only the hidden items are listed
   * @param pageable the page and its sort
   * @return the page of items
   */
  @NotNull
  public Page<GameItemAdminDto> items(
      @Nullable String query, boolean unobtainableOnly, @NotNull Pageable pageable) {
    String q = query == null ? "" : LikePatterns.escape(query);
    Page<GameItem> page =
        unobtainableOnly
            ? gameItemRepository.findUnobtainableByName(q, pageable)
            : gameItemRepository.findListedByName(q, pageable);
    return page.map(GameItemAdminDto::of);
  }

  /**
   * Hides a game item as not obtainable in the game, or offers it again. The request names the
   * state it wants, so a repeated or a raced request is idempotent and an unchanged state records
   * nothing.
   *
   * @param id the item, live or retained
   * @param obtainable {@code false} to hide the item, {@code true} to offer it again
   * @return the item after the change
   * @throws NotFoundException when no item has the id
   * @throws BusinessConflictException when an APPLY or a Wiki or UEX sync holds the {@link
   *     SyncCoordinator} lock over the rows it writes
   */
  @Transactional(propagation = Propagation.SUPPORTS)
  @NotNull
  public GameItemAdminDto setObtainable(@NotNull UUID id, boolean obtainable) {
    AtomicReference<GameItemAdminDto> changed = new AtomicReference<>();
    boolean ran =
        syncCoordinator.tryRunExclusively(
            LOCK_LABEL,
            () -> changed.set(self.getObject().setObtainableWithinLock(id, obtainable)));
    if (!ran) {
      throw new BusinessConflictException(
          "A P4K import or a Wiki or UEX sync is running; change the item once it has finished.");
    }
    return Objects.requireNonNull(changed.get());
  }

  /**
   * Hides or offers an item in its own transaction; only {@link #setObtainable} calls it, holding
   * the {@link SyncCoordinator} lock for the whole transaction.
   *
   * @param id the item, live or retained
   * @param obtainable {@code false} to hide the item, {@code true} to offer it again
   * @return the item after the change
   * @throws NotFoundException when no item has the id
   */
  @Transactional
  @NotNull
  public GameItemAdminDto setObtainableWithinLock(@NotNull UUID id, boolean obtainable) {
    GameItem item =
        Entities.require(
            gameItemRepository.findById(id), () -> "Game item " + id + " was not found.");
    boolean unobtainable = !obtainable;
    if (item.isUnobtainable() != unobtainable) {
      item.setUnobtainableSince(unobtainable ? Instant.now() : null);
      gameItemRepository.saveAndFlush(item);
      auditRecorder.record(
          AuditEventType.CATALOGUE_GAME_ITEM_OBTAINABILITY_CHANGED,
          item.getId(),
          item.getName(),
          null,
          AuditDetails.of("obtainable", obtainable));
      adminLiveSync.itemsChanged();
    }
    return GameItemAdminDto.of(item);
  }
}
