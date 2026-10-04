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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAuditLabels;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryProperties;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.CheckoutType;
import de.greluc.krt.profit.basetool.backend.model.ExchangeChange;
import de.greluc.krt.profit.basetool.backend.model.ExchangeJournalAction;
import de.greluc.krt.profit.basetool.backend.model.ExchangeResource;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemStolenMarkDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeCatalogKind;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeItemRef;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeResolveRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeResolveResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeStockChangeSet;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeChangeRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeJournalRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.InventoryCheckoutService;
import de.greluc.krt.profit.basetool.backend.service.InventoryStolenMarkService;
import de.greluc.krt.profit.basetool.backend.service.MaterialExchangeOfferRatchet;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies a client's stock changes to the member's lots, personal and shared: each op sets a lot to
 * a quantity against the quantity the client last saw, under row locks, and the difference is
 * booked in or out like the Lager does (REQ-XCH-014, REQ-XCH-016, REQ-XCH-021, REQ-XCH-022,
 * ADR-0218, ADR-0230).
 *
 * <p>A book-in is a new personal row without an org unit, at the quality the op names. A book-out
 * takes the lot's personal rows first, then rows without an org unit, then the oldest, and never
 * more of a row than its job-order and mission reservations leave free, through the Lager's own
 * book-out, which lowers linked Materialbörse offers and audits each offer it lowers or removes;
 * they are counted in the result.
 */
@Service
@RequiredArgsConstructor
public class ExchangeStockWriteService {

  /** The share of a lot a batch may take within the window before it counts as a removal. */
  private static final BigDecimal REMOVAL_SHARE = new BigDecimal("0.1");

  private static final String UNCHANGED = "unchanged";
  private static final String UNMATCHED = "unmatched";
  private static final String AMBIGUOUS = "ambiguous";
  private static final String REJECTED = "rejected";
  private static final String APPLIED = "applied";
  private static final String HELD = "held";
  private static final String CLIENT_CHANNEL = "client";

  /** The refusal of a quantity whose unit is not the material's. */
  static final String UNIT_MISMATCH = "UNIT_MISMATCH";

  /** The refusal of a place without a Lager location. */
  static final String LOCATION_UNKNOWN = "LOCATION_UNKNOWN";

  /** The refusal of an expected quantity the lot no longer holds. */
  static final String VERSION_CONFLICT = "VERSION_CONFLICT";

  /** The refusal of refilling a lot emptied by another channel or installation. */
  static final String REMOVED_ELSEWHERE = "REMOVED_ELSEWHERE";

  /** The refusal of taking stock that is reserved for a job order or mission. */
  static final String STOCK_EARMARKED = "STOCK_EARMARKED";

  /** The prefix that keeps the exchange's lot locks apart from any other advisory lock. */
  private static final String LOT_LOCK_PREFIX = "exchange-stock-lot|";

  /** The refusal of a stolen lot while the stolen marking is switched off. */
  static final String STOLEN_MARKING_DISABLED = "STOLEN_MARKING_DISABLED";

  private final ExchangeResolveService resolveService;
  private final MaterialRepository materialRepository;
  private final GameItemRepository gameItemRepository;
  private final ExchangeLocationResolver locationResolver;
  private final LocationRepository locationRepository;
  private final InventoryItemRepository inventoryRepository;
  private final InventoryCheckoutService checkoutService;
  private final InventoryStolenMarkService stolenMarkService;
  private final MaterialExchangeOfferRepository offerRepository;
  private final UserRepository userRepository;
  private final AuditRecorder auditRecorder;
  private final InventoryProperties inventoryProperties;
  private final ExchangeChangeRepository changeRepository;
  private final ExchangeJournalService journalService;
  private final ExchangeJournalRepository journalRepository;
  private final ExchangeMassChangeGuard guard;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper;
  private final ExchangeLiveSync liveSync;
  private final Clock clock = Clock.systemUTC();

  /**
   * Plans and applies one change set in one transaction.
   *
   * @param caller the client, installation and member
   * @param changeSet the changes
   * @return the counts, the offers the book-outs lowered or removed, and the detail of every op
   *     that was not applied
   * @throws ExchangeProblemException {@code 409 MASS_CHANGE_CONFIRMATION_REQUIRED} when the batch
   *     removes too much; nothing is written then
   */
  @Transactional
  public @NotNull ExchangeChangeResultDto apply(
      @NotNull ExchangeCaller caller, @NotNull ExchangeStockChangeSet changeSet) {
    return run(caller, changeSet, true);
  }

