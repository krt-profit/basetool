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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRemovedByDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeTombstoneDto;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;

/**
 * Reads the change feed for the exchange's resource feeds: where a snapshot starts, whether a
 * cursor is still served, and which keys changed after a position (REQ-XCH-013, ADR-0224).
 */
@Service
@RequiredArgsConstructor
public class ExchangeFeedReader {

  /** The largest page a client may ask for, the published schema's limit. */
  public static final int MAX_LIMIT = 1000;

  /** The page size when the client names none. */
  public static final int DEFAULT_LIMIT = 500;

  private final ExchangeChangeRepository changeRepository;
  private final ExchangeInstallationRepository installationRepository;
  private final ExchangeChangeRetentionService retentionService;

  /**
   * Clamps a requested page size.
   *
   * @param limit the requested size
   * @return the size, from 1 to {@value #MAX_LIMIT}
   */
  public static int pageSize(int limit) {
    return Math.clamp(limit, 1, MAX_LIMIT);
  }

  /**
   * Returns the position a new snapshot is taken at: every finished transaction is in the rows it
   * reads, every later one follows in the feed.
   *
   * @return the position
   */
  public @NotNull ExchangeFeedPosition snapshotStart() {
    return new ExchangeFeedPosition(changeRepository.watermark(), 0);
  }

  /**
   * Reads a cursor a client echoed and checks that the feed still holds everything after it.
   *
   * @param cursor the cursor
   * @return the cursor's position
   * @throws ExchangeProblemException {@code 410 CURSOR_EXPIRED} for a cursor the server did not
   *     issue or one older than the retained changes
   */
  public @NotNull ExchangeFeedCursor resume(@NotNull String cursor) {
    ExchangeFeedCursor parsed = ExchangeFeedCursor.parse(cursor);
    if (parsed.position().isBefore(retentionService.horizon())) {
      throw ExchangeProblemException.cursorExpired();
    }
    return parsed;
  }

  /**
   * Returns the keys of one resource that changed after a position, each once in the order of its
   * latest change, only from finished transactions.
   *
   * @param member the member
   * @param resource the resource
   * @param after the position
   * @param size the page size
   * @return the keys, how each was last removed, and where the next page starts
   */
  public @NotNull Changes changes(
      @NotNull UUID member,
      @NotNull ExchangeResource resource,
      @NotNull ExchangeFeedPosition after,
      int size) {
    long watermark = changeRepository.watermark();
    List<ExchangeChangeRepository.ChangedKey> changed =
        changeRepository.findChangedKeys(
            member, resource.name(), after.tx(), after.seq(), watermark, size + 1);
    boolean more = changed.size() > size;
    List<ExchangeChangeRepository.ChangedKey> delivered = more ? changed.subList(0, size) : changed;
    ExchangeFeedPosition next;
    if (more) {
      ExchangeChangeRepository.ChangedKey last = delivered.getLast();
      next = new ExchangeFeedPosition(last.getTx(), last.getSeq());
    } else {
      ExchangeFeedPosition caughtUp = new ExchangeFeedPosition(watermark, 0);
      next = after.isBefore(caughtUp) ? caughtUp : after;
    }
    return new Changes(
        delivered.stream().map(ExchangeChangeRepository.ChangedKey::getEntityKey).toList(),
        removals(member, delivered),
        next,
        more);
  }

  /**
   * Describes how each delivered key was last changed, for the tombstone of one that is gone.
   *
   * @param member the member
   * @param delivered the delivered keys
   * @return the removal by key
   */
  private @NotNull Map<String, Removal> removals(
      @NotNull UUID member, @NotNull List<ExchangeChangeRepository.ChangedKey> delivered) {
    if (delivered.isEmpty()) {
      return Map.of();
    }
    Map<Long, ExchangeChange> latest =
        changeRepository
            .findAllById(
                delivered.stream().map(ExchangeChangeRepository.ChangedKey::getSeq).toList())
            .stream()
            .collect(Collectors.toMap(ExchangeChange::getSeq, Function.identity()));
    Map<String, String> installations = installationIds(member, latest.values());
    Map<String, Removal> removals = new HashMap<>();
    for (ExchangeChangeRepository.ChangedKey key : delivered) {
      ExchangeChange change = latest.get(key.getSeq());
      removals.put(
          key.getEntityKey(),
          new Removal(
              change.getChangedAt(),
              new ExchangeRemovedByDto(
                  change.getSourceChannel(),
                  change.getSourceClient(),
                  change.getSourceKey() == null
                      ? null
                      : installations.get(
                          change.getSourceClient() + "|" + change.getSourceKey()))));
    }
    return removals;
  }

  /**
   * Resolves the installation ids of the clients that made the given changes.
   *
   * @param member the member
   * @param changes the changes
   * @return the opaque installation id by {@code client|key}
   */
  private @NotNull Map<String, String> installationIds(
      @NotNull UUID member, @NotNull Collection<ExchangeChange> changes) {
    List<String> keys =
        changes.stream()
            .map(ExchangeChange::getSourceKey)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (keys.isEmpty()) {
      return Map.of();
    }
    return installationRepository.findAllByUserAndKeys(member, keys).stream()
        .collect(
            Collectors.toMap(
                (ExchangeInstallation i) ->
                    i.getClient().getClientId() + "|" + i.getKeyThumbprint(),
                i -> i.getId().toString(),
                (a, b) -> a));
  }

  /**
   * The keys of one feed page.
   *
   * @param keys the changed keys in feed order
   * @param removals how each key was last changed
   * @param next where the next page starts
   * @param more whether more changes follow now
   */
  public record Changes(
      @NotNull @Unmodifiable List<String> keys,
      @NotNull Map<String, Removal> removals,
      @NotNull ExchangeFeedPosition next,
      boolean more) {

    /**
     * Builds the tombstone of a key that is gone.
     *
     * @param key the key as the feed recorded it
     * @param opaqueKey the key as the client knows it
     * @return the tombstone
     */
    public @NotNull ExchangeTombstoneDto tombstone(@NotNull String key, @NotNull String opaqueKey) {
      Removal removal = removals.get(key);
      return new ExchangeTombstoneDto(opaqueKey, removal.at(), removal.by());
    }

    /**
     * The cursor of the next page.
     *
     * @return the cursor
     */
    public @NotNull String nextCursor() {
      return ExchangeFeedCursor.feed(next).format();
    }
  }

  /**
   * When and by whom a key was last changed.
   *
   * @param at when
   * @param by who, as the tombstone names it
   */
  public record Removal(@NotNull Instant at, @NotNull ExchangeRemovedByDto by) {}
}
