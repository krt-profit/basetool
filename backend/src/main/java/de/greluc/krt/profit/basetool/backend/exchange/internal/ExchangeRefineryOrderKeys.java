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

import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryExchangeReader;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the client keys of refinery orders ({@link ExchangeRefineryOrderLink}) in step with the
 * orders: the undo gives a recreated order its key back and frees the key of an order it removes,
 * the account merge keeps the key of an order that still exists, and the retention deletes a link
 * nothing refers to any more (REQ-XCH-052, REQ-XCH-056).
 */
@Component
@RequiredArgsConstructor
public class ExchangeRefineryOrderKeys {

  /** The most order ids one existence check sends. */
  private static final int BATCH = 500;

  private final ExchangeRefineryOrderLinkRepository linkRepository;
  private final RefineryExchangeReader reader;
  private final Clock clock = Clock.systemUTC();

  /**
   * Lets the key of an order the undo recreated name it again when a later create took the key
   * over: the live link of the key is retired when its order is gone, and left alone while its
   * order exists, whose own undo then hands the key back.
   *
   * @param orderId the recreated order
   */
  void restored(@NotNull UUID orderId) {
    Optional<ExchangeRefineryOrderLink> retired =
        linkRepository.findByRefineryOrderIdAndRetiredAtIsNotNull(orderId);
    if (retired.isEmpty()) {
      return;
    }
    ExchangeRefineryOrderLink own = retired.get();
    Optional<ExchangeRefineryOrderLink> live =
        linkRepository.findByUserIdAndClientIdAndClientKeyAndRetiredAtIsNull(
            own.getUserId(), own.getClientId(), own.getClientKey());
    if (live.isPresent()) {
      if (!reader.existing(Set.of(live.get().getRefineryOrderId())).isEmpty()) {
        return;
      }
      linkRepository.retire(live.get().getId(), clock.instant());
    }
    linkRepository.revive(own.getId());
  }

  /**
   * Retires the key of an order the undo removed and hands it back to the latest order that had it
   * before and still exists.
   *
   * @param orderId the removed order
   */
  void removed(@NotNull UUID orderId) {
    Optional<ExchangeRefineryOrderLink> live =
        linkRepository.findByRefineryOrderIdAndRetiredAtIsNull(orderId);
    if (live.isEmpty()) {
      return;
    }
    ExchangeRefineryOrderLink link = live.get();
    linkRepository.retire(link.getId(), clock.instant());
    List<ExchangeRefineryOrderLink> earlier =
        linkRepository
            .findByUserIdAndClientIdAndClientKeyAndRetiredAtIsNotNullOrderByRetiredAtDesc(
                link.getUserId(), link.getClientId(), link.getClientKey())
            .stream()
            .filter(candidate -> !candidate.getId().equals(link.getId()))
            .toList();
    Set<UUID> existing =
        reader.existing(
            earlier.stream().map(ExchangeRefineryOrderLink::getRefineryOrderId).toList());
    earlier.stream()
        .filter(candidate -> existing.contains(candidate.getRefineryOrderId()))
        .findFirst()
        .ifPresent(candidate -> linkRepository.revive(candidate.getId()));
  }

  /**
   * Prepares the account merge's move of the links (REQ-SEC-045): where both accounts hold a live
   * link of one key, the kept account's link is retired when its order is gone and the moving
   * account's order exists, so the moving order keeps its key.
   *
   * @param source the account being emptied
   * @param target the account being kept
   */
  void handOver(@NotNull UUID source, @NotNull UUID target) {
    List<ExchangeRefineryOrderLink> live =
        linkRepository.findByUserIdInAndRetiredAtIsNull(Set.of(source, target));
    Map<Key, ExchangeRefineryOrderLink> held =
        live.stream()
            .filter(link -> link.getUserId().equals(target))
            .collect(Collectors.toMap(Key::of, link -> link));
    List<Clash> clashes = new ArrayList<>();
    for (ExchangeRefineryOrderLink moving : live) {
      ExchangeRefineryOrderLink kept = held.get(Key.of(moving));
      if (moving.getUserId().equals(source) && kept != null) {
        clashes.add(new Clash(moving, kept));
      }
    }
    if (clashes.isEmpty()) {
      return;
    }
    Set<UUID> existing =
        reader.existing(
            clashes.stream()
                .flatMap(clash ->
                    Stream.of(
                        clash.moving().getRefineryOrderId(), clash.kept().getRefineryOrderId()))
                .toList());
    for (Clash clash : clashes) {
      if (existing.contains(clash.moving().getRefineryOrderId())
          && !existing.contains(clash.kept().getRefineryOrderId())) {
        linkRepository.retire(clash.kept().getId(), clock.instant());
      }
    }
  }

  /**
   * Deletes the links whose order is gone and that no journal or change-feed entry refers to any
   * more, so a key lives as long as the undo and the tombstone that may need it (REQ-XCH-013,
   * REQ-XCH-022).
   *
   * @return the number of links deleted
   */
  @Transactional
  public int purgeUnreferenced() {
    List<UUID> candidates = linkRepository.findUnreferencedOrderIds();
    int deleted = 0;
    for (int from = 0; from < candidates.size(); from += BATCH) {
      List<UUID> batch = candidates.subList(from, Math.min(from + BATCH, candidates.size()));
      Set<UUID> existing = reader.existing(batch);
      List<UUID> gone = batch.stream().filter(id -> !existing.contains(id)).toList();
      if (!gone.isEmpty()) {
        deleted += linkRepository.deleteByOrderIds(gone);
      }
    }
    return deleted;
  }

  /**
   * Two live links of one key on the two accounts of a merge.
   *
   * @param moving the link of the account being emptied
   * @param kept the link of the account being kept
   */
  private record Clash(
      @NotNull ExchangeRefineryOrderLink moving, @NotNull ExchangeRefineryOrderLink kept) {}

  /**
   * A client key within one member's links.
   *
   * @param clientId the client
   * @param clientKey the client's key
   */
  private record Key(@NotNull String clientId, @NotNull String clientKey) {

    /**
     * Reads a link's key.
     *
     * @param link the link
     * @return its client and key
     */
    static @NotNull Key of(@NotNull ExchangeRefineryOrderLink link) {
      return new Key(link.getClientId(), link.getClientKey());
    }
  }
}