  /**
   * Applies a change set the member confirmed after the mass-change guard held it back, without
   * asking the guard again (REQ-XCH-021).
   *
   * @param caller the client, installation and member the change set was staged for
   * @param changeSet the changes
   * @return the counts and the detail of every op that was not applied
   */
  @Transactional
  public @NotNull ExchangeChangeResultDto applyConfirmed(
      @NotNull ExchangeCaller caller, @NotNull ExchangeStockChangeSet changeSet) {
    return run(caller, changeSet, false);
  }

  /**
   * Plans a change set, asks the guard when told to, and applies it.
   *
   * @param caller the caller
   * @param changeSet the changes
   * @param guarded whether the mass-change guard decides
   * @return the result
   */
  private @NotNull ExchangeChangeResultDto run(
      @NotNull ExchangeCaller caller, @NotNull ExchangeStockChangeSet changeSet, boolean guarded) {
    List<Planned> plan = plan(caller, changeSet.ops());
    Set<Change> moves = moves(plan);
    if (guarded
        && !changeSet.dryRun()
        && guard.requiresConfirmation(
            caller,
            ExchangeResource.STOCK,
            lotCount(caller.member()),
            removals(caller, plan, moves))) {
      counter(caller, HELD).increment();
      throw ExchangeProblemException.massChangeConfirmationRequired();
    }
    UUID batch = UUID.randomUUID();
    int applied = 0;
    int unchanged = 0;
    OfferEffects offers = new OfferEffects();
    Map<Change, BigDecimal> flipped =
        changeSet.dryRun() || !inventoryProperties.stolenMarkingEnabled()
            ? Map.of()
            : flipStolen(caller.member(), plan);
    List<ExchangeChangeResultDto.OpResult> results = new ArrayList<>();
    for (int i = 0; i < plan.size(); i++) {
      String opId = changeSet.ops().get(i).opId();
      if (plan.get(i) instanceof Change change) {
        if (!changeSet.dryRun()) {
          execute(caller, batch, change, moves, flipped, offers);
          counter(caller, APPLIED).increment();
        }
        applied++;
      } else if (plan.get(i) instanceof Skip skip) {
        if (UNCHANGED.equals(skip.result())) {
          unchanged++;
        }
        if (!changeSet.dryRun()) {
          counter(caller, skip.result()).increment();
        }
        results.add(new ExchangeChangeResultDto.OpResult(i, opId, skip.result(), skip.reason()));
      }
    }
    if (!changeSet.dryRun() && applied > 0) {
      liveSync.stockChanged(offers.reduced + offers.removed > 0);
    }
    return new ExchangeChangeResultDto(
        changeSet.dryRun(),
        applied,
        unchanged,
        plan.size() - applied - unchanged,
        results,
        offers.reduced,
        offers.removed,
        null);
  }

