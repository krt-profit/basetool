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

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReconciliationBusyException;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiItemPasses.KindPass;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiItemPasses.KindPassResult;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemManufacturerDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiResponseDto;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 * write only Wiki-owned columns and never overwrite the UEX-canonical name or flags. A craftable
 * item's blueprint stat base values go to {@code game_item_stat} (REQ-DATA-027); Mode B fetches the
 * full payload of every blueprint output still without one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScWikiItemSyncService {

  /** Typed envelope reference for the {@code /api/items}-shaped list endpoints used by Mode B. */
  private static final ParameterizedTypeReference<ScWikiResponseDto<ScWikiItemDto>> ITEM_PAGE_TYPE =
      new ParameterizedTypeReference<ScWikiResponseDto<ScWikiItemDto>>() {};

  private static final int RECONCILIATION_ATTEMPTS = 3;
  private static final Duration RECONCILIATION_RETRY_DELAY = Duration.ofMillis(25);

  private final ScWikiClient scWikiClient;
  private final ScWikiProperties properties;
  private final GameItemRepository gameItemRepository;
  private final BlueprintRepository blueprintRepository;
  private final ManufacturerRepository manufacturerRepository;
  private final SyncReportService syncReportService;
  private final GameItemStatWriter gameItemStatWriter;
  private final ScWikiItemStatFollowUp statFollowUp;
  private final ItemReconciliationService itemReconciliationService;
  private final ScWikiItemFieldWriter fieldWriter;

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
   * <p>An enabled run registers the unknown-class counter at zero before it writes.
   *
   * @return the number of {@code game_item} rows written this run; {@code 0} when disabled
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public int syncItems() {
    if (!Boolean.TRUE.equals(properties.itemSyncEnabled())) {
      log.info(
          "SC Wiki item sync invoked but disabled (krt.scwiki.item-sync-enabled=false) —"
              + " skipping.");
      return 0;
    }
    try (var _ = scWikiClient.openSyncRun()) {
      return Boolean.TRUE.equals(properties.syncAllItems())
          ? syncItemsBackfill()
          : syncItemsClosure();
    }
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
    int rejected = 0;
    int unavailable = 0;

    for (UUID uuid : targets) {
      try {
        ScWikiClient.DetailResult<ScWikiItemDto> detail =
            scWikiClient.fetchOneResult(
                properties.itemsEndpoint(), uuid, ScWikiItemDto.class, "item");
        if (detail.outcome() == ScWikiClient.DetailOutcome.UNAVAILABLE) {
          unavailable++;
          continue;
        }
        ClosureOutcome outcome =
            self.getObject().fillClosureItemWithinTransaction(runId, uuid, detail.data(), now);
        if (outcome == ClosureOutcome.FILLED) {
          filled++;
          syncReportService.recordApplied(1);
        } else if (outcome == ClosureOutcome.CREATED) {
          created++;
          syncReportService.recordApplied(1);
        } else {
          missing++;
          syncReportService.recordSkipped(1);
        }
      } catch (SourceIdentityMismatchException e) {
        rejected++;
        recordDetailIdentityConflict(runId, e);
        syncReportService.recordFailed(1);
      } catch (OptimisticLockingFailureException _) {
        deferred++;
        syncReportService.recordFailed(1);
        log.warn("Optimistic lock collision filling SC Wiki item {}; deferring to next run", uuid);
      } catch (Exception e) {
        syncReportService.recordFailed(1);
        log.error("Failed to fill SC Wiki item {}", uuid, e);
      }
    }

    syncReportService.pruneRuns(SyncSourceSystem.SCWIKI);
    log.info(
        "Finished SC Wiki item sync: {} filled, {} created WIKI_ONLY, {} missing on Wiki, {}"
            + " deferred (lock collision), {} rejected identities, {} unavailable details.",
        filled,
        created,
        missing,
        deferred,
        rejected,
        unavailable);
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
   * @param dto the Wiki item payload, or {@code null} after a confirmed HTTP 404
   * @param now the shared {@code scwiki_synced_at} timestamp
   * @return {@link ClosureOutcome#FILLED}, {@link ClosureOutcome#CREATED} or {@link
   *     ClosureOutcome#MISSING}
   */
  @NotNull
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ClosureOutcome fillClosureItemWithinTransaction(
      UUID runId, UUID uuid, @Nullable ScWikiItemDto dto, Instant now) {
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
    if (!uuid.equals(dto.uuid())) {
      throw new SourceIdentityMismatchException(uuid, dto.uuid());
    }
    GameItem item = gameItemRepository.findByExternalUuid(uuid).orElse(null);
    final boolean isNew = item == null;
    if (item == null) {
      item = new GameItem();
      item.setExternalUuid(uuid);
      item.setName(StringUtils.hasText(dto.name()) ? dto.name() : uuid.toString());
      item.setKind(GameItemKind.GENERIC);
      item.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    }
    fieldWriter.apply(item, dto, runId, now);
    if (item.getSourceSystems() == GameItemSourceSystem.UEX_ONLY) {
      item.setSourceSystems(GameItemSourceSystem.BOTH);
    }
    gameItemRepository.save(item);
    gameItemStatWriter.write(item, dto, runId);
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
   * @return the number of game items created, linked, reconciled, updated or restored
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
          "Exact-name reconciliation enabled: indexed {} uuid-less UEX candidate row(s).",
          uuidlessUexRows.size());
    }
    BackfillContext ctx =
        new BackfillContext(
            syncReportService.beginRun(),
            Instant.now(),
            manufacturerRepository.findAll(),
            uuidlessUexRows,
            reconcile ? gameItemRepository.findByUexItemIdIsNotNull() : List.of(),
            reconcile);
    List<KindPass> passes = new ArrayList<>(kindPasses());
    KindPass residualPass =
        new KindPass(properties.itemsEndpoint(), GameItemKind.GENERIC, null, false);
    passes.add(residualPass);
    Map<KindPass, ScWikiClient.FetchResult<ScWikiItemDto>> fetchedPasses = new LinkedHashMap<>();
    for (KindPass pass : passes) {
      fetchedPasses.put(pass, fetchKindPass(pass));
    }
    ctx.indexWikiCandidates(
        Objects.requireNonNull(fetchedPasses.get(residualPass)), fetchedPasses.values());
    boolean allPassesSucceeded = true;
    for (KindPass pass : kindPasses()) {
      allPassesSucceeded &=
          runKindPass(pass, Objects.requireNonNull(fetchedPasses.get(pass)), ctx).succeeded();
    }
    KindPassResult residual =
        runKindPass(residualPass, Objects.requireNonNull(fetchedPasses.get(residualPass)), ctx);
    allPassesSucceeded &= residual.succeeded();

    boolean fullCensus = allPassesSucceeded || residualVouchesForPool(residual, ctx);
    if (fullCensus) {
      syncReportService.recordCompleteCensus();
    }
    Set<UUID> protectedSeen =
        fullCensus && !ctx.seen.isEmpty() && ctx.failedWrites == 0
            ? protectKnownItemsOutsidePlayerScope(ctx)
            : null;
    if (protectedSeen != null) {
      int marked = self.getObject().markBackfillOrphansWithinTransaction(protectedSeen, ctx.now);
      if (marked > 0) {
        log.info(
            "Marked {} game_item row(s) scwiki_deleted (no longer in any Wiki kind feed).", marked);
      }
      syncReportService.recordSweep(true);
    } else {
      syncReportService.recordSweep(false);
      String reason = sweepSkipReason(ctx);
      meterRegistry
          .counter(
              CatalogueMetricNames.CATALOGUE_ORPHAN_SWEEP_SKIPPED,
              CatalogueMetricNames.TAG_SWEEP,
              CatalogueMetricNames.SWEEP_ITEM,
              MetricNames.TAG_REASON,
              reason)
          .increment();
      log.warn(
          "Skipping cross-kind orphan sweep (reason={}, allPassesSucceeded={}, seenCount={}): a"
              + " partial, empty or sanity-capped kind fetch must never wipe Wiki-side state.",
          reason,
          allPassesSucceeded,
          ctx.seen.size());
    }

    statFollowUp.fetchMissing((uuid, dto) -> {
      try {
        self.getObject().fillClosureItemWithinTransaction(ctx.runId, uuid, dto, ctx.now);
      } catch (SourceIdentityMismatchException e) {
        recordDetailIdentityConflict(ctx.runId, e);
        throw e;
      }
    });

    syncReportService.pruneRuns(SyncSourceSystem.SCWIKI);
    log.info(
        "Finished SC Wiki item sync (full backfill): {} created WIKI_ONLY, {} linked UEX_ONLY→BOTH,"
            + " {} reconciled (uuid-less UEX merged by name), {} updated, {} restored, {} skipped"
            + " (junk), {} deferred (lock collision), {} pass(es) empty/capped.",
        ctx.created,
        ctx.linked,
        ctx.reconciled,
        ctx.updated,
        ctx.restored,
        ctx.skipped,
        ctx.deferred,
        ctx.failedPasses);

    return ctx.created + ctx.linked + ctx.reconciled + ctx.updated + ctx.restored;
  }

  private void recordDetailIdentityConflict(UUID runId, SourceIdentityMismatchException failure) {
    syncReportService.recordIncomplete();
    syncReportService.logScwikiEvent(
        runId,
        SyncEventType.SOURCE_IDENTITY_CONFLICT,
        "game_item",
        failure.getRequestedUuid(),
        null,
        "requestedUuid=" + failure.getRequestedUuid() + ";servedUuid=" + failure.getServedUuid());
  }

  /**
   * Requires a full-scope existence census before treating a player-scope omission as a deletion.
   *
   * @param ctx this run's identity census and committed-write counters
   * @return protected cleanup identities, or {@code null} when absence cannot be proven
   */
  private @Nullable Set<UUID> protectKnownItemsOutsidePlayerScope(@NotNull BackfillContext ctx) {
    Set<UUID> omittedKnown = new HashSet<>(gameItemRepository.findKnownWikiExternalUuids());
    omittedKnown.removeAll(ctx.seen);
    if (omittedKnown.isEmpty()) {
      return ctx.seen;
    }
    ScWikiClient.ExistenceCensus full =
        scWikiClient.fetchExistenceCensus(
            properties.itemsEndpoint(), "items", properties.pageSize());
    if (!full.complete() || !full.uuids().containsAll(ctx.seen)) {
      syncReportService.recordIncomplete();
      log.warn("Skipping item orphan sweep: the full-scope existence census cannot prove absence.");
      return null;
    }
    Set<UUID> protectedSeen = new HashSet<>(ctx.seen);
    omittedKnown.retainAll(full.uuids());
    if (!omittedKnown.isEmpty()) {
      try {
        ctx.restored =
            self.getObject().restoreKnownItemsWithinTransaction(omittedKnown, ctx.runId, ctx.now);
        syncReportService.recordApplied(ctx.restored);
      } catch (RuntimeException e) {
        syncReportService.recordFailed(1);
        throw e;
      }
    }
    protectedSeen.addAll(omittedKnown);
    return protectedSeen;
  }

  /**
   * Clears false tombstones only for known positively verified Wiki identities in bounded batches.
   *
   * @param presentKnown known game UUIDs present in the complete all-asset census
   * @param runId source report run
   * @param now time of the presence correction
   * @return the committed number of restored item rows
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int restoreKnownItemsWithinTransaction(
      @NotNull Set<UUID> presentKnown, @NotNull UUID runId, @NotNull Instant now) {
    List<UUID> ids = new ArrayList<>(presentKnown);
    int restored = 0;
    for (int start = 0; start < ids.size(); start += SyncChunkWriter.DEFAULT_CHUNK_SIZE) {
      int end = Math.min(start + SyncChunkWriter.DEFAULT_CHUNK_SIZE, ids.size());
      restored += gameItemRepository.restoreKnownWikiItems(ids.subList(start, end), now);
    }
    if (restored > 0) {
      syncReportService.logScwikiEvent(
          runId,
          SyncEventType.SOURCE_RECORDS_RESTORED,
          "game_item",
          null,
          null,
          "restored=" + restored + ";scope=all_assets");
    }
    return restored;
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

  private List<KindPass> kindPasses() {
    return ScWikiItemPasses.ordered(properties);
  }

  /**
   * Fetches one kind pass without applying its source identities.
   *
   * @param pass the endpoint, kind and filter to run
   * @return the fetched pass and its census verdict
   */
  @NotNull
  private ScWikiClient.FetchResult<ScWikiItemDto> fetchKindPass(@NotNull KindPass pass) {
    @Nullable
    Map<String, String> filters =
        StringUtils.hasText(pass.classificationFilter())
            ? Map.of("classification", pass.classificationFilter())
            : null;
    String label = pass.kind().name().toLowerCase(Locale.ROOT) + " items";
    return scWikiClient.fetchAllPagesResult(
        pass.endpoint(), ITEM_PAGE_TYPE, label, ScWikiClient.ITEM_METADATA_INCLUDES, filters);
  }

  /**
   * Applies a prefetched kind pool after cross-kind identity ambiguity is known.
   *
   * @param pass the kind assigned to this endpoint
   * @param result the fetched pool and its census verdict
   * @param ctx the shared backfill state
   * @return the pass's completeness and enumerated identities
   */
  @NotNull
  private KindPassResult runKindPass(
      @NotNull KindPass pass,
      @NotNull ScWikiClient.FetchResult<ScWikiItemDto> result,
      @NotNull BackfillContext ctx) {

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
        ItemReconciliationIndex.Match match = ctx.resolveUuidlessUexMatch(dto);
        if (match.ambiguity() != null) {
          syncReportService.logScwikiEvent(
              ctx.runId,
              SyncEventType.MULTI_MATCH_AMBIGUOUS,
              "game_item",
              dto.uuid(),
              dto.name(),
              "Exact-name reconciliation declined;reason=" + match.ambiguity().name());
        }
        UUID reconcileUexId = match.candidateId();
        BackfillOutcome outcome =
            upsertWithReconciliationRetry(
                dto, pass.kind(), ctx, resolvedManufacturer, reconcileUexId);
        switch (outcome) {
          case CREATED -> ctx.created++;
          case LINKED -> ctx.linked++;
          case RECONCILED -> {
            ctx.reconciled++;
            ctx.markConsumed(reconcileUexId);
          }
          case SKIPPED -> {
            ctx.skipped++;
            syncReportService.recordSkipped(1);
          }
          case UPDATED -> ctx.updated++;
          case null -> throw new NullPointerException("backfill outcome");
        }
        if (outcome != BackfillOutcome.SKIPPED) {
          syncReportService.recordApplied(1);
        }
      } catch (GameItemReconciliationBusyException _) {
        ctx.deferred++;
        ctx.failedWrites++;
        syncReportService.recordFailed(1);
        log.warn(
            "Reference-owner locks stayed busy for SC Wiki item {}; deferring reconciliation",
            dto.uuid());
      } catch (OptimisticLockingFailureException _) {
        ctx.deferred++;
        ctx.failedWrites++;
        syncReportService.recordFailed(1);
        log.warn(
            "Optimistic lock collision upserting SC Wiki item {} ({}); deferring to next run",
            dto.uuid(),
            pass.kind());
      } catch (Exception e) {
        ctx.failedWrites++;
        syncReportService.recordFailed(1);
        log.error("Failed to upsert SC Wiki item {} ({})", dto.uuid(), pass.kind(), e);
      }
    }
    return new KindPassResult(complete, enumerated);
  }

  private BackfillOutcome upsertWithReconciliationRetry(
      ScWikiItemDto dto,
      GameItemKind kind,
      BackfillContext ctx,
      @Nullable Manufacturer manufacturer,
      @Nullable UUID uexId) {
    for (int attempt = 1; ; attempt++) {
      try {
        return self.getObject()
            .upsertBackfillItemWithinTransaction(
                dto, kind, ctx.runId, ctx.now, manufacturer, uexId);
      } catch (GameItemReconciliationBusyException failure) {
        if (attempt >= RECONCILIATION_ATTEMPTS || Thread.currentThread().isInterrupted()) {
          throw failure;
        }
        LockSupport.parkNanos(RECONCILIATION_RETRY_DELAY.toNanos());
        if (Thread.currentThread().isInterrupted()) {
          throw failure;
        }
      }
    }
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
   * @param reconcileUexId the id of a uuid-less {@code UEX_ONLY} row matched by a unique source
   *     name, or {@code null}
   * @return the {@link BackfillOutcome} for the row
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BackfillOutcome upsertBackfillItemWithinTransaction(
      @NotNull ScWikiItemDto dto,
      GameItemKind passKind,
      UUID runId,
      Instant now,
      @Nullable Manufacturer resolvedManufacturer,
      @Nullable UUID reconcileUexId) {
    GameItem item = gameItemRepository.findByExternalUuid(dto.uuid()).orElse(null);
    if (item == null) {
      if (ScWikiItemFieldWriter.shouldSkipNewItem(dto)) {
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
          && reconcileIntoUexRow(reconcileUexId, dto, passKind, resolvedManufacturer, runId, now)
              != null) {
        return BackfillOutcome.RECONCILED;
      }
      item = new GameItem();
      item.setExternalUuid(dto.uuid());
      item.setName(dto.name().trim());
      item.setKind(passKind);
      item.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
      item.setManufacturer(resolvedManufacturer);
      fieldWriter.apply(item, dto, runId, now);
      gameItemRepository.save(item);
      gameItemStatWriter.write(item, dto, runId);
      syncReportService.logScwikiEvent(
          runId,
          SyncEventType.CREATED_WIKI_ONLY,
          "game_item",
          dto.uuid(),
          item.getName(),
          "New " + passKind + " row from the full Wiki item backfill.");
      return BackfillOutcome.CREATED;
    }

    boolean reconciled = false;
    if (reconcileUexId != null) {
      GameItem survivor =
          itemReconciliationService.mergeExisting(
              item,
              reconcileUexId,
              runId,
              new ItemReconciliationService.WikiIdentity(
                  dto.name(), passKind, resolvedManufacturer, dto.className()));
      if (survivor != null) {
        item = survivor;
        reconciled = true;
      }
    }
    item.setKind(GameItemKind.mergeMoreSpecific(item.getKind(), passKind));
    fieldWriter.apply(item, dto, runId, now);
    BackfillOutcome outcome = reconciled ? BackfillOutcome.RECONCILED : BackfillOutcome.UPDATED;
    if (item.getSourceSystems() == GameItemSourceSystem.UEX_ONLY) {
      item.setSourceSystems(GameItemSourceSystem.BOTH);
      outcome = BackfillOutcome.LINKED;
    }
    gameItemRepository.save(item);
    gameItemStatWriter.write(item, dto, runId);
    return outcome;
  }

  /**
   * Merges a Wiki item into a uuid-less {@code UEX_ONLY} row within the caller's transaction,
   * re-checking that its source identity still qualifies; logs {@link
   * SyncEventType#LINKED_VIA_NAME}.
   *
   * @param uexRowId the id of the uuid-less UEX row matched by its unique exact source name
   * @param dto the Wiki item payload supplying the {@code external_uuid} and Wiki columns
   * @param passKind the kind derived from the source endpoint, merged more-specific-wins
   * @param resolvedManufacturer the Wiki source's resolved manufacturer
   * @param runId the current run id for the {@link SyncEventType#LINKED_VIA_NAME} event
   * @param now the shared {@code scwiki_synced_at} timestamp
   * @return the merged row, or {@code null} if the candidate no longer qualifies
   */
  @Nullable
  private GameItem reconcileIntoUexRow(
      UUID uexRowId,
      ScWikiItemDto dto,
      GameItemKind passKind,
      @Nullable Manufacturer resolvedManufacturer,
      UUID runId,
      Instant now) {
    GameItem uexRow = gameItemRepository.findById(uexRowId).orElse(null);
    if (uexRow == null
        || uexRow.getExternalUuid() != null
        || uexRow.getSourceSystems() != GameItemSourceSystem.UEX_ONLY
        || !ItemIdentityCompatibility.matches(
            uexRow, passKind, resolvedManufacturer, dto.className())) {
      return null;
    }
    uexRow.setExternalUuid(dto.uuid());
    uexRow.setKind(GameItemKind.mergeMoreSpecific(uexRow.getKind(), passKind));
    fieldWriter.apply(uexRow, dto, runId, now);
    uexRow.setSourceSystems(GameItemSourceSystem.BOTH);
    gameItemRepository.save(uexRow);
    gameItemStatWriter.write(uexRow, dto, runId);
    syncReportService.logScwikiEvent(
        runId,
        SyncEventType.LINKED_VIA_NAME,
        "game_item",
        dto.uuid(),
        uexRow.getName(),
        "Merged Wiki item into uuid-less UEX row '"
            + uexRow.getName()
            + "' by its unique source name; attached external_uuid and marked both sources.");
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
   * Mutable per-run Mode-B state: run id, timestamp, cross-kind seen set, manufacturer lookup,
   * reconciliation index and result counters.
   */
  private static final class BackfillContext {
    private final UUID runId;
    private final Instant now;
    private final Set<UUID> seen = new HashSet<>();
    private final Map<UUID, Manufacturer> manufacturersByScwikiUuid = new HashMap<>();
    private final Map<String, Manufacturer> manufacturersByLowerName = new HashMap<>();

    private final ItemReconciliationIndex reconciliationIndex;

    private int created;
    private int linked;
    private int reconciled;
    private int skipped;
    private int deferred;
    private int updated;
    private int restored;
    private int failedWrites;
    private int failedPasses;

    /**
     * Indexes both sides of every name and slug match before any row may be linked.
     *
     * @param residual complete unfiltered Wiki item pool
     * @param passes every fetched kind pool
     */
    private void indexWikiCandidates(
        ScWikiClient.FetchResult<ScWikiItemDto> residual,
        java.util.Collection<ScWikiClient.FetchResult<ScWikiItemDto>> passes) {
      reconciliationIndex.indexWikiCandidates(
          residual.data(),
          residual.complete(),
          passes.stream().map(ScWikiClient.FetchResult::data).toList());
    }

    /**
     * Captures the run id and timestamp, indexes the manufacturer table by Wiki UUID and by
     * lower-cased name, and builds the reconciliation index over the uuid-less {@code UEX_ONLY}
     * rows (by {@code uex_slug} and by name, ambiguous keys excluded).
     *
     * @param runId the sync-report run id
     * @param now the shared {@code scwiki_synced_at} timestamp
     * @param manufacturers every manufacturer row, loaded once
     * @param uuidlessUexRows the uuid-less {@code UEX_ONLY} rows to index for reconciliation (empty
     *     when reconciliation is disabled)
     * @param allUexRows every UEX source row, including variants already linked to Wiki identities
     * @param reconcileEnabled whether exact-name reconciliation is active
     */
    BackfillContext(
        UUID runId,
        Instant now,
        List<Manufacturer> manufacturers,
        List<GameItem> uuidlessUexRows,
        List<GameItem> allUexRows,
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
      reconciliationIndex =
          new ItemReconciliationIndex(
              reconcileEnabled ? uuidlessUexRows : List.of(),
              reconcileEnabled ? allUexRows : List.of());
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
     * Finds a uuid-less UEX row through a name unique in both censuses and a non-conflicting slug.
     *
     * @param dto the Wiki item payload
     * @return a safe candidate or a bounded ambiguity reason for the source report
     */
    @NotNull
    private ItemReconciliationIndex.Match resolveUuidlessUexMatch(ScWikiItemDto dto) {
      return reconciliationIndex.match(dto);
    }

    /**
     * Marks a UEX row id as consumed by a successful reconciliation so a second Wiki item sharing
     * the slug/name cannot merge into the same row (it falls through to {@code WIKI_ONLY} instead).
     *
     * @param uexId the id of the just-merged UEX row
     */
    private void markConsumed(@Nullable UUID uexId) {
      reconciliationIndex.markConsumed(uexId);
    }
  }

  /**
   * Names why the cross-kind orphan sweep stood down; only {@link
   * CatalogueMetricNames#SWEEP_SKIP_INCOMPLETE} indicates a fault.
   *
   * @param ctx the finished run's context
   * @return one of the two bounded reason values; never {@code null}
   */
  private static String sweepSkipReason(BackfillContext ctx) {

    if (ctx.seen.isEmpty()) {
      return CatalogueMetricNames.SWEEP_SKIP_NO_ROWS;
    }
    return CatalogueMetricNames.SWEEP_SKIP_INCOMPLETE;
  }
}
