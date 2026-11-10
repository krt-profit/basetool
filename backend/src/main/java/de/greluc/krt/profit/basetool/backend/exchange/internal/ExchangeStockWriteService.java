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

import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.REJECTED;
import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.UNCHANGED;
import static de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeOps.round;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeChangeResultDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeStockChangeSet;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryProperties;
import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockReads;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
 * they are counted in the result. A share moves part of a lot's personal stock into the pool of one
 * of the member's direct units, an unshare moves the member's own stock in a pool back to personal,
 * both through the Lager's rebooking ({@link ExchangeStockShares}). A rise of a P4K-retired item is
 * refused unless the batch's falls of that item cover it ({@link ExchangeRetiredItemCover}). The
 * {@link ExchangeChangeSetRunner} runs the change set (ADR-0285).
 */
@Service
@RequiredArgsConstructor
public class ExchangeStockWriteService
    implements ExchangeChangeSetAdapter<
        ExchangeStockChangeSet, ExchangeStockWriteService.StockWrite> {

  /** The share of a lot a batch may take within the window before it counts as a removal. */
  private static final BigDecimal REMOVAL_SHARE = new BigDecimal("0.1");

  private static final Pattern LOT_KEY_SEPARATOR = Pattern.compile("\\|");
  private static final String CLIENT_CHANNEL = "client";

  /** The refusal of a quantity whose unit is not the material's. */
  static final String UNIT_MISMATCH = ExchangeReasons.UNIT_MISMATCH;

  /** The refusal of a place without a Lager location. */
  static final String LOCATION_UNKNOWN = ExchangeReasons.LOCATION_UNKNOWN;

  /** The refusal of an expected quantity the lot no longer holds. */
  static final String VERSION_CONFLICT = ExchangeReasons.VERSION_CONFLICT;

  /** The refusal of refilling a lot emptied by another channel or installation. */
  static final String REMOVED_ELSEWHERE = "REMOVED_ELSEWHERE";

  /** The refusal of taking stock that is reserved for a job order or mission. */
  static final String STOCK_EARMARKED = "STOCK_EARMARKED";

  /** The refusal of a stolen lot while the stolen marking is switched off. */
  static final String STOLEN_MARKING_DISABLED = "STOLEN_MARKING_DISABLED";

  private final ExchangeLotCatalogue lotCatalogue;
  private final MaterialRepository materialRepository;
  private final GameItemRepository gameItemRepository;
  private final ExchangeLocationResolver locationResolver;
  private final LocationRepository locationRepository;
  private final StockReads stockReads;
  private final StockLotWritePlans lotWritePlans;
  private final ExchangeStockShares shares;
  private final OrgUnitMembershipQueryService membershipQueryService;
  private final InventoryProperties inventoryProperties;
  private final ExchangeChangeRepository changeRepository;
  private final ExchangeJournalService journalService;
  private final ExchangeJournalRepository journalRepository;
  private final ExchangeMassChangeGuard guard;
  private final ExchangeChangeSetRunner runner;
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
    return runner.run(caller, this, changeSet, true);
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
    return runner.run(caller, this, changeSet, false);
  }

  @Override
  public @NotNull ExchangeResource resource() {
    return ExchangeResource.STOCK;
  }

  @Override
  public boolean dryRun(@NotNull ExchangeStockChangeSet changeSet) {
    return changeSet.dryRun();
  }

  @Override
  public @Nullable String opId(@NotNull ExchangeStockChangeSet changeSet, int index) {
    return changeSet.ops().get(index).opId();
  }

  /**
   * Counts the batch's removals by the stock rules against the member's lots.
   *
   * @param caller the caller
   * @param changeSet the changes
   * @param plan the plan
   * @return {@code true} when the member must confirm the batch
   */
  @Override
  public boolean holdsBack(
      @NotNull ExchangeCaller caller,
      @NotNull ExchangeStockChangeSet changeSet,
      @NotNull List<Planned<StockWrite>> plan) {
    return guard.requiresConfirmation(
        caller,
        ExchangeResource.STOCK,
        lotCount(caller.member()),
        removals(caller, plan, moves(plan)));
  }

  /**
   * Marks the stolen twins of the batch, then books, rebooks and journals each planned change, and
   * refreshes the Lager pages afterwards.
   *
   * @param caller the caller
   * @param batch the change set's id
   * @param plan the plan
   * @param dryRun whether nothing is written; shares and unshares still ask the Lager
   * @return the execution
   */
  @Override
  public @NotNull Execution<StockWrite> start(
      @NotNull ExchangeCaller caller,
      @NotNull UUID batch,
      @NotNull List<Planned<StockWrite>> plan,
      boolean dryRun) {
    Set<Change> moves = moves(plan);
    OfferEffects offers = new OfferEffects();
    Set<String> touched = new HashSet<>();
    Set<String> refused = new HashSet<>();
    return new Execution<>() {
      @Override
      public @NotNull Outcome run(@NotNull StockWrite write) {
        return switch (write) {
          case Rebooking rebooking ->
              runRebooking(caller, batch, rebooking, dryRun, touched, refused);
          case Change change -> {
            var outcome =
                lotWritePlans.executePreparedLot(
                    Objects.requireNonNull(change.confirmed()), change.step(), dryRun);
            offers.reduced += outcome.offers().reduced();
            offers.removed += outcome.offers().removed();
            if (!dryRun) {
              journal(caller, batch, change, moves);
            }
            touched.add(change.lot().key());
            yield Outcome.APPLIED;
          }
        };
      }

      @Override
      public @NotNull Effects effects() {
        return new Effects(offers.reduced, offers.removed, null);
      }

      @Override
      public void announce() {
        liveSync.stockChanged(offers.reduced + offers.removed > 0);
      }
    };
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
    BigDecimal current =
        round(
            sum(
                Objects.requireNonNull(
                    stockReads.lotRows(member, List.of(lot.stock())).get(lotKey))),
            unit);
    var prepared =
        lotWritePlans.prepareLotBatch(
            member,
            List.of(lot.stock()),
            current.compareTo(target) == 0
                ? List.of()
                : List.of(
                    new StockLotWritePlans.SetLotAmount(
                        0, lot.stock(), current, round(target, unit))));
    BigDecimal lockedCurrent =
        round(sum(Objects.requireNonNull(prepared.lockedRows().get(lotKey))), unit);
    List<StockLotWritePlans.Intent> intents =
        lockedCurrent.compareTo(target) == 0
            ? List.of()
            : List.of(
                new StockLotWritePlans.SetLotAmount(
                    0, lot.stock(), lockedCurrent, round(target, unit)));
    var confirmed = lotWritePlans.confirmLotBatch(prepared, intents, false);
    if (!intents.isEmpty()) {
      lotWritePlans.executePreparedLot(confirmed, 0, false);
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
    List<String> parts = LOT_KEY_SEPARATOR.splitAsStream(lotKey).toList();
    if (parts.size() != 4
        || !parts.get(1).startsWith("l:")
        || !parts.get(2).startsWith("q:")
        || !parts.get(3).startsWith("s:")) {
      return Optional.empty();
    }
    try {
      UUID catalogueId = UUID.fromString(parts.get(0).substring(2));
      Optional<Location> location =
          locationRepository.findById(UUID.fromString(parts.get(1).substring(2)));
      int quality = Integer.parseInt(parts.get(2).substring(2));
      boolean stolen = "1".equals(parts.get(3).substring(2));
      if (location.isEmpty()) {
        return Optional.empty();
      }
      if (parts.get(0).startsWith("m:")) {
        return materialRepository
            .findById(catalogueId)
            .map(m -> new Lot(m, null, location.get(), quality, stolen, unit));
      }
      if (parts.get(0).startsWith("i:")) {
        return gameItemRepository
            .findById(catalogueId)
            .map(i -> new Lot(null, i, location.get(), null, stolen, unit));
      }
      return Optional.empty();
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }

  /**
   * Decides every op in order, as if the earlier ops had run, after locking every lot the batch
   * names in the order of the lots' keys.
   *
   * @param caller the caller
   * @param changeSet the changes
   * @return one plan per op
   * @throws BadRequestException when a share or unshare names no org unit
   */
  @Override
  public @NotNull List<Planned<StockWrite>> plan(
      @NotNull ExchangeCaller caller, @NotNull ExchangeStockChangeSet changeSet) {
    List<ExchangeStockChangeSet.Op> ops = changeSet.ops();
    Map<Integer, ExchangeLotCatalogue.Entry> catalogue = resolve(ops);
    List<Planned<StockWrite>> early = new ArrayList<>(ops.size());
    List<Lot> lots = new ArrayList<>(ops.size());
    for (int i = 0; i < ops.size(); i++) {
      ExchangeStockChangeSet.Op op = ops.get(i);
      if (ExchangeStockShares.isRebooking(op) && op.orgUnit() == null) {
        throw new BadRequestException("A share or unshare names its org unit");
      }
      ExchangeLotCatalogue.Entry entry = Objects.requireNonNull(catalogue.get(i));
      Planned<StockWrite> skip = null;
      Lot lot = null;
      if (entry.result() != null) {
        skip = ExchangeChangeSetAdapter.skip(entry.result(), entry.reason());
      } else {
        Optional<Location> location = locationResolver.resolve(op.location());
        String unit = entry.unit();
        if (location.isEmpty()) {
          skip = ExchangeChangeSetAdapter.skip(REJECTED, LOCATION_UNKNOWN);
        } else if (!unit.equals(op.quantity().unit())
            || !unit.equals(op.expectedQuantity().unit())
            || (ExchangeStockShares.isRebooking(op)
                && !ExchangeStockShares.isWhole(op.quantity(), unit))) {
          skip = ExchangeChangeSetAdapter.skip(REJECTED, UNIT_MISMATCH);
        } else if (Boolean.TRUE.equals(op.stolen())
            && !inventoryProperties.stolenMarkingEnabled()) {
          skip = ExchangeChangeSetAdapter.skip(REJECTED, STOLEN_MARKING_DISABLED);
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
    List<StockLot> readLots =
        lots.stream().filter(Objects::nonNull).map(Lot::stock).distinct().toList();
    var observed = stockReads.lotRows(caller.member(), readLots);
    var preliminary = decide(caller, ops, catalogue, early, lots, observed);
    var prepared =
        lotWritePlans.prepareLotBatch(
            caller.member(), readLots, intents(caller.member(), preliminary));
    var finalPlan = decide(caller, ops, catalogue, early, lots, prepared.lockedRows());
    var confirmed =
        lotWritePlans.confirmLotBatch(
            prepared, intents(caller.member(), finalPlan), changeSet.dryRun());
    return finalPlan.stream()
        .map(planned -> {
          if (planned instanceof Write<StockWrite>(StockWrite write)) {
            StockWrite confirmedWrite =
                switch (write) {
                  case Change change ->
                      new Change(
                          change.step(),
                          change.lot(),
                          change.current(),
                          change.target(),
                          change.rows(),
                          confirmed);
                  case Rebooking rebook -> new Rebooking(rebook.step(), rebook.move(), confirmed);
                };
            return ExchangeChangeSetAdapter.write(confirmedWrite);
          }
          return planned;
        })
        .toList();
  }

  /**
   * Re-evaluates the existing sequential Exchange policy against the supplied scalar observations.
   */
  private List<Planned<StockWrite>> decide(
      ExchangeCaller caller,
      List<ExchangeStockChangeSet.Op> ops,
      Map<Integer, ExchangeLotCatalogue.Entry> catalogue,
      List<Planned<StockWrite>> early,
      List<Lot> lots,
      Map<String, List<StockRow>> locked) {
    ExchangeRetiredItemCover cover = retiredCover(ops, catalogue, lots);
    Map<String, BigDecimal> quantities = new HashMap<>();
    ExchangeStockShares.Ledger ledger =
        new ExchangeStockShares.Ledger(
            ops.stream().anyMatch(op -> ExchangeStockChangeSet.SHARE.equals(op.op()))
                ? membershipQueryService.findDirectMembershipOrgUnitIds(caller.member())
                : Set.of());
    List<Planned<StockWrite>> plan = new ArrayList<>();
    for (int i = 0; i < ops.size(); i++) {
      if (early.get(i) != null) {
        plan.add(early.get(i));
        continue;
      }
      ExchangeStockChangeSet.Op op = ops.get(i);
      Lot lot = lots.get(i);
      String unit = lot.unit();
      List<StockRow> rows = Objects.requireNonNull(locked.get(lot.key()));
      BigDecimal current = quantities.computeIfAbsent(lot.key(), _ -> round(sum(rows), unit));
      BigDecimal expected = round(op.expectedQuantity().amount(), unit);
      BigDecimal target = round(op.quantity().amount(), unit);
      if (ExchangeStockShares.isRebooking(op)) {
        ExchangeStockShares.Decision decision =
            ledger.decide(op, lot.stock(), lot.key(), unit, rows, current, expected, target);
        ExchangeStockShares.Move move = decision.move();
        plan.add(
            move != null
                ? ExchangeChangeSetAdapter.write(new Rebooking(i, move, null))
                : ExchangeChangeSetAdapter.skip(
                    decision.refusal() == null ? UNCHANGED : REJECTED, decision.refusal()));
        continue;
      }
      boolean retired = Objects.requireNonNull(catalogue.get(i)).retired();
      BigDecimal rise = target.subtract(current);
      Planned<StockWrite> decided;
      if (current.compareTo(expected) != 0) {
        decided = ExchangeChangeSetAdapter.skip(REJECTED, VERSION_CONFLICT);
      } else if (current.compareTo(target) == 0) {
        decided = ExchangeChangeSetAdapter.skip(UNCHANGED, null);
      } else if (retired && rise.signum() > 0 && !cover.covers(lot.catalogueId(), rise)) {
        decided = ExchangeChangeSetAdapter.skip(REJECTED, ExchangeLotCatalogue.ITEM_RETIRED);
      } else if (current.signum() == 0
          && !Boolean.TRUE.equals(op.override())
          && removedElsewhere(caller, lot.key())) {
        decided = ExchangeChangeSetAdapter.skip(REJECTED, REMOVED_ELSEWHERE);
      } else if (rise.signum() < 0 && rise.negate().compareTo(round(free(rows), unit)) > 0) {
        decided = ExchangeChangeSetAdapter.skip(REJECTED, STOCK_EARMARKED);
      } else {
        quantities.put(lot.key(), target);
        ledger.changed(lot.key(), unit, rows, current, target);
        decided = ExchangeChangeSetAdapter.write(new Change(i, lot, current, target, rows, null));
      }
      if (retired) {
        cover.settle(lot.catalogueId(), decided instanceof Write<StockWrite>, expected, target);
      }
      plan.add(decided);
    }
    return plan;
  }

  /** Adapts only admitted Exchange decisions to the owning Inventory physical intent seam. */
  private static List<StockLotWritePlans.Intent> intents(
      UUID holder, List<Planned<StockWrite>> plan) {
    List<StockLotWritePlans.Intent> intents = new ArrayList<>();
    for (var planned : plan) {
      if (planned instanceof Write<StockWrite>(StockWrite write)) {
        intents.add(
            switch (write) {
              case Change set ->
                  new StockLotWritePlans.SetLotAmount(
                      set.step(), set.lot().stock(), set.current(), set.target());
              case Rebooking rebook ->
                  new StockLotWritePlans.RebookLot(
                      rebook.step(),
                      new RebookCommands.Rebook(
                          holder,
                          rebook.move().lot(),
                          rebook.move().orgUnitId(),
                          rebook.move().direction(),
                          rebook.move().amount().doubleValue()));
            });
      }
    }
    return List.copyOf(intents);
  }

  /**
   * Counts the falls every {@code set-quantity} of a retired item claims, before any op is decided,
   * so a move of retired stock applies whichever of its fall and rise comes first.
   *
   * @param ops the ops
   * @param catalogue each op's material or item, by op index
   * @param lots each op's lot, {@code null} for an op that ended before its lot
   * @return the cover the batch's rises of retired items draw on
   */
  private static @NotNull ExchangeRetiredItemCover retiredCover(
      @NotNull List<ExchangeStockChangeSet.Op> ops,
      @NotNull Map<Integer, ExchangeLotCatalogue.Entry> catalogue,
      @NotNull List<@Nullable Lot> lots) {
    ExchangeRetiredItemCover cover = new ExchangeRetiredItemCover();
    for (int i = 0; i < ops.size(); i++) {
      ExchangeStockChangeSet.Op op = ops.get(i);
      Lot lot = lots.get(i);
      if (lot != null
          && !ExchangeStockShares.isRebooking(op)
          && Objects.requireNonNull(catalogue.get(i)).retired()) {
        cover.offer(
            lot.catalogueId(),
            round(op.expectedQuantity().amount(), lot.unit()),
            round(op.quantity().amount(), lot.unit()));
      }
    }
    return cover;
  }

  /**
   * Runs a planned share or unshare; a dry run leaves a lot an earlier op of the batch would have
   * written to the plan, and a lot whose earlier rebooking the Lager refused is a version conflict,
   * since the plan counted that rebooking.
   *
   * @param caller the caller
   * @param batch the change set's id
   * @param rebooking the share or unshare
   * @param dryRun whether to write nothing
   * @param touched the lots earlier ops of the batch wrote or would have written; the move's lot is
   *     added
   * @param refused the lots whose earlier rebooking the Lager refused; the move's lot is added on a
   *     refusal
   * @return {@code applied} when the move ran or would run, else its refusal
   */
  private @NotNull Outcome runRebooking(
      @NotNull ExchangeCaller caller,
      @NotNull UUID batch,
      @NotNull Rebooking rebooking,
      boolean dryRun,
      @NotNull Set<String> touched,
      @NotNull Set<String> refused) {
    String key = rebooking.move().key();
    var outcome =
        lotWritePlans.executePreparedLot(
            Objects.requireNonNull(rebooking.confirmed()), rebooking.step(), dryRun);
    if (refused.contains(key)) {
      return Outcome.rejected(VERSION_CONFLICT);
    }
    if (!(dryRun && touched.contains(key))) {
      String refusal = shares.record(caller, batch, rebooking.move(), dryRun, outcome.refusal());
      if (refusal != null) {
        refused.add(key);
        return Outcome.rejected(refusal);
      }
    }
    touched.add(key);
    return Outcome.APPLIED;
  }

  /**
   * Sets a lot's pools back for an undo; all or nothing.
   *
   * @param member the member
   * @param lotKey the lot's key as the journal records it
   * @param unit the lot's unit
   * @param pools the net amount the client moved into each pool
   * @return whether every pool could be set back; {@code false} writes nothing
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean restorePools(
      @NotNull UUID member,
      @NotNull String lotKey,
      @NotNull String unit,
      @NotNull Map<UUID, BigDecimal> pools) {
    Optional<Lot> parsed = parseLot(lotKey, unit);
    if (parsed.isEmpty()) {
      return false;
    }
    return shares.takeBack(member, parsed.get().stock(), pools);
  }

  /**
   * Resolves each op's material, and falls back to the items for those no material matched.
   *
   * @param ops the ops
   * @return the catalogue entry, or the result that ends the op, by op index
   */
  private @NotNull Map<Integer, ExchangeLotCatalogue.Entry> resolve(
      @NotNull List<ExchangeStockChangeSet.Op> ops) {
    return lotCatalogue.resolve(ops.stream().map(ExchangeStockChangeSet.Op::material).toList());
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
        changeRepository.findLatestForKey(
            caller.member(), ExchangeFeedResource.STOCK.name(), lotKey);
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
  private static @NotNull Set<Change> moves(@NotNull List<Planned<StockWrite>> plan) {
    Map<UUID, BigDecimal> rises = new HashMap<>();
    for (Planned<StockWrite> planned : plan) {
      if (planned instanceof Write<StockWrite>(Change change)
          && change.target().compareTo(change.current()) > 0) {
        rises.merge(
            change.lot().catalogueId(),
            change.target().subtract(change.current()),
            BigDecimal::add);
      }
    }
    Set<Change> moves = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Planned<StockWrite> planned : plan) {
      if (planned instanceof Write<StockWrite>(Change change)
          && change.target().compareTo(change.current()) < 0) {
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
      @NotNull ExchangeCaller caller,
      @NotNull List<Planned<StockWrite>> plan,
      @NotNull Set<Change> moves) {
    long removals = 0;
    for (Planned<StockWrite> planned : plan) {
      if (planned instanceof Write<StockWrite>(Change change) && isRemoval(caller, change, moves)) {
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
        .map(ExchangeJournalEntry::getBeforeState)
        .map(json -> ExchangeStockJournal.State.read(objectMapper.readTree(json)).quantity());
  }

  /** Journals one quantity change after its confirmed owning physical execution. */
  private void journal(ExchangeCaller caller, UUID batch, Change change, Set<Change> moves) {
    journalService.record(
        caller,
        batch,
        ExchangeJournalAction.STOCK_SET_QUANTITY,
        change.lot().key(),
        isRemoval(caller, change, moves),
        ExchangeStockJournal.State.of(change.current(), change.lot().unit()).toJournal(),
        ExchangeStockJournal.State.of(change.target(), change.lot().unit()).toJournal());
  }

  /**
   * Counts the member's lots, personal and shared.
   *
   * @param member the member
   * @return the number of lots
   */
  private long lotCount(@NotNull UUID member) {
    return stockReads.countExchangeLots(member);
  }

  /**
   * Sums the amounts of rows.
   *
   * @param rows the rows
   * @return the sum
   */
  static @NotNull BigDecimal sum(@NotNull List<StockRow> rows) {
    BigDecimal sum = BigDecimal.ZERO;
    for (StockRow row : rows) {
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
  private static @NotNull BigDecimal free(@NotNull List<StockRow> rows) {
    BigDecimal free = BigDecimal.ZERO;
    for (StockRow row : rows) {
      double reserved =
          Math.max(Math.max(row.jobOrderReserved(), row.missionReserved()), row.donationReserved());
      free = free.add(BigDecimal.valueOf(Math.max(0.0, row.getAmount() - reserved)));
    }
    return free;
  }

  /** The offers the change set's book-outs lowered or removed. */
  private static final class OfferEffects {

    /** The offers lowered. */
    private int reduced;

    /** The offers removed. */
    private int removed;
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
     * The lot as the Lager's stock commands take it.
     *
     * @return the stock lot
     */
    @NotNull
    StockLot stock() {
      return new StockLot(material(), gameItem(), location(), quality(), stolen());
    }

    /**
     * The material's or item's id.
     *
     * @return the id
     */
    @NotNull
    UUID catalogueId() {
      return material != null ? material.getId() : Objects.requireNonNull(gameItem).getId();
    }

    /**
     * The key the change feed records for the lot.
     *
     * @return the key
     */
    @NotNull
    String key() {
      String head =
          material != null
              ? "m:" + material.getId()
              : "i:" + Objects.requireNonNull(gameItem).getId();
      return head
          + "|l:"
          + location.getId()
          + "|q:"
          + (quality == null ? 0 : quality)
          + "|s:"
          + (stolen ? "1" : "0");
    }
  }

  /** One change a planned op writes. */
  sealed interface StockWrite permits Change, Rebooking {}

  /**
   * An op that moves part of a lot's stock between personal and a pool.
   *
   * @param move the share or unshare
   */
  private record Rebooking(
      int step,
      @NotNull ExchangeStockShares.Move move,
      @Nullable StockLotWritePlans.Confirmed confirmed)
      implements StockWrite {}

  /**
   * An op that changes a lot.
   *
   * @param lot the lot
   * @param current what it holds now
   * @param target what it should hold
   * @param rows its locked rows, in the order a book-out takes them
   */
  private record Change(
      int step,
      @NotNull Lot lot,
      @NotNull BigDecimal current,
      @NotNull BigDecimal target,
      @NotNull List<StockRow> rows,
      @Nullable StockLotWritePlans.Confirmed confirmed)
      implements StockWrite {}
}