  /**
   * Sets one of the member's lots back to a quantity for an undo, through the same book-in and
   * book-out as a client write; offers a book-out lowers are not raised again.
   *
   * @param member the member
   * @param lotKey the lot's key as the journal and the change feed record it
   * @param target the quantity to restore
   * @param unit the lot's unit
   * @return whether the lot could be restored; {@code false} when its material, item or location is
   *     gone or the key is not a lot key
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean restoreLot(
      @NotNull UUID member,
      @NotNull String lotKey,
      @NotNull BigDecimal target,
      @NotNull String unit) {
    Optional<Lot> parsed = parseLot(lotKey, unit);
    if (parsed.isEmpty()) {
      return false;
    }
    Lot lot = parsed.get();
    lockLots(member, List.of(lotKey));
    List<InventoryItem> rows = lockRows(member, lot);
    BigDecimal delta = round(target, unit).subtract(round(sum(rows), unit));
    if (delta.signum() > 0) {
      bookIn(member, lot, delta);
    } else if (delta.signum() < 0) {
      bookOut(member, rows, delta.negate(), unit, new OfferEffects());
    }
    return true;
  }

  /**
   * Reads a lot key back into its lot.
   *
   * @param lotKey the key
   * @param unit the lot's unit
   * @return the lot, or empty when the key is malformed or names something that no longer exists
   */
  private @NotNull Optional<Lot> parseLot(@NotNull String lotKey, @NotNull String unit) {
    String[] parts = lotKey.split("\\|");
    if (parts.length != 4
        || !parts[1].startsWith("l:")
        || !parts[2].startsWith("q:")
        || !parts[3].startsWith("s:")) {
      return Optional.empty();
    }
    try {
      UUID catalogueId = UUID.fromString(parts[0].substring(2));
      Optional<Location> location =
          locationRepository.findById(UUID.fromString(parts[1].substring(2)));
      int quality = Integer.parseInt(parts[2].substring(2));
      boolean stolen = "1".equals(parts[3].substring(2));
      if (location.isEmpty()) {
        return Optional.empty();
      }
      if (parts[0].startsWith("m:")) {
        return materialRepository
            .findById(catalogueId)
            .map(m -> new Lot(m, null, location.get(), quality, stolen, unit));
      }
      if (parts[0].startsWith("i:")) {
        return gameItemRepository
            .findById(catalogueId)
            .map(i -> new Lot(null, i, location.get(), null, stolen, unit));
      }
      return Optional.empty();
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  /**
   * Decides every op in order, as if the earlier ops had run, after locking every lot the batch
   * names in the order of the lots' keys.
   *
   * @param caller the caller
   * @param ops the ops
   * @return one plan per op
   */
  private @NotNull List<Planned> plan(
      @NotNull ExchangeCaller caller, @NotNull List<ExchangeStockChangeSet.Op> ops) {
    Map<Integer, Catalogue> catalogue = resolve(ops);
    List<Skip> early = new ArrayList<>(ops.size());
    List<Lot> lots = new ArrayList<>(ops.size());
    for (int i = 0; i < ops.size(); i++) {
      ExchangeStockChangeSet.Op op = ops.get(i);
      Catalogue entry = catalogue.get(i);
      Skip skip = null;
      Lot lot = null;
      if (entry.result() != null) {
        skip = new Skip(entry.result(), entry.reason());
      } else {
        Optional<Location> location = locationResolver.resolve(op.location());
        String unit = entry.unit();
        if (location.isEmpty()) {
          skip = new Skip(REJECTED, LOCATION_UNKNOWN);
        } else if (!unit.equals(op.quantity().unit())
            || !unit.equals(op.expectedQuantity().unit())) {
          skip = new Skip(REJECTED, UNIT_MISMATCH);
        } else if (Boolean.TRUE.equals(op.stolen())
            && !inventoryProperties.stolenMarkingEnabled()) {
          skip = new Skip(REJECTED, STOLEN_MARKING_DISABLED);
        } else {
          Integer quality = entry.material() == null ? null : op.quality();
          lot =
              new Lot(
                  entry.material(), entry.gameItem(), location.get(), quality, op.stolen(), unit);
        }
      }
      early.add(skip);
      lots.add(lot);
    }
    Map<String, List<InventoryItem>> locked = lockInKeyOrder(caller.member(), lots);
    Map<String, BigDecimal> quantities = new HashMap<>();
    List<Planned> plan = new ArrayList<>();
    for (int i = 0; i < ops.size(); i++) {
      if (early.get(i) != null) {
        plan.add(early.get(i));
        continue;
      }
      ExchangeStockChangeSet.Op op = ops.get(i);
      Lot lot = lots.get(i);
      String unit = lot.unit();
      List<InventoryItem> rows = locked.get(lot.key());
      BigDecimal current = quantities.computeIfAbsent(lot.key(), k -> round(sum(rows), unit));
      BigDecimal expected = round(op.expectedQuantity().amount(), unit);
      BigDecimal target = round(op.quantity().amount(), unit);
      if (current.compareTo(expected) != 0) {
        plan.add(new Skip(REJECTED, VERSION_CONFLICT));
        continue;
      }
      if (current.compareTo(target) == 0) {
        plan.add(new Skip(UNCHANGED, null));
        continue;
      }
      if (current.signum() == 0
          && !Boolean.TRUE.equals(op.override())
          && removedElsewhere(caller, lot.key())) {
        plan.add(new Skip(REJECTED, REMOVED_ELSEWHERE));
        continue;
      }
      if (target.compareTo(current) < 0
          && current.subtract(target).compareTo(round(free(rows), unit)) > 0) {
        plan.add(new Skip(REJECTED, STOCK_EARMARKED));
        continue;
      }
      quantities.put(lot.key(), target);
      plan.add(new Change(lot, current, target, rows));
    }
    return plan;
  }

  /**
   * Resolves each op's material, and falls back to the items for those no material matched.
   *
   * @param ops the ops
   * @return the catalogue entry, or the result that ends the op, by op index
   */
  private @NotNull Map<Integer, Catalogue> resolve(@NotNull List<ExchangeStockChangeSet.Op> ops) {
    List<ExchangeItemRef> refs = ops.stream().map(ExchangeStockChangeSet.Op::material).toList();
    ExchangeResolveResponse materials =
        resolveService.resolve(new ExchangeResolveRequest(ExchangeCatalogKind.MATERIAL, refs));
    Map<Integer, Catalogue> byIndex = new HashMap<>();
    List<Integer> retry = new ArrayList<>();
    for (ExchangeResolveResponse.Result result : materials.results()) {
      if (result.status() == ExchangeResolveResponse.Status.RESOLVED) {
        Material material =
            materialRepository.findById(UUID.fromString(result.ref().bt())).orElseThrow();
        byIndex.put(result.index(), Catalogue.of(material));
      } else if (result.status() == ExchangeResolveResponse.Status.AMBIGUOUS) {
        byIndex.put(result.index(), Catalogue.skip(AMBIGUOUS, "AMBIGUOUS"));
      } else {
        retry.add(result.index());
      }
    }
    if (!retry.isEmpty()) {
      ExchangeResolveResponse items =
          resolveService.resolve(
              new ExchangeResolveRequest(
                  ExchangeCatalogKind.ITEM, retry.stream().map(refs::get).toList()));
      for (ExchangeResolveResponse.Result result : items.results()) {
        int index = retry.get(result.index());
        if (result.status() == ExchangeResolveResponse.Status.RESOLVED) {
          GameItem item =
              gameItemRepository.findById(UUID.fromString(result.ref().bt())).orElseThrow();
          byIndex.put(index, Catalogue.of(item));
        } else if (result.status() == ExchangeResolveResponse.Status.AMBIGUOUS) {
          byIndex.put(index, Catalogue.skip(AMBIGUOUS, "AMBIGUOUS"));
        } else {
          byIndex.put(index, Catalogue.skip(UNMATCHED, "UNMATCHED"));
        }
      }
    }
    return byIndex;
  }

  /**
   * Locks every distinct lot of a batch, first by its advisory lock, then its rows in the order of
   * the lots' keys, so two batches of one member that share lots never deadlock and the later one
   * reads the rows the earlier one booked in (ADR-0229).
   *
   * @param member the member
   * @param lots the batch's lots by op, {@code null} for an op that ends before its lot
   * @return each lot's locked rows by the lot's key
   */
  private @NotNull Map<String, List<InventoryItem>> lockInKeyOrder(
      @NotNull UUID member, @NotNull List<@Nullable Lot> lots) {
    Map<String, Lot> byKey = new TreeMap<>();
    for (Lot lot : lots) {
      if (lot != null) {
        byKey.putIfAbsent(lot.key(), lot);
      }
    }
    lockLots(member, byKey.keySet());
    Map<String, List<InventoryItem>> locked = new HashMap<>();
    byKey.forEach((key, lot) -> locked.put(key, lockRows(member, lot)));
    return locked;
  }

  /**
   * Takes the transaction-scoped advisory lock of each of a member's lots, in the order of the lock
   * keys, before any of their rows is read (ADR-0229).
   *
   * <p>The lock exists whether or not the lot has rows, so a writer that waited reads the rows the
   * holder booked in. Two lots whose keys collide share one lock, which only serialises them.
   *
   * @param member the member
   * @param lotKeys the lots' keys as the change feed records them
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockLots(@NotNull UUID member, @NotNull Collection<String> lotKeys) {
    Set<Long> keys = new TreeSet<>();
    for (String lotKey : lotKeys) {
      keys.add(lotLockKey(member, lotKey));
    }
    keys.forEach(inventoryRepository::lockExchangeLot);
  }

  /**
   * Derives a lot's 64-bit advisory lock key: the first eight bytes of the SHA-256 of the member
   * and the lot key under the exchange's own prefix.
   *
   * @param member the member
   * @param lotKey the lot's key
   * @return the lock key
   */
  static long lotLockKey(@NotNull UUID member, @NotNull String lotKey) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((LOT_LOCK_PREFIX + member + '|' + lotKey).getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.wrap(digest).getLong();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * Locks the member's rows of a lot, personal and shared, in the order a book-out takes them: the
   * personal rows first, then the rows without an org unit, then the oldest.
   *
   * @param member the member
   * @param lot the lot
   * @return the rows, locked for this transaction
   */
  private @NotNull List<InventoryItem> lockRows(@NotNull UUID member, @NotNull Lot lot) {
    List<InventoryItem> rows =
        new ArrayList<>(
            lot.material() != null
                ? inventoryRepository.lockMaterialLot(
                    member,
                    lot.material().getId(),
                    lot.location().getId(),
                    lot.quality() == null ? 0 : lot.quality(),
                    lot.stolen())
                : inventoryRepository.lockItemLot(
                    member, lot.gameItem().getId(), lot.location().getId(), lot.stolen()));
    rows.sort(
        Comparator.comparing((InventoryItem r) -> Boolean.TRUE.equals(r.getPersonal()) ? 0 : 1)
            .thenComparing(r -> r.getOwningOrgUnit() == null ? 0 : 1)
            .thenComparing(
                InventoryItem::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    return rows;
  }

  /**
   * Whether the lot's latest change was its emptying by another channel or installation.
   *
   * @param caller the caller
   * @param lotKey the lot's key
   * @return {@code true} when a live tombstone of someone else stands
   */
  private boolean removedElsewhere(@NotNull ExchangeCaller caller, @NotNull String lotKey) {
    Optional<ExchangeChange> latest =
        changeRepository.findLatestForKey(caller.member(), ExchangeResource.STOCK.name(), lotKey);
    if (latest.isEmpty()) {
      return false;
    }
    ExchangeChange change = latest.get();
    return !(CLIENT_CHANNEL.equals(change.getSourceChannel())
        && caller.clientId().equals(change.getSourceClient())
        && caller.installationKey().equals(change.getSourceKey()));
  }

  /**
   * Finds the falling lots that are moves: a fall is a move when the rises of the same material or
   * item elsewhere in the batch still cover all of it, taken in the batch's order; a fall they do
   * not cover counts by the removal rules.
   *
   * @param plan the plan
   * @return the falling lots whose whole fall is covered
   */
  private static @NotNull Set<Change> moves(@NotNull List<Planned> plan) {
    Map<UUID, BigDecimal> rises = new HashMap<>();
    for (Planned planned : plan) {
      if (planned instanceof Change change && change.target().compareTo(change.current()) > 0) {
        rises.merge(
            change.lot().catalogueId(),
            change.target().subtract(change.current()),
            BigDecimal::add);
      }
    }
    Set<Change> moves = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Planned planned : plan) {
      if (planned instanceof Change change && change.target().compareTo(change.current()) < 0) {
        BigDecimal fall = change.current().subtract(change.target());
        BigDecimal left = rises.getOrDefault(change.lot().catalogueId(), BigDecimal.ZERO);
        if (left.compareTo(fall) >= 0) {
          moves.add(change);
          rises.put(change.lot().catalogueId(), left.subtract(fall));
        }
      }
    }
    return moves;
  }

  /**
   * Moves stock between a lot and its stolen or not-stolen twin by marking the rows, the way the
   * Lager does (REQ-INV-053), instead of booking it out and in: for each falling lot whose twin
   * rises in the batch, as much as both allow is marked, row by row, rows backing a Materialbörse
   * offer left out and earmarked stock kept on the row it is earmarked on.
   *
   * @param member the member
   * @param plan the plan
   * @return the amount each change had marked, by change; the rest of it is booked
   */
  private @NotNull Map<Change, BigDecimal> flipStolen(
      @NotNull UUID member, @NotNull List<Planned> plan) {
    Map<Change, BigDecimal> flipped = new IdentityHashMap<>();
    Map<Change, BigDecimal> room = new IdentityHashMap<>();
    for (Planned planned : plan) {
      if (planned instanceof Change change && change.target().compareTo(change.current()) > 0) {
        room.put(change, change.target().subtract(change.current()));
      }
    }
    for (Planned planned : plan) {
      if (!(planned instanceof Change falling)
          || falling.target().compareTo(falling.current()) >= 0) {
        continue;
      }
      BigDecimal left = falling.current().subtract(falling.target());
      for (Map.Entry<Change, BigDecimal> twin : room.entrySet()) {
        if (left.signum() <= 0) {
          break;
        }
        Change rising = twin.getKey();
        if (!isTwin(falling.lot(), rising.lot()) || twin.getValue().signum() <= 0) {
          continue;
        }
        BigDecimal marked =
            markRows(member, falling, rising.lot().stolen(), left.min(twin.getValue()));
        twin.setValue(twin.getValue().subtract(marked));
        left = left.subtract(marked);
        flipped.merge(falling, marked, BigDecimal::add);
        flipped.merge(rising, marked, BigDecimal::add);
      }
    }
    return flipped;
  }

  /**
   * Whether two lots differ in their stolen marker only.
   *
   * @param a one lot
   * @param b the other lot
   * @return whether b is a's stolen or not-stolen twin
   */
  private static boolean isTwin(@NotNull Lot a, @NotNull Lot b) {
    return a.stolen() != b.stolen()
        && a.catalogueId().equals(b.catalogueId())
        && a.location().getId().equals(b.location().getId())
        && Objects.equals(a.quality(), b.quality());
  }

  /**
   * Marks up to an amount of a lot's rows, whole rows first as they come, the last one split.
   *
   * @param member the member, recorded as the actor
   * @param change the falling lot
   * @param stolen the marker to set
   * @param amount the amount to mark
   * @return the amount marked; less when rows backing an offer had to be left out
   */
  private @NotNull BigDecimal markRows(
      @NotNull UUID member, @NotNull Change change, boolean stolen, @NotNull BigDecimal amount) {
    BigDecimal left = amount;
    for (InventoryItem row : change.rows()) {
      if (left.signum() <= 0) {
        break;
      }
      if (Boolean.TRUE.equals(row.getStolen()) == stolen
          || row.getAmount() == null
          || row.getAmount() <= 0
          || offerRepository.existsByInventoryItemId(row.getId())) {
        continue;
      }
      BigDecimal rowAmount = round(BigDecimal.valueOf(row.getAmount()), change.lot().unit());
      BigDecimal take = rowAmount.min(left);
      if (take.compareTo(rowAmount) < 0) {
        BigDecimal earmarked =
            BigDecimal.valueOf(
                InventoryAllocations.sumJobOrder(row) + InventoryAllocations.sumMission(row));
        take = take.min(round(rowAmount.subtract(earmarked), change.lot().unit()));
      }
      if (take.signum() <= 0) {
        continue;
      }
      stolenMarkService.mark(
          row.getId(),
          new InventoryItemStolenMarkDto(
              row.getVersion(), stolen, take.compareTo(rowAmount) == 0 ? null : take.doubleValue()),
          member);
      left = left.subtract(take);
    }
    return amount.subtract(left);
  }

  /**
   * Counts the batch's removals by the stock rules: a lot set to 0, or cut to at most a tenth of
   * what it held when the window opened, unless another lot of the same material rises in the same
   * batch, which makes it a move.
   *
   * @param caller the caller
   * @param plan the plan
   * @param moves the falling lots that are moves
   * @return the removals
   */
  private long removals(
      @NotNull ExchangeCaller caller, @NotNull List<Planned> plan, @NotNull Set<Change> moves) {
    long removals = 0;
    for (Planned planned : plan) {
      if (planned instanceof Change change && isRemoval(caller, change, moves)) {
        removals++;
      }
    }
    return removals;
  }

  /**
   * Whether one change counts as a removal for the mass-change guard.
   *
   * @param caller the caller
   * @param change the change
   * @param moves the falling lots that are moves
   * @return {@code true} for a removal
   */
  private boolean isRemoval(
      @NotNull ExchangeCaller caller, @NotNull Change change, @NotNull Set<Change> moves) {
    if (change.target().compareTo(change.current()) >= 0 || moves.contains(change)) {
      return false;
    }
    if (change.target().signum() == 0) {
      return true;
    }
    BigDecimal start = windowStart(caller, change.lot().key()).orElse(change.current());
    return start.signum() > 0 && change.target().compareTo(start.multiply(REMOVAL_SHARE)) <= 0;
  }

  /**
   * Reads what a lot held before the client's first change to it within the guard's window.
   *
   * @param caller the caller
   * @param lotKey the lot's key
   * @return the quantity, or empty when the client did not change it within the window
   */
  private @NotNull Optional<BigDecimal> windowStart(
      @NotNull ExchangeCaller caller, @NotNull String lotKey) {
    return journalRepository
        .findFirstByUserIdAndClientIdAndResourceAndEntityKeyAndRecordedAtAfterOrderByRecordedAtAsc(
            caller.member(),
            caller.clientId(),
            ExchangeResource.STOCK,
            lotKey,
            clock.instant().minus(ExchangeMassChangeGuard.WINDOW))
        .map(entry -> entry.getBeforeState())
        .map(json -> objectMapper.readTree(json).get("quantity"))
        .filter(JsonNode::isNumber)
        .map(JsonNode::decimalValue);
  }

  /**
   * Books one change in or out and journals it.
   *
   * @param caller the caller
   * @param batch the change set's id
   * @param change the change
   * @param moves the falling lots that are moves
   * @param flipped the amount of each change that marking already moved
   * @param offers the running offer effects
   */
  private void execute(
      @NotNull ExchangeCaller caller,
      @NotNull UUID batch,
      @NotNull Change change,
      @NotNull Set<Change> moves,
      @NotNull Map<Change, BigDecimal> flipped,
      @NotNull OfferEffects offers) {
    BigDecimal delta = change.target().subtract(change.current());
    BigDecimal booked = delta.abs().subtract(flipped.getOrDefault(change, BigDecimal.ZERO));
    if (booked.signum() > 0 && delta.signum() > 0) {
      bookIn(caller.member(), change.lot(), booked);
    } else if (booked.signum() > 0) {
      List<InventoryItem> stillInLot =
          change.rows().stream()
              .filter(row -> Boolean.TRUE.equals(row.getStolen()) == change.lot().stolen())
              .toList();
      bookOut(caller.member(), stillInLot, booked, change.lot().unit(), offers);
    }
    journalService.record(
        caller,
        batch,
        ExchangeJournalAction.STOCK_SET_QUANTITY,
        change.lot().key(),
        isRemoval(caller, change, moves),
        state(change.current(), change.lot().unit()),
        state(change.target(), change.lot().unit()));
  }

  /**
   * Books stock in as a new personal row without an org unit, which piece-counted stock then joins
   * to its existing row as a book-in in the Lager does (REQ-INV-026).
   *
   * @param member the member
   * @param lot the lot
   * @param amount the amount
   */
  private void bookIn(@NotNull UUID member, @NotNull Lot lot, @NotNull BigDecimal amount) {
    User user = userRepository.findById(member).orElseThrow();
    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setOwningOrgUnit(null);
    item.setMaterial(lot.material());
    item.setGameItem(lot.gameItem());
    item.setLocation(lot.location());
    item.setQuality(lot.material() == null ? null : lot.quality() == null ? 0 : lot.quality());
    item.setAmount(InventoryItem.roundToScuScale(amount.doubleValue()));
    item.setPersonal(true);
    item.setStolen(lot.stolen());
    InventoryItem saved = inventoryRepository.save(item);
    auditRecorder.record(
        AuditEventType.INVENTORY_ITEM_CREATED,
        saved.getId(),
        InventoryAuditLabels.label(saved),
        member,
        AuditDetails.of("qty", saved.getAmount())
            .with("q", saved.getQuality())
            .with("personal", true));
    checkoutService.mergeStockIfRequested(saved, false);
  }

  /**
   * Books stock out through the Lager's own book-out, row by row, never more of a row than its
   * reservations leave free, which audits every Materialbörse offer it lowers or removes, and
   * counts those offers.
   *
   * @param member the member
   * @param rows the lot's locked rows, in the order to take them
   * @param amount the amount to take
   * @param unit the lot's unit
   * @param offers the running offer effects
   */
  private void bookOut(
      @NotNull UUID member,
      @NotNull List<InventoryItem> rows,
      @NotNull BigDecimal amount,
      @NotNull String unit,
      @NotNull OfferEffects offers) {
    BigDecimal remaining = amount;
    for (InventoryItem row : rows) {
      if (remaining.signum() <= 0) {
        break;
      }
      BigDecimal take = round(free(List.of(row)), unit).min(remaining);
      if (take.signum() <= 0) {
        continue;
      }
      MaterialExchangeOfferRatchet.Effects effects =
          checkoutService.bookOutForClient(
              row.getId(),
              new InventoryItemBookOutDto(
                  take.doubleValue(),
                  null,
                  null,
                  CheckoutType.DISCARD,
                  null,
                  null,
                  row.getVersion(),
                  null,
                  null,
                  null,
                  null),
              member);
      offers.reduced += effects.reduced();
      offers.removed += effects.removed();
      remaining = remaining.subtract(take);
    }
  }

  /**
   * Counts the member's lots, personal and shared.
   *
   * @param member the member
   * @return the number of lots
   */
  private long lotCount(@NotNull UUID member) {
    return inventoryRepository.countExchangeLots(member);
  }

  /**
   * Sums the amounts of rows.
   *
   * @param rows the rows
   * @return the sum
   */
  private static @NotNull BigDecimal sum(@NotNull List<InventoryItem> rows) {
    BigDecimal sum = BigDecimal.ZERO;
    for (InventoryItem row : rows) {
      sum = sum.add(BigDecimal.valueOf(row.getAmount()));
    }
    return sum;
  }

  /**
   * Sums what the rows hold beyond their job-order and mission reservations.
   *
   * @param rows the rows
   * @return the free amount
   */
  private static @NotNull BigDecimal free(@NotNull List<InventoryItem> rows) {
    BigDecimal free = BigDecimal.ZERO;
    for (InventoryItem row : rows) {
      double reserved =
          Math.max(InventoryAllocations.sumJobOrder(row), InventoryAllocations.sumMission(row));
      free = free.add(BigDecimal.valueOf(Math.max(0.0, row.getAmount() - reserved)));
    }
    return free;
  }

  /**
   * Rounds an amount to its unit: three decimals for SCU, whole pieces otherwise.
   *
   * @param amount the amount
   * @param unit the unit
   * @return the rounded amount
   */
  private static @NotNull BigDecimal round(@NotNull BigDecimal amount, @NotNull String unit) {
    return amount.setScale(QuantityType.SCU.name().equals(unit) ? 3 : 0, RoundingMode.HALF_UP);
  }

  /**
   * Rounds a double amount to its unit.
   *
   * @param amount the amount
   * @param unit the unit
   * @return the rounded amount
   */
  private static @NotNull BigDecimal round(double amount, @NotNull String unit) {
    return round(BigDecimal.valueOf(amount), unit);
  }

  /**
   * Captures a lot's quantity for the journal.
   *
   * @param quantity the quantity
   * @param unit the unit
   * @return the state
   */
  private static @NotNull Map<String, Object> state(
      @NotNull BigDecimal quantity, @NotNull String unit) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("quantity", quantity);
    state.put("unit", unit);
    return state;
  }

  /**
   * Returns the write counter of the caller's client and one outcome.
   *
   * @param caller the caller, whose client the relay bounded by the registry
   * @param outcome the outcome
   * @return the counter
   */
  private io.micrometer.core.instrument.Counter counter(
      @NotNull ExchangeCaller caller, @NotNull String outcome) {
    return meterRegistry.counter(
        MetricNames.EXCHANGE_WRITES,
        MetricNames.TAG_CLIENT_ID,
        caller.clientId(),
        MetricNames.TAG_RESOURCE,
        ExchangeResource.STOCK.name().toLowerCase(Locale.ROOT),
        MetricNames.TAG_OUTCOME,
        outcome);
  }

  /** The offers the change set's book-outs lowered or removed. */
  private static final class OfferEffects {

    /** The offers lowered. */
    private int reduced;

    /** The offers removed. */
    private int removed;
  }

  /**
   * A resolved material or item, or the result that ends the op.
   *
   * @param material the material, or {@code null}
   * @param gameItem the item, or {@code null}
   * @param unit the lot's unit
   * @param result the result that ends the op, or {@code null}
   * @param reason the reason of that result, or {@code null}
   */
  private record Catalogue(
      @Nullable Material material,
      @Nullable GameItem gameItem,
      @NotNull String unit,
      @Nullable String result,
      @Nullable String reason) {

    /**
     * A material.
     *
     * @param material the material
     * @return the entry
     */
    static @NotNull Catalogue of(@NotNull Material material) {
      String unit =
          material.getQuantityType() == QuantityType.PIECE
              ? QuantityType.PIECE.name()
              : QuantityType.SCU.name();
      return new Catalogue(material, null, unit, null, null);
    }

    /**
     * An item.
     *
     * @param item the item
     * @return the entry
     */
    static @NotNull Catalogue of(@NotNull GameItem item) {
      return new Catalogue(null, item, QuantityType.PIECE.name(), null, null);
    }

    /**
     * The end of an op.
     *
     * @param result the result
     * @param reason the reason
     * @return the entry
     */
    static @NotNull Catalogue skip(@NotNull String result, @NotNull String reason) {
      return new Catalogue(null, null, QuantityType.SCU.name(), result, reason);
    }
  }

  /**
   * A lot: the member's rows, personal and shared, of one material or item at one location, quality
   * and stolen state.
   *
   * @param material the material, or {@code null} for an item lot
   * @param gameItem the item, or {@code null} for a material lot
   * @param location the location
   * @param quality the quality, {@code null} for an item
   * @param stolen whether it is stolen
   * @param unit its unit
   */
  private record Lot(
      @Nullable Material material,
      @Nullable GameItem gameItem,
      @NotNull Location location,
      @Nullable Integer quality,
      boolean stolen,
      @NotNull String unit) {

    /**
     * The material's or item's id.
     *
     * @return the id
     */
    @NotNull
    UUID catalogueId() {
      return material != null ? material.getId() : gameItem.getId();
    }

    /**
     * The key the change feed records for the lot.
     *
     * @return the key
     */
    @NotNull
    String key() {
      String head = material != null ? "m:" + material.getId() : "i:" + gameItem.getId();
      return head
          + "|l:"
          + location.getId()
          + "|q:"
          + (quality == null ? 0 : quality)
          + "|s:"
          + (stolen ? "1" : "0");
    }
  }

  /** What one op of a change set will do. */
  private sealed interface Planned permits Change, Skip {}

  /**
   * An op that changes a lot.
   *
   * @param lot the lot
   * @param current what it holds now
   * @param target what it should hold
   * @param rows its locked rows, in the order a book-out takes them
   */
  private record Change(
      @NotNull Lot lot,
      @NotNull BigDecimal current,
      @NotNull BigDecimal target,
      @NotNull List<InventoryItem> rows)
      implements Planned {}

  /**
   * An op that changes nothing.
   *
   * @param result its result
   * @param reason its refusal code, or {@code null}
   */
  private record Skip(@NotNull String result, @Nullable String reason) implements Planned {}
}
