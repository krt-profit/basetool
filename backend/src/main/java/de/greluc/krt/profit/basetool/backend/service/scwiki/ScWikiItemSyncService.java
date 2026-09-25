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

package de.greluc.krt.profit.basetool.backend.service.scwiki;

import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiItemDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiItemManufacturerDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiResponseDto;
import de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.GameItemKind;
import de.greluc.krt.profit.basetool.backend.model.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.SyncEventType;
import de.greluc.krt.profit.basetool.backend.model.SyncSourceSystem;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.service.SyncReportService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Syncs SC Wiki items into {@code game_item}, gated behind {@code krt.scwiki.item-sync-enabled}
 * (default {@code false}).
 *
 * <p>Mode A ({@code sync-all-items=false}) fetches only items already in scope, per UUID, and never
 * sweeps. Mode B ({@code sync-all-items=true}) pages every per-kind endpoint plus the residual
 * {@code /api/items} pass and tombstones orphans only on a vouched-for census (ADR-0195). Both
 * write only Wiki-owned columns and never overwrite the UEX-canonical name or flags.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScWikiItemSyncService {

  /** Typed envelope reference for the {@code /api/items}-shaped list endpoints used by Mode B. */
  private static final ParameterizedTypeReference<ScWikiResponseDto<ScWikiItemDto>> ITEM_PAGE_TYPE =
      new ParameterizedTypeReference<ScWikiResponseDto<ScWikiItemDto>>() {};

  private final ScWikiClient scWikiClient;
  private final ScWikiProperties properties;
  private final GameItemRepository gameItemRepository;
  private final BlueprintRepository blueprintRepository;
  private final ManufacturerRepository manufacturerRepository;
  private final SyncReportService syncReportService;

  /** Registry the orphan-sweep stand-down counter binds to. */
  private final MeterRegistry meterRegistry;

  /**
   * Lazily resolved self-proxy, so the per-item {@code REQUIRES_NEW} writes each open their own
   * transaction.
   */
  private final ObjectProvider<ScWikiItemSyncService> self;

  /**
   * Entry point invoked by {@link ScWikiScheduler}: a no-op when {@code item-sync-enabled} is off,
   * otherwise runs Mode B or Mode A depending on {@code sync-all-items}.
   *
   * @return the number of {@code game_item} rows written this run; {@code 0} when disabled
   */
  public int syncItems() {
    if (!Boolean.TRUE.equals(properties.itemSyncEnabled())) {
      log.info(
          "SC Wiki item sync invoked but disabled (krt.scwiki.item-sync-enabled=false) —"
              + " skipping.");
      return 0;
    }
    if (Boolean.TRUE.equals(properties.syncAllItems())) {
      return syncItemsBackfill();
    }
    return syncItemsClosure();
  }

  /**
   * Mode A: fetches every locally known and blueprint-referenced item via {@code GET
   * /api/items/{uuid}}, without an orphan sweep.
   *
   * @return the number of {@code game_item} rows filled or created; {@code 0} when there is nothing
   *     to fill
   */
  private int syncItemsClosure() {
    Set<UUID> targets = new LinkedHashSet<>(gameItemRepository.findAllExternalUuids());
    targets.addAll(blueprintRepository.findReferencedItemUuids());
    if (targets.isEmpty()) {
      log.info("SC Wiki item sync: no game_item UUIDs to fill — nothing to do.");
      return 0;
    }

    log.info("Starting SC Wiki item sync (closure mode) for {} item uuid(s)...", targets.size());
    UUID runId = syncReportService.beginRun();
    Instant now = Instant.now();
    int filled = 0;
    int created = 0;
    int missing = 0;
    int deferred = 0;

    for (UUID uuid : targets) {
      scWikiClient.paceForRateLimit();
      try {
        ScWikiItemDto dto =
            scWikiClient.fetchOne(
                properties.itemsEndpoint() + "/" + uuid, ScWikiItemDto.class, "item");
        ClosureOutcome outcome =
            self.getObject().fillClosureItemWithinTransaction(runId, uuid, dto, now);
        if (outcome == ClosureOutcome.FILLED) {
          filled++;
        } else if (outcome == ClosureOutcome.CREATED) {
          created++;
        } else {
          missing++;
        }
      } catch (OptimisticLockingFailureException e) {
        deferred++;
        log.warn("Optimistic lock collision filling SC Wiki item {}; deferring to next run", uuid);
      } catch (Exception e) {
        log.error("Failed to fill SC Wiki item {}", uuid, e);
      }
    }

    syncReportService.pruneRuns(SyncSourceSystem.SCWIKI);
    log.info(
        "Finished SC Wiki item sync: {} filled, {} created WIKI_ONLY, {} missing on Wiki, {}"
            + " deferred (lock collision).",
        filled,
        created,
        missing,
        deferred);
    return filled + created;
  }

  /**
   * Persists one closure-mode item in its own {@code REQUIRES_NEW} transaction, invoked through
   * {@link #self}.
   *
   * <p>A {@code null} {@code dto} logs {@link SyncEventType#WIKI_MISSING}; otherwise the row is
   * matched by {@code external_uuid} (or created {@code WIKI_ONLY}), its Wiki columns filled and
   * {@code source_systems} flipped to {@code BOTH}.
   *
   * @param runId the current run id for the {@code WIKI_MISSING} event
   * @param uuid the in-game asset UUID being filled
   * @param dto the Wiki item payload, or {@code null} if the Wiki did not return the item
   * @param now the shared {@code scwiki_synced_at} timestamp
   * @return {@link ClosureOutcome#FILLED}, {@link ClosureOutcome#CREATED} or {@link
   *     ClosureOutcome#MISSING}
   */
  @NotNull
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ClosureOutcome fillClosureItemWithinTransaction(
      UUID runId, UUID uuid, ScWikiItemDto dto, Instant now) {
    if (dto == null) {
      syncReportService.logScwikiEvent(
          runId,
          SyncEventType.WIKI_MISSING,
          "game_item",
          uuid,
          null,
          "GET /api/items/{uuid} returned no item — Wiki does not know this asset.");
      return ClosureOutcome.MISSING;
    }
    GameItem item = gameItemRepository.findByExternalUuid(uuid).orElse(null);
    boolean isNew = item == null;
    if (isNew) {
      item = new GameItem();
      item.setExternalUuid(uuid);
      item.setName(StringUtils.hasText(dto.name()) ? dto.name() : uuid.toString());
      item.setKind(GameItemKind.GENERIC);
      item.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    }
    applyWikiFields(item, dto, now);
    if (item.getSourceSystems() == GameItemSourceSystem.UEX_ONLY) {
      item.setSourceSystems(GameItemSourceSystem.BOTH);
    }
    gameItemRepository.save(item);
    return isNew ? ClosureOutcome.CREATED : ClosureOutcome.FILLED;
  }

  /**
   * Outcome of a single closure-mode item upsert, used by {@link #syncItemsClosure()} to tally the
   * run summary without re-reading the row.
   */
  public enum ClosureOutcome {
    /** An existing {@code game_item} row had its Wiki columns filled. */
    FILLED,
    /** A new {@code WIKI_ONLY} row was created for a blueprint-referenced item. */
    CREATED,
    /** The Wiki returned no item for the UUID; a {@code WIKI_MISSING} event was logged. */
    MISSING
  }

  /**
   * Mode B: runs every kind pass (most specific first) and the residual {@code GENERIC} pass, then
   * the orphan sweep when the seen set is non-empty and the census is vouched for (ADR-0195).
   *
   * @return the number of {@code game_item} rows created, linked or reconciled, or {@link
   *     GameItemRepository#countLiveScwikiItems() the live Wiki-linked item count} when nothing was
   *     written but at least one pass returned {@code 304}
   */
  private int syncItemsBackfill() {
    log.info("Starting SC Wiki item sync (FULL BACKFILL mode) — paging every kind endpoint...");
    boolean reconcile = Boolean.TRUE.equals(properties.reconcileUuidlessByName());
    List<GameItem> uuidlessUexRows =
        reconcile
            ? gameItemRepository.findByExternalUuidIsNullAndSourceSystems(
                GameItemSourceSystem.UEX_ONLY)
            : List.of();
    if (reconcile) {
      log.info(
          "Weg-2 reconciliation enabled: indexed {} uuid-less UEX row(s) for name/slug merge.",
          uuidlessUexRows.size());
    }
    BackfillContext ctx =
        new BackfillContext(
            syncReportService.beginRun(),
            Instant.now(),
            manufacturerRepository.findAll(),
            uuidlessUexRows,
            reconcile);
    boolean allPassesSucceeded = true;

    for (KindPass pass : kindPasses()) {
      allPassesSucceeded &= runKindPass(pass, ctx).succeeded();
    }
    KindPassResult residual =
        runKindPass(
            new KindPass(properties.itemsEndpoint(), GameItemKind.GENERIC, null, false), ctx);
    allPassesSucceeded &= residual.succeeded();

    if ((allPassesSucceeded || residualVouchesForPool(residual, ctx)) && !ctx.seen.isEmpty()) {
      int marked = self.getObject().markBackfillOrphansWithinTransaction(ctx.seen, ctx.now);
      if (marked > 0) {
        log.info(
            "Marked {} game_item row(s) scwiki_deleted (no longer in any Wiki kind feed).", marked);
      }
    } else {
      String reason = sweepSkipReason(ctx);
      meterRegistry
          .counter(
              MetricNames.CATALOGUE_ORPHAN_SWEEP_SKIPPED,
              MetricNames.TAG_SWEEP,
              MetricNames.SWEEP_ITEM,
              MetricNames.TAG_REASON,
              reason)
          .increment();
      log.warn(
          "Skipping cross-kind orphan sweep (reason={}, allPassesSucceeded={}, seenCount={}): a"
              + " partial, empty, 304 or sanity-capped kind fetch must never wipe Wiki-side state.",
          reason,
          allPassesSucceeded,
          ctx.seen.size());
    }

    syncReportService.pruneRuns(SyncSourceSystem.SCWIKI);
    log.info(
        "Finished SC Wiki item sync (full backfill): {} created WIKI_ONLY, {} linked"
            + " UEX_ONLY→BOTH, {} reconciled (uuid-less UEX merged by name/slug), {} skipped"
            + " (junk), {} deferred (lock collision), {} pass(es) empty/capped ({} of them"
            + " unchanged 304).",
        ctx.created,
        ctx.linked,
        ctx.reconciled,
        ctx.skipped,
        ctx.deferred,
        ctx.failedPasses,
        ctx.notModifiedPasses);

    int written = ctx.created + ctx.linked + ctx.reconciled;
    if (written == 0 && ctx.notModifiedPasses > 0) {
      long live = gameItemRepository.countLiveScwikiItems();
      log.info(
          "SC Wiki item backfill: every fetched kind pass was unchanged (304) and nothing was"
              + " written — reporting {} live Wiki-linked game_item row(s) instead of 0.",
          live);
      return (int) live;
    }
    return written;
  }

  /**
   * Soft-deletes, in its own {@code REQUIRES_NEW} transaction, every Wiki-written {@code game_item}
   * whose {@code external_uuid} was not seen this run; invoked through {@link #self}.
   *
   * @param seenExternalUuids the external UUIDs every kind pass touched this run
   * @param now the soft-delete timestamp
   * @return the number of rows marked {@code scwiki_deleted}
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int markBackfillOrphansWithinTransaction(Set<UUID> seenExternalUuids, Instant now) {
    return gameItemRepository.markScwikiDeletedExcept(seenExternalUuids, now);
  }

  /**
   * The ordered Mode-B kind passes, most specific first so a UUID served by several endpoints gets
   * the most specific kind; {@link #syncItemsBackfill()} appends the residual pass.
   *
   * @return the kind passes in claim order
   */
  @NotNull
  @Unmodifiable
  private List<KindPass> kindPasses() {
    return List.of(
        new KindPass(
            properties.weaponAttachmentsEndpoint(),
            GameItemKind.WEAPON_ATTACHMENT,
            properties.weaponAttachmentsFilter(),
            true),
        new KindPass(
            properties.weaponsEndpoint(), GameItemKind.WEAPON, properties.weaponsFilter(), true),
        new KindPass(
            properties.vehicleWeaponsEndpoint(),
            GameItemKind.VEHICLE_WEAPON,
            properties.vehicleWeaponsFilter(),
            true),
        new KindPass(
            properties.vehicleItemsEndpoint(),
            GameItemKind.VEHICLE_ITEM,
            properties.vehicleItemsFilter(),
            true),
        new KindPass(
            properties.armorEndpoint(), GameItemKind.ARMOR, properties.armorFilter(), true),
        new KindPass(
            properties.clothesEndpoint(), GameItemKind.CLOTHING, properties.clothesFilter(), true),
        new KindPass(properties.foodEndpoint(), GameItemKind.FOOD, properties.foodFilter(), true));
  }

  /**
   * Pages one endpoint, applies the sanity cap for kind passes, and upserts every UUID not already
   * claimed by an earlier pass.
   *
   * @param pass the endpoint, kind and filter to run
   * @param ctx the shared backfill state
   * @return the outcome: {@link KindPassResult#succeeded()} only for a complete, ingested pool, and
   *     {@link KindPassResult#enumerated()} with every UUID the pass was served
   */
  @NotNull
  private KindPassResult runKindPass(@NotNull KindPass pass, BackfillContext ctx) {
    Map<String, String> filters =
        StringUtils.hasText(pass.classificationFilter())
            ? Map.of("classification", pass.classificationFilter())
            : null;
    String label = pass.kind().name().toLowerCase(Locale.ROOT) + " items";
    ScWikiClient.FetchResult<ScWikiItemDto> result =
        scWikiClient.fetchAllPagesResult(pass.endpoint(), ITEM_PAGE_TYPE, label, null, filters);

    if (result.notModified()) {
      log.debug(
          "Wiki kind pass {} ({}) unchanged since last sync (304) — skipping ingest; no orphan"
              + " sweep this run.",
          pass.endpoint(),
          pass.kind());
      ctx.notModifiedPasses++;
      ctx.failedPasses++;
      return KindPassResult.notEnumerated();
    }
    List<ScWikiItemDto> fetched = result.data();
    if (fetched.isEmpty()) {
      log.warn(
          "Wiki kind pass {} ({}) returned no rows (empty-200) — skipping it; no orphan sweep"
              + " this run.",
          pass.endpoint(),
          pass.kind());
      ctx.failedPasses++;
      return KindPassResult.notEnumerated();
    }
    if (pass.applySanityCap() && fetched.size() > properties.backfillKindSanityCap()) {
      log.error(
          "Wiki kind pass {} ({}) returned {} rows, exceeding the sanity cap {} — assuming the"
              + " §3.4 full-pool quirk (missing/blank filter[classification]) and SKIPPING it to"
              + " avoid mis-filing the whole pool under {}.",
          pass.endpoint(),
          pass.kind(),
          fetched.size(),
          properties.backfillKindSanityCap(),
          pass.kind());
      ctx.failedPasses++;
      return KindPassResult.notEnumerated();
    }
    final boolean complete = result.complete();
    if (!complete) {
      log.warn(
          "Wiki kind pass {} ({}) came back INCOMPLETE ({} row(s) merged) — ingesting what arrived"
              + " but suppressing the cross-kind orphan sweep this run.",
          pass.endpoint(),
          pass.kind(),
          fetched.size());
      ctx.failedPasses++;
    }

    Set<UUID> enumerated = HashSet.newHashSet(fetched.size());
    for (ScWikiItemDto dto : fetched) {
      if (dto.uuid() == null) {
        continue;
      }
      enumerated.add(dto.uuid());
      if (!ctx.seen.add(dto.uuid())) {
        continue;
      }
      try {
        Manufacturer resolvedManufacturer = ctx.resolveManufacturer(dto.manufacturer());
        UUID reconcileUexId = ctx.resolveUuidlessUexMatch(dto);
        BackfillOutcome outcome =
            self.getObject()
                .upsertBackfillItemWithinTransaction(
                    dto, pass.kind(), ctx.runId, ctx.now, resolvedManufacturer, reconcileUexId);
        if (outcome == BackfillOutcome.CREATED) {
          ctx.created++;
        } else if (outcome == BackfillOutcome.LINKED) {
          ctx.linked++;
        } else if (outcome == BackfillOutcome.RECONCILED) {
          ctx.reconciled++;
          ctx.markConsumed(reconcileUexId);
        } else if (outcome == BackfillOutcome.SKIPPED) {
          ctx.skipped++;
        }
      } catch (OptimisticLockingFailureException e) {
        ctx.deferred++;
        log.warn(
            "Optimistic lock collision upserting SC Wiki item {} ({}); deferring to next run",
            dto.uuid(),
            pass.kind());
      } catch (Exception e) {
        log.error("Failed to upsert SC Wiki item {} ({})", dto.uuid(), pass.kind(), e);
      }
    }
    return new KindPassResult(complete, enumerated);
  }

  /**
   * Decides whether the residual {@code /api/items} pass alone vouches for the cross-kind census,
   * so the orphan sweep may run although a kind pass failed (ADR-0195).
   *
   * <p>Requires the residual pass to be complete and to have enumerated every UUID any pass was
   * served.
   *
   * @param residual the outcome of the residual {@code /api/items} pass
   * @param ctx the finished run's context, whose {@code seen} set is the union of every pass
   * @return {@code true} when the residual census contains every UUID the run saw
   */
  private static boolean residualVouchesForPool(
      @NotNull KindPassResult residual, @NotNull BackfillContext ctx) {
    if (!residual.succeeded()) {
      return false;
    }
    if (!residual.enumerated().containsAll(ctx.seen)) {
      Set<UUID> outsidePool = new HashSet<>(ctx.seen);
      outsidePool.removeAll(residual.enumerated());
      log.warn(
          "The residual /api/items pass enumerated a complete census of {} row(s), but {} row(s)"
              + " seen by the kind passes are not in it — the kind endpoints are not a subset of"
              + " the item pool after all, so the census is NOT vouched for and the orphan sweep"
              + " stands down.",
          residual.enumerated().size(),
          outsidePool.size());
      return false;
    }
    log.info(
        "{} kind pass(es) could not vouch for their own census, but the residual /api/items pass"
            + " enumerated a complete one of {} row(s) that contains every row they saw — the"
            + " cross-kind census stands and the orphan sweep runs (ADR-0195).",
        ctx.failedPasses,
        residual.enumerated().size());
    return true;
  }

  /**
   * Upserts one Wiki item under the given kind in its own {@code REQUIRES_NEW} transaction, invoked
   * through {@link #self}.
   *
   * <p>For an unknown UUID: skip junk names ({@link SyncEventType#SKIP_JUNK}), else fold into the
   * reconciliation candidate via {@link #reconcileIntoUexRow}, else create a {@code WIKI_ONLY} row.
   * An existing row gets its Wiki columns filled and its kind merged via {@link
   * GameItemKind#mergeMoreSpecific}.
   *
   * @param dto the Wiki item payload
   * @param passKind the kind derived from the source endpoint
   * @param runId the current run id for the sync-report events
   * @param now the shared {@code scwiki_synced_at} timestamp
   * @param resolvedManufacturer the manufacturer for new rows, or {@code null}
   * @param reconcileUexId the id of a uuid-less {@code UEX_ONLY} row matched by slug or name, or
   *     {@code null}
   * @return the {@link BackfillOutcome} for the row
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BackfillOutcome upsertBackfillItemWithinTransaction(
      @NotNull ScWikiItemDto dto,
      GameItemKind passKind,
      UUID runId,
      Instant now,
      Manufacturer resolvedManufacturer,
      UUID reconcileUexId) {
    GameItem item = gameItemRepository.findByExternalUuid(dto.uuid()).orElse(null);
    if (item == null) {
      if (shouldSkipNewItem(dto)) {
        syncReportService.logScwikiEvent(
            runId,
            SyncEventType.SKIP_JUNK,
            "game_item",
            dto.uuid(),
            dto.name(),
            "Wiki item failed the new-row guard (blank/markup name or NOITEM_Vehicle); not"
                + " created.");
        return BackfillOutcome.SKIPPED;
      }
      if (reconcileUexId != null
          && reconcileIntoUexRow(reconcileUexId, dto, passKind, runId, now) != null) {
        return BackfillOutcome.RECONCILED;
      }
      item = new GameItem();
      item.setExternalUuid(dto.uuid());
      item.setName(dto.name().trim());
      item.setKind(passKind);
      item.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
      item.setManufacturer(resolvedManufacturer);
      applyWikiFields(item, dto, now);
      gameItemRepository.save(item);
      syncReportService.logScwikiEvent(
          runId,
          SyncEventType.CREATED_WIKI_ONLY,
          "game_item",
          dto.uuid(),
          item.getName(),
          "New " + passKind + " row from the full Wiki item backfill.");
      return BackfillOutcome.CREATED;
    }

    item.setKind(GameItemKind.mergeMoreSpecific(item.getKind(), passKind));
    applyWikiFields(item, dto, now);
    BackfillOutcome outcome = BackfillOutcome.UPDATED;
    if (item.getSourceSystems() == GameItemSourceSystem.UEX_ONLY) {
      item.setSourceSystems(GameItemSourceSystem.BOTH);
      outcome = BackfillOutcome.LINKED;
    }
    gameItemRepository.save(item);
    return outcome;
  }

  /**
   * Merges a Wiki item into a uuid-less {@code UEX_ONLY} row within the caller's transaction,
   * re-checking under the row lock that the row still qualifies; logs {@link
   * SyncEventType#LINKED_VIA_NAME}.
   *
   * @param uexRowId the id of the uuid-less UEX row matched by slug or name
   * @param dto the Wiki item payload supplying the {@code external_uuid} and Wiki columns
   * @param passKind the kind derived from the source endpoint, merged more-specific-wins
   * @param runId the current run id for the {@link SyncEventType#LINKED_VIA_NAME} event
   * @param now the shared {@code scwiki_synced_at} timestamp
   * @return the merged row, or {@code null} if the candidate no longer qualifies
   */
  @Nullable
  private GameItem reconcileIntoUexRow(
      UUID uexRowId, ScWikiItemDto dto, GameItemKind passKind, UUID runId, Instant now) {
    GameItem uexRow = gameItemRepository.findById(uexRowId).orElse(null);
    if (uexRow == null
        || uexRow.getExternalUuid() != null
        || uexRow.getSourceSystems() != GameItemSourceSystem.UEX_ONLY) {
      return null;
    }
    uexRow.setExternalUuid(dto.uuid());
    uexRow.setKind(GameItemKind.mergeMoreSpecific(uexRow.getKind(), passKind));
    applyWikiFields(uexRow, dto, now);
    uexRow.setSourceSystems(GameItemSourceSystem.BOTH);
    gameItemRepository.save(uexRow);
    syncReportService.logScwikiEvent(
        runId,
        SyncEventType.LINKED_VIA_NAME,
        "game_item",
        dto.uuid(),
        uexRow.getName(),
        "Merged Wiki item into uuid-less UEX row '"
            + uexRow.getName()
            + "' by slug/name; backfilled external_uuid and flipped UEX_ONLY → BOTH.");
    return uexRow;
  }

  /** Outcome of a single Mode-B backfill upsert, used by {@link #runKindPass} to tally counters. */
  public enum BackfillOutcome {
    /** A new {@code WIKI_ONLY} row was created. */
    CREATED,
    /** An existing {@code UEX_ONLY} row was linked to the Wiki ({@code UEX_ONLY → BOTH}). */
    LINKED,
    /**
     * A uuid-less {@code UEX_ONLY} row absorbed this Wiki item's {@code external_uuid} by slug or
     * name and flipped to {@code BOTH}.
     */
    RECONCILED,
    /** An existing {@code BOTH} / {@code WIKI_ONLY} row had its Wiki columns refreshed. */
    UPDATED,
    /** The Wiki row failed the junk-name guard and no row was created. */
    SKIPPED
  }

  /**
   * Writes the Wiki-owned descriptive columns onto a game item; the UEX {@code name} and, on
   * existing rows, {@code manufacturer} are never touched.
   *
   * @param item the game item to update
   * @param dto the Wiki item payload
   * @param now timestamp for {@code scwiki_synced_at}
   */
  private void applyWikiFields(@NotNull GameItem item, @NotNull ScWikiItemDto dto, Instant now) {
    item.setScwikiSlug(dto.slug());
    item.setClassName(dto.className());
    item.setClassification(dto.classification());
    item.setClassificationLabel(dto.classificationLabel());
    item.setWikiType(dto.type());
    item.setWikiTypeLabel(dto.typeLabel());
    item.setWikiSubType(dto.subType());
    item.setWikiSubTypeLabel(dto.subTypeLabel());
    item.setSizeClass(parseSize(dto.size()));
    item.setGrade(dto.grade());
    item.setRarity(dto.rarity());
    item.setMass(dto.mass());
    if (dto.dimension() != null) {
      item.setDimensionX(dto.dimension().width());
      item.setDimensionY(dto.dimension().height());
      item.setDimensionZ(dto.dimension().length());
    }
    if (dto.description() != null) {
      item.setDescriptionEn(dto.description().get("en_EN"));
      item.setDescriptionDe(dto.description().get("de_DE"));
    }
    item.setIsBaseVariant(dto.isBaseVariant());
    item.setIsCraftable(dto.isCraftable());
    item.setScwikiGameVersionSeen(dto.version());
    item.setScwikiSyncedAt(now);
    item.setScwikiDeletedAt(null);
  }

  /**
   * Parses the Wiki {@code size} token into an integer size class.
   *
   * @param size raw size token
   * @return the integer size class, or {@code null} if not a plain integer
   */
  @Nullable
  private static Integer parseSize(String size) {
    if (!StringUtils.hasText(size)) {
      return null;
    }
    try {
      return Integer.valueOf(size.trim());
    } catch (NumberFormatException e) {
      log.debug("Non-numeric Wiki item size '{}' — leaving size_class null", size);
      return null;
    }
  }

  /**
   * Returns whether a Wiki item must not create a new {@code WIKI_ONLY} row: a blank name, a name
   * with markup ({@code <} or {@code >}), or a {@code NOITEM_Vehicle} type.
   *
   * @param dto the Wiki item payload
   * @return {@code true} if no new row should be created for this item
   */
  private static boolean shouldSkipNewItem(ScWikiItemDto dto) {
    if (!StringUtils.hasText(dto.name())) {
      return true;
    }
    String name = dto.name().trim();
    if (name.contains("<") || name.contains(">")) {
      return true;
    }
    return "NOITEM_Vehicle".equalsIgnoreCase(dto.type());
  }

  /**
   * One Mode-B endpoint pass: endpoint, assigned kind, optional classification filter, and whether
   * the sanity cap applies.
   *
   * @param endpoint Wiki list endpoint path
   * @param kind the kind assigned to rows from this endpoint
   * @param classificationFilter the {@code filter[classification]} value, or {@code null} / blank
   * @param applySanityCap whether to refuse a pool-sized response
   */
  private record KindPass(
      String endpoint, GameItemKind kind, String classificationFilter, boolean applySanityCap) {}

  /**
   * What one Mode-B pass achieved: whether it vouches for its census, and every UUID it was served,
   * regardless of which pass claimed it.
   *
   * @param succeeded whether the pass fetched a complete, usable pool and ingested it
   * @param enumerated every UUID the pass was served; empty when it enumerated nothing, never
   *     {@code null}
   */
  private record KindPassResult(boolean succeeded, @NotNull Set<UUID> enumerated) {

    /**
     * The outcome of a pass that never enumerated the feed at all — a {@code 304 Not Modified}, an
     * empty-200, or a fetch the sanity cap rejected. It failed and it vouches for nothing, so it
     * can neither satisfy the strict gate nor contribute a pool to the residual-census check.
     *
     * @return a failed result carrying no row identities
     */
    @Contract(value = " -> new", pure = true)
    private static @NotNull KindPassResult notEnumerated() {
      return new KindPassResult(false, Set.of());
    }
  }

  /**
   * Mutable per-run Mode-B state: run id, timestamp, cross-kind seen set, manufacturer lookup,
   * reconciliation index and result counters.
   */
  private static final class BackfillContext {
    private final UUID runId;
    private final Instant now;
    private final Set<UUID> seen = new HashSet<>();
    private final Map<UUID, Manufacturer> manufacturersByScwikiUuid = new HashMap<>();
    private final Map<String, Manufacturer> manufacturersByLowerName = new HashMap<>();

    /**
     * Weg-2 reconciliation index: lower-cased {@code uex_slug} → id of the single uuid-less {@code
     * UEX_ONLY} row carrying it. Keys carried by more than one row are dropped (ambiguous → never a
     * unique match), so a hit is always safe to merge.
     */
    private final Map<String, UUID> uexIdBySlug = new HashMap<>();

    /** Weg-2 reconciliation index: lower-cased name → id of the single uuid-less UEX row. */
    private final Map<String, UUID> uexIdByLowerName = new HashMap<>();

    /** UEX row ids already merged this run, so a second Wiki item can never re-consume one. */
    private final Set<UUID> consumedUexIds = new HashSet<>();

    private int created;
    private int linked;
    private int reconciled;
    private int skipped;
    private int deferred;
    private int failedPasses;

    /**
     * Count of passes that returned {@code 304 Not Modified}, a subset of {@link #failedPasses}.
     */
    private int notModifiedPasses;

    /**
     * Captures the run id and timestamp, indexes the manufacturer table by Wiki UUID and by
     * lower-cased name, and builds the Weg-2 reconciliation index over the uuid-less {@code
     * UEX_ONLY} rows (by {@code uex_slug} and by name, ambiguous keys excluded).
     *
     * @param runId the sync-report run id
     * @param now the shared {@code scwiki_synced_at} timestamp
     * @param manufacturers every manufacturer row, loaded once
     * @param uuidlessUexRows the uuid-less {@code UEX_ONLY} rows to index for reconciliation (empty
     *     when reconciliation is disabled)
     * @param reconcileEnabled whether Weg-2 name/slug reconciliation is active
     */
    BackfillContext(
        UUID runId,
        Instant now,
        List<Manufacturer> manufacturers,
        List<GameItem> uuidlessUexRows,
        boolean reconcileEnabled) {
      this.runId = runId;
      this.now = now;
      for (Manufacturer mfr : manufacturers) {
        if (mfr.getScwikiUuid() != null) {
          manufacturersByScwikiUuid.putIfAbsent(mfr.getScwikiUuid(), mfr);
        }
        if (StringUtils.hasText(mfr.getName())) {
          manufacturersByLowerName.putIfAbsent(mfr.getName().trim().toLowerCase(Locale.ROOT), mfr);
        }
      }
      if (reconcileEnabled) {
        Set<String> ambiguousSlugs = new HashSet<>();
        Set<String> ambiguousNames = new HashSet<>();
        for (GameItem row : uuidlessUexRows) {
          if (StringUtils.hasText(row.getUexSlug())) {
            indexUnique(
                uexIdBySlug,
                ambiguousSlugs,
                row.getUexSlug().trim().toLowerCase(Locale.ROOT),
                row.getId());
          }
          if (StringUtils.hasText(row.getName())) {
            indexUnique(
                uexIdByLowerName,
                ambiguousNames,
                row.getName().trim().toLowerCase(Locale.ROOT),
                row.getId());
          }
        }
      }
    }

    /**
     * Inserts {@code key → id} into {@code index} while the key stays unique; a second distinct id
     * removes the key and records it as ambiguous.
     *
     * @param index the slug- or name-keyed unique index being built
     * @param ambiguous the keys already found to be non-unique
     * @param key the normalised lookup key
     * @param id the candidate UEX row id
     */
    private static void indexUnique(
        Map<String, UUID> index, Set<String> ambiguous, String key, UUID id) {
      if (ambiguous.contains(key)) {
        return;
      }
      UUID existing = index.putIfAbsent(key, id);
      if (existing != null && !existing.equals(id)) {
        index.remove(key);
        ambiguous.add(key);
      }
    }

    /**
     * Resolves a Wiki item's manufacturer against the pre-loaded table by Wiki UUID, then
     * case-insensitive name; never creates one.
     *
     * @param dto the item's nested manufacturer reference, may be {@code null}
     * @return the matching local manufacturer, or {@code null}
     */
    @Contract("null -> null")
    @Nullable
    private Manufacturer resolveManufacturer(ScWikiItemManufacturerDto dto) {
      if (dto == null) {
        return null;
      }
      if (dto.uuid() != null) {
        Manufacturer byUuid = manufacturersByScwikiUuid.get(dto.uuid());
        if (byUuid != null) {
          return byUuid;
        }
      }
      if (StringUtils.hasText(dto.name())) {
        return manufacturersByLowerName.get(dto.name().trim().toLowerCase(Locale.ROOT));
      }
      return null;
    }

    /**
     * Finds the uuid-less {@code UEX_ONLY} row this Wiki item should merge into, preferring a
     * {@code uex_slug} match over a name match; only unambiguous, unconsumed candidates qualify.
     *
     * @param dto the Wiki item payload
     * @return the id of the uuid-less UEX row to merge into, or {@code null} when none is safe
     */
    @Nullable
    private UUID resolveUuidlessUexMatch(ScWikiItemDto dto) {
      if (StringUtils.hasText(dto.slug())) {
        UUID bySlug = uexIdBySlug.get(dto.slug().trim().toLowerCase(Locale.ROOT));
        if (bySlug != null && !consumedUexIds.contains(bySlug)) {
          return bySlug;
        }
      }
      if (StringUtils.hasText(dto.name())) {
        UUID byName = uexIdByLowerName.get(dto.name().trim().toLowerCase(Locale.ROOT));
        if (byName != null && !consumedUexIds.contains(byName)) {
          return byName;
        }
      }
      return null;
    }

    /**
     * Marks a UEX row id as consumed by a successful reconciliation so a second Wiki item sharing
     * the slug/name cannot merge into the same row (it falls through to {@code WIKI_ONLY} instead).
     *
     * @param uexId the id of the just-merged UEX row
     */
    private void markConsumed(UUID uexId) {
      consumedUexIds.add(uexId);
    }
  }

  /**
   * Names why the cross-kind orphan sweep stood down; only {@link
   * MetricNames#SWEEP_SKIP_INCOMPLETE} indicates a fault.
   *
   * @param ctx the finished run's context
   * @return one of the three bounded reason values; never {@code null}
   */
  private static String sweepSkipReason(BackfillContext ctx) {
    if (ctx.notModifiedPasses > 0 && ctx.failedPasses == ctx.notModifiedPasses) {
      return MetricNames.SWEEP_SKIP_NOT_MODIFIED;
    }
    if (ctx.seen.isEmpty()) {
      return MetricNames.SWEEP_SKIP_NO_ROWS;
    }
    return MetricNames.SWEEP_SKIP_INCOMPLETE;
  }
}
