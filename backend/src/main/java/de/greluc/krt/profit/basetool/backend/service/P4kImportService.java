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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.dto.p4k.P4kBlueprintDto;
import de.greluc.krt.profit.basetool.backend.dto.p4k.P4kCatalogDto;
import de.greluc.krt.profit.basetool.backend.dto.p4k.P4kCommodityDto;
import de.greluc.krt.profit.basetool.backend.dto.p4k.P4kIngredientDto;
import de.greluc.krt.profit.basetool.backend.dto.p4k.P4kItemDto;
import de.greluc.krt.profit.basetool.backend.dto.p4k.P4kManufacturerDto;
import de.greluc.krt.profit.basetool.backend.dto.p4k.P4kShipDto;
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.GameItemKind;
import de.greluc.krt.profit.basetool.backend.model.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.SyncEventType;
import de.greluc.krt.profit.basetool.backend.model.SyncSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.dto.P4kImportResultDto;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.service.scwiki.BlueprintOutputNameOverrides;
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * KRT P4K Reader catalog import engine. Consumes the single JSON catalog the external KRT P4K
 * Reader extracts from the game's {@code Data/Game2.dcb} (DataForge) and <em>enriches, reconciles
 * and (opt-in) seeds</em> the {@code game_item} / {@code ship_type} / {@code manufacturer} / {@code
 * material} / {@code blueprint} rows against it.
 *
 * <p>The game itself reads this DCB, so the catalog is the upstream source of truth — it routinely
 * carries player-facing records UEX and the SC Wiki have not catalogued yet. For a matched row the
 * import enriches in place (fill-if-null); for an unmatched record it either <b>seeds a brand-new
 * {@code source = P4K} row</b> (when seeding is enabled and the record passes the real-record
 * filter) or reports it as unmatched.
 *
 * <p>Two entry points share the same reconciliation logic:
 *
 * <ul>
 *   <li>{@link #previewImport(MultipartFile)} computes every action ({@code dryRun = true}, seeding
 *       analysed) without writing anything or emitting audit rows.
 *   <li>{@link #applyImport(MultipartFile, boolean)} applies the same actions inside a read-write
 *       transaction, emitting {@code SyncReportService} events for UUID backfills ({@link
 *       SyncEventType#LINKED_VIA_NAME}), UUID conflicts ({@link SyncEventType#BACKFILL_AMBIGUOUS})
 *       and seeded rows ({@link SyncEventType#CREATED_FROM_P4K}), one {@link
 *       SyncEventType#SYNC_RUN_SUMMARY} per run, and pruning the P4K sync-report history at the
 *       end.
 * </ul>
 *
 * <p><b>UUID-conflict policy (keep both + report).</b> On every matched row the import always
 * stamps {@code p4k_uuid = <P4K guid>} and {@code p4k_synced_at = now}. It backfills the canonical
 * UUID ({@code external_uuid} for items/ships, {@code scwiki_uuid} for manufacturers / commodities
 * / blueprints) only when that column is currently null AND no other row already holds the GUID —
 * which only happens when the row was reached through the case-insensitive name/slug fallback,
 * hence the backfill is logged as {@link SyncEventType#LINKED_VIA_NAME}. When the existing
 * canonical UUID is non-null and differs from the P4K GUID it is left untouched and a {@link
 * SyncEventType#BACKFILL_AMBIGUOUS} event is logged. {@code source_systems} is never changed on an
 * enriched row — P4K participation there is signalled solely by a non-null {@code p4k_synced_at}.
 *
 * <p><b>Seeding (opt-in).</b> New rows are inserted only when {@code seedNew} is set on apply (a
 * preview always shows the potential). The real-record filter requires a parseable GUID, a resolved
 * player-facing name (the export leaves dev/test/template records nameless) and rejects identifiers
 * that smell like dev assets; every insert is guarded against the relevant UNIQUE columns. Seeded
 * commodities are inserted {@code is_visible = false} so they stay out of trading flows until an
 * admin reviews them, mirroring the SC-Wiki commodity sync. Enrichment is otherwise fill-if-null:
 * an existing non-null value is never overwritten. Manufacturers are processed first so items and
 * ships can link to them (including freshly-seeded ones) via the manufacturer GUID index.
 *
 * <p>Enrichment mutates by dirty-checking the managed entities loaded through the repositories (no
 * explicit {@code save()} on matched rows, per the CLAUDE.md concurrency rules); seeded rows are
 * persisted explicitly. The audit rows are saved through {@code SyncReportService} and commit
 * atomically with the data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class P4kImportService {

  /**
   * Identifier tokens that mark a DataForge record as a developer / test / template asset rather
   * than shippable player content. Matched as delimited words (see {@link #containsToken}) against
   * engine identifiers ({@code class_name}, record {@code path}, blueprint {@code key},
   * manufacturer {@code code}) — never against the localized display name.
   */
  private static final Set<String> DEV_TOKENS =
      Set.of(
          "test",
          "template",
          "dummy",
          "placeholder",
          "debug",
          "tutorial",
          "sample",
          "proto",
          "prototype",
          "deprecated",
          "unused",
          "wip",
          "ghost",
          "dev");

  private final ObjectMapper objectMapper;
  private final GameItemRepository gameItemRepository;
  private final ShipTypeRepository shipTypeRepository;
  private final ManufacturerRepository manufacturerRepository;
  private final MaterialRepository materialRepository;
  private final BlueprintRepository blueprintRepository;
  private final SyncReportService syncReportService;

  /**
   * Curated corrections for CIG-mislabeled blueprint {@code output_name}s (#327). The seed path
   * writes {@code output_name} from the produced item's name, so it applies the same guarded
   * override the SC Wiki sync uses, for consistency. See {@link BlueprintOutputNameOverrides}.
   */
  private final BlueprintOutputNameOverrides outputNameOverrides;

  /**
   * Previews a P4K catalog import: parses the upload and computes every reconciliation action —
   * including how many brand-new rows seeding <em>would</em> create — without persisting anything
   * or emitting audit rows.
   *
   * @param bytes the uploaded P4K catalog JSON bytes
   * @return the per-type action summary with {@code dryRun = true}, {@code seedingEnabled = true}
   *     and {@code runId = null}
   * @throws BadRequestException if the catalog is empty or not valid P4K catalog JSON
   */
  @NotNull
  public P4kImportResultDto previewImport(byte[] bytes) {
    P4kCatalogDto catalog = parse(bytes);
    return reconcile(catalog, false, true, null);
  }

  /**
   * Applies a P4K catalog import: enriches / reconciles the matching local rows and, when {@code
   * seedNew} is set, seeds brand-new rows for unmatched player-facing records. Emits sync-report
   * events for backfills, conflicts and seeded rows plus one run summary, then prunes the P4K
   * report history.
   *
   * <p>The whole apply runs in a single transaction and is deliberately all-or-nothing: an
   * unexpected failure on any record (e.g. a constraint violation while seeding) rolls the entire
   * run back — every enrichment included — rather than leaving the master data half-updated. There
   * is no per-record error isolation; re-run the import after correcting the offending catalog
   * record.
   *
   * @param bytes the uploaded P4K catalog JSON bytes
   * @param seedNew {@code true} to insert new {@code source = P4K} rows for unmatched records that
   *     pass the real-record filter; {@code false} to enrich existing rows only
   * @return the per-type action summary with {@code dryRun = false} and the stamped {@code runId}
   * @throws BadRequestException if the catalog is empty or not valid P4K catalog JSON
   */
  @Transactional
  @NotNull
  public P4kImportResultDto applyImport(byte[] bytes, boolean seedNew) {
    P4kCatalogDto catalog = parse(bytes);
    UUID runId = syncReportService.beginRun();
    P4kImportResultDto result = reconcile(catalog, true, seedNew, runId);
    syncReportService.logP4kEvent(
        runId, SyncEventType.SYNC_RUN_SUMMARY, "catalog", null, null, summaryDetail(result));
    syncReportService.pruneRuns(SyncSourceSystem.P4K);
    log.info("P4K import applied (run {}): {}", runId, summaryDetail(result));
    return result;
  }

  // ────────────────────────────────────────────────────────── reconciliation ──

  /**
   * Runs the full reconciliation across all five types. Manufacturers are processed first to build
   * the GUID→entity index that item and ship linking consults (including any seeded this run).
   *
   * @param catalog the parsed catalog
   * @param apply {@code true} to persist mutations and emit audit rows; {@code false} for a dry run
   * @param seed {@code true} to seed new rows for unmatched player-facing records
   * @param runId the audit run id ({@code null} on a dry run)
   * @return the aggregate result
   */
  @NotNull
  private P4kImportResultDto reconcile(
      @NotNull P4kCatalogDto catalog, boolean apply, boolean seed, @Nullable UUID runId) {
    Instant now = Instant.now();

    Map<String, Manufacturer> manufacturerByGuid = new HashMap<>();
    P4kImportResultDto.Counts manufacturers =
        reconcileManufacturers(
            catalog.manufacturers(), manufacturerByGuid, apply, seed, runId, now);
    P4kImportResultDto.Counts items =
        reconcileItems(catalog.items(), manufacturerByGuid, apply, seed, runId, now);
    P4kImportResultDto.Counts ships =
        reconcileShips(catalog.ships(), manufacturerByGuid, apply, seed, runId, now);
    P4kImportResultDto.Counts commodities =
        reconcileCommodities(catalog.commodities(), apply, seed, runId, now);

    BlueprintOutcome blueprintOutcome =
        reconcileBlueprints(catalog.blueprints(), apply, seed, runId, now);

    return new P4kImportResultDto(
        !apply,
        seed,
        manufacturers,
        items,
        ships,
        commodities,
        blueprintOutcome.counts(),
        blueprintOutcome.ingredientsResolved(),
        runId);
  }

  /**
   * Reconciles manufacturers: resolve by {@code scwiki_uuid} → {@code abbreviation} → {@code name},
   * backfill {@code scwiki_uuid} (guarded), fill-if-null {@code scwiki_code} / {@code description},
   * stamp the P4K lane, or seed a new manufacturer. Populates {@code byGuid} with the resolved (or
   * seeded) entity keyed by the inbound P4K GUID so items / ships can link to it.
   *
   * @param dtos inbound manufacturer records (may be {@code null})
   * @param byGuid output index (P4K GUID → manufacturer) the caller passes on to items / ships
   * @param apply whether to persist mutations and emit audit rows
   * @param seed whether to seed new rows for unmatched records
   * @param runId the audit run id, or {@code null} on a dry run
   * @param now the import timestamp stamped on {@code p4k_synced_at}
   * @return the manufacturer counts
   */
  @NotNull
  private P4kImportResultDto.Counts reconcileManufacturers(
      @Nullable List<P4kManufacturerDto> dtos,
      @NotNull Map<String, Manufacturer> byGuid,
      boolean apply,
      boolean seed,
      @Nullable UUID runId,
      @NotNull Instant now) {
    CountsAccumulator counts = new CountsAccumulator();
    if (dtos == null) {
      return counts.toCounts();
    }
    for (P4kManufacturerDto dto : dtos) {
      UUID guid = parseUuid(dto.guid());
      Manufacturer target = resolveManufacturer(dto, guid);
      if (target == null) {
        if (maybeSeedManufacturer(dto, guid, byGuid, seed, apply, runId, now)) {
          counts.created++;
        } else {
          counts.unmatched++;
        }
        continue;
      }
      counts.matched++;
      if (guid != null) {
        byGuid.put(dto.guid(), target);
      }

      // Canonical UUID backfill (scwiki_uuid), guarded against UNIQUE collisions.
      backfillCanonicalUuid(
          counts,
          guid,
          target.getScwikiUuid(),
          () -> manufacturerRepository.findByScwikiUuid(guid).isPresent(),
          value -> {
            if (apply) {
              target.setScwikiUuid(value);
            }
          },
          existing ->
              logConflict(apply, runId, "manufacturer", existing, guid, dto.name(), "scwiki_uuid"),
          apply,
          runId,
          "manufacturer",
          dto.name());

      boolean enriched = false;
      enriched |=
          fillIfNull(target.getScwikiCode(), dto.code(), v -> target.setScwikiCode(v), apply);
      enriched |=
          fillIfNull(target.getDescription(), dto.desc(), v -> target.setDescription(v), apply);
      if (enriched) {
        counts.enriched++;
      }

      stampP4k(apply, guid, now, v -> target.setP4kUuid(v), t -> target.setP4kSyncedAt(t));
    }
    return counts.toCounts();
  }

  /**
   * Resolves an inbound manufacturer to a local row through the chain {@code scwiki_uuid} →
   * case-insensitive {@code abbreviation} → case-insensitive {@code name}.
   *
   * @param dto the inbound manufacturer
   * @param guid the parsed GUID, or {@code null} when unparseable
   * @return the matched manufacturer, or {@code null} if none matched
   */
  @Nullable
  private Manufacturer resolveManufacturer(@NotNull P4kManufacturerDto dto, @Nullable UUID guid) {
    if (guid != null) {
      Optional<Manufacturer> byUuid = manufacturerRepository.findByScwikiUuid(guid);
      if (byUuid.isPresent()) {
        return byUuid.get();
      }
    }
    if (dto.code() != null && !dto.code().isBlank()) {
      Optional<Manufacturer> byCode =
          manufacturerRepository.findFirstByAbbreviationIgnoreCaseOrderByCreatedAtAsc(
              dto.code().trim());
      if (byCode.isPresent()) {
        return byCode.get();
      }
    }
    if (dto.name() != null && !dto.name().isBlank()) {
      return manufacturerRepository.findByNameIgnoreCase(dto.name().trim()).orElse(null);
    }
    return null;
  }

  /**
   * Reconciles items: resolve by {@code external_uuid} → unique case-insensitive {@code class_name}
   * → unique case-insensitive {@code name}, backfill {@code external_uuid} (guarded), fill-if-null
   * {@code class_name} / {@code manufacturer} / {@code mass} / {@code description_en} / {@code
   * description_de}, stamp the P4K lane, or seed a new item.
   *
   * @param dtos inbound item records (may be {@code null})
   * @param manufacturerByGuid the GUID→manufacturer index built from the manufacturer pass
   * @param apply whether to persist mutations and emit audit rows
   * @param seed whether to seed new rows for unmatched records
   * @param runId the audit run id, or {@code null} on a dry run
   * @param now the import timestamp
   * @return the item counts
   */
  @NotNull
  private P4kImportResultDto.Counts reconcileItems(
      @Nullable List<P4kItemDto> dtos,
      @NotNull Map<String, Manufacturer> manufacturerByGuid,
      boolean apply,
      boolean seed,
      @Nullable UUID runId,
      @NotNull Instant now) {
    CountsAccumulator counts = new CountsAccumulator();
    if (dtos == null) {
      return counts.toCounts();
    }
    for (P4kItemDto dto : dtos) {
      UUID guid = parseUuid(dto.guid());
      GameItem target =
          resolveSingle(
              guid,
              gameItemRepository::findByExternalUuid,
              dto.className(),
              gameItemRepository::findByClassNameIgnoreCase,
              dto.name(),
              gameItemRepository::findByNameIgnoreCase);
      if (target == null) {
        if (maybeSeedItem(dto, guid, manufacturerByGuid, seed, apply, runId, now)) {
          counts.created++;
        } else {
          counts.unmatched++;
        }
        continue;
      }
      counts.matched++;

      backfillExternalUuidGameItem(counts, target, guid, dto.name(), apply, runId);

      boolean enriched = false;
      enriched |=
          fillIfNull(target.getClassName(), dto.className(), v -> target.setClassName(v), apply);
      Manufacturer mfg = manufacturerByGuid.get(dto.manufacturerGuid());
      enriched |= fillIfNull(target.getManufacturer(), mfg, v -> target.setManufacturer(v), apply);
      enriched |= fillIfNull(target.getMass(), dto.mass(), v -> target.setMass(v), apply);
      enriched |=
          fillIfNull(target.getDescriptionEn(), dto.desc(), v -> target.setDescriptionEn(v), apply);
      enriched |=
          fillIfNull(
              target.getDescriptionDe(), dto.descDe(), v -> target.setDescriptionDe(v), apply);
      if (enriched) {
        counts.enriched++;
      }

      stampP4k(apply, guid, now, v -> target.setP4kUuid(v), t -> target.setP4kSyncedAt(t));
    }
    return counts.toCounts();
  }

  /**
   * Reconciles ships: resolve by {@code external_uuid} → unique case-insensitive {@code class_name}
   * → case-insensitive {@code name}, backfill {@code external_uuid} (guarded), fill-if-null {@code
   * class_name} / {@code manufacturer} / {@code description_en} / {@code description_de}, stamp the
   * P4K lane, or seed a new ship. Crew and SCU are deliberately left untouched on enriched rows and
   * unset on seeded rows (the DCB entity does not carry them).
   *
   * @param dtos inbound ship records (may be {@code null})
   * @param manufacturerByGuid the GUID→manufacturer index built from the manufacturer pass
   * @param apply whether to persist mutations and emit audit rows
   * @param seed whether to seed new rows for unmatched records
   * @param runId the audit run id, or {@code null} on a dry run
   * @param now the import timestamp
   * @return the ship counts
   */
  @NotNull
  private P4kImportResultDto.Counts reconcileShips(
      @Nullable List<P4kShipDto> dtos,
      @NotNull Map<String, Manufacturer> manufacturerByGuid,
      boolean apply,
      boolean seed,
      @Nullable UUID runId,
      @NotNull Instant now) {
    CountsAccumulator counts = new CountsAccumulator();
    if (dtos == null) {
      return counts.toCounts();
    }
    for (P4kShipDto dto : dtos) {
      UUID guid = parseUuid(dto.guid());
      ShipType target =
          resolveSingle(
              guid,
              shipTypeRepository::findByExternalUuid,
              dto.className(),
              shipTypeRepository::findByClassNameIgnoreCase,
              dto.name(),
              name ->
                  shipTypeRepository.findByNameIgnoreCase(name).map(List::of).orElseGet(List::of));
      if (target == null) {
        if (maybeSeedShip(dto, guid, manufacturerByGuid, seed, apply, runId, now)) {
          counts.created++;
        } else {
          counts.unmatched++;
        }
        continue;
      }
      counts.matched++;

      backfillExternalUuidShipType(counts, target, guid, dto.name(), apply, runId);

      boolean enriched = false;
      enriched |=
          fillIfNull(target.getClassName(), dto.className(), v -> target.setClassName(v), apply);
      Manufacturer mfg = manufacturerByGuid.get(dto.manufacturerGuid());
      enriched |= fillIfNull(target.getManufacturer(), mfg, v -> target.setManufacturer(v), apply);
      enriched |=
          fillIfNull(target.getDescriptionEn(), dto.desc(), v -> target.setDescriptionEn(v), apply);
      enriched |=
          fillIfNull(
              target.getDescriptionDe(), dto.descDe(), v -> target.setDescriptionDe(v), apply);
      if (enriched) {
        counts.enriched++;
      }

      stampP4k(apply, guid, now, v -> target.setP4kUuid(v), t -> target.setP4kSyncedAt(t));
    }
    return counts.toCounts();
  }

  /**
   * Reconciles commodities against the {@code material} table: resolve by {@code scwiki_uuid} →
   * case-insensitive {@code name}, backfill {@code scwiki_uuid} (guarded), fill-if-null {@code
   * description}, stamp the P4K lane, or seed a new (invisible) material.
   *
   * @param dtos inbound commodity records (may be {@code null})
   * @param apply whether to persist mutations and emit audit rows
   * @param seed whether to seed new rows for unmatched records
   * @param runId the audit run id, or {@code null} on a dry run
   * @param now the import timestamp
   * @return the commodity counts
   */
  @NotNull
  private P4kImportResultDto.Counts reconcileCommodities(
      @Nullable List<P4kCommodityDto> dtos,
      boolean apply,
      boolean seed,
      @Nullable UUID runId,
      @NotNull Instant now) {
    CountsAccumulator counts = new CountsAccumulator();
    if (dtos == null) {
      return counts.toCounts();
    }
    for (P4kCommodityDto dto : dtos) {
      UUID guid = parseUuid(dto.guid());
      Material target = resolveCommodity(dto, guid);
      if (target == null) {
        if (maybeSeedCommodity(dto, guid, seed, apply, runId, now)) {
          counts.created++;
        } else {
          counts.unmatched++;
        }
        continue;
      }
      counts.matched++;

      backfillCanonicalUuid(
          counts,
          guid,
          target.getScwikiUuid(),
          () -> materialRepository.findByScwikiUuid(guid).isPresent(),
          value -> {
            if (apply) {
              target.setScwikiUuid(value);
            }
          },
          existing ->
              logConflict(apply, runId, "material", existing, guid, dto.name(), "scwiki_uuid"),
          apply,
          runId,
          "material",
          dto.name());

      boolean enriched =
          fillIfNull(target.getDescription(), dto.desc(), v -> target.setDescription(v), apply);
      if (enriched) {
        counts.enriched++;
      }

      stampP4k(apply, guid, now, v -> target.setP4kUuid(v), t -> target.setP4kSyncedAt(t));
    }
    return counts.toCounts();
  }

  /**
   * Resolves an inbound commodity to a local material through the chain {@code scwiki_uuid} →
   * case-insensitive {@code name}.
   *
   * @param dto the inbound commodity
   * @param guid the parsed GUID, or {@code null}
   * @return the matched material, or {@code null}
   */
  @Nullable
  private Material resolveCommodity(@NotNull P4kCommodityDto dto, @Nullable UUID guid) {
    if (guid != null) {
      Optional<Material> byUuid = materialRepository.findByScwikiUuid(guid);
      if (byUuid.isPresent()) {
        return byUuid.get();
      }
    }
    if (dto.name() != null && !dto.name().isBlank()) {
      return materialRepository.findByNameIgnoreCase(dto.name().trim()).orElse(null);
    }
    return null;
  }

  /**
   * Reconciles blueprints: resolve by {@code scwiki_uuid} → first {@code scwiki_key}, backfill
   * {@code scwiki_uuid} (guarded), fill-if-null {@code scwiki_key} / {@code output_item} / {@code
   * craft_time_seconds}, stamp the P4K lane, then resolve any still-unresolved existing ingredient
   * lines; or seed a new blueprint (with its ingredient lines) for an unmatched record.
   *
   * @param dtos inbound blueprint records (may be {@code null})
   * @param apply whether to persist mutations and emit audit rows
   * @param seed whether to seed new rows for unmatched records
   * @param runId the audit run id, or {@code null} on a dry run
   * @param now the import timestamp
   * @return the blueprint counts plus the count of ingredient lines newly resolved
   */
  @NotNull
  private BlueprintOutcome reconcileBlueprints(
      @Nullable List<P4kBlueprintDto> dtos,
      boolean apply,
      boolean seed,
      @Nullable UUID runId,
      @NotNull Instant now) {
    CountsAccumulator counts = new CountsAccumulator();
    int ingredientsResolved = 0;
    if (dtos == null) {
      return new BlueprintOutcome(counts.toCounts(), 0);
    }
    for (P4kBlueprintDto dto : dtos) {
      UUID guid = parseUuid(dto.guid());
      Blueprint target = resolveBlueprint(dto, guid);
      if (target == null) {
        if (maybeSeedBlueprint(dto, guid, seed, apply, runId, now)) {
          counts.created++;
        } else {
          counts.unmatched++;
        }
        continue;
      }
      counts.matched++;

      backfillCanonicalUuid(
          counts,
          guid,
          target.getScwikiUuid(),
          () -> blueprintRepository.findByScwikiUuid(guid).isPresent(),
          value -> {
            if (apply) {
              target.setScwikiUuid(value);
            }
          },
          existing ->
              logConflict(apply, runId, "blueprint", existing, guid, dto.key(), "scwiki_uuid"),
          apply,
          runId,
          "blueprint",
          dto.key());

      boolean enriched = false;
      enriched |= fillIfNull(target.getScwikiKey(), dto.key(), v -> target.setScwikiKey(v), apply);
      GameItem produced = resolveProducedItem(dto.producedItemGuid());
      enriched |= fillIfNull(target.getOutputItem(), produced, v -> target.setOutputItem(v), apply);
      enriched |=
          fillIfNull(
              target.getCraftTimeSeconds(),
              dto.craftTimeSeconds(),
              v -> target.setCraftTimeSeconds(v),
              apply);
      if (enriched) {
        counts.enriched++;
      }

      stampP4k(apply, guid, now, v -> target.setP4kUuid(v), t -> target.setP4kSyncedAt(t));

      ingredientsResolved += resolveExistingIngredients(target, apply);
    }
    return new BlueprintOutcome(counts.toCounts(), ingredientsResolved);
  }

  /**
   * Resolves an inbound blueprint to a local row through the chain {@code scwiki_uuid} → first
   * {@code scwiki_key}.
   *
   * @param dto the inbound blueprint
   * @param guid the parsed GUID, or {@code null}
   * @return the matched blueprint, or {@code null}
   */
  @Nullable
  private Blueprint resolveBlueprint(@NotNull P4kBlueprintDto dto, @Nullable UUID guid) {
    if (guid != null) {
      Optional<Blueprint> byUuid = blueprintRepository.findByScwikiUuid(guid);
      if (byUuid.isPresent()) {
        return byUuid.get();
      }
    }
    if (dto.key() != null && !dto.key().isBlank()) {
      return blueprintRepository
          .findFirstByScwikiKeyOrderByScwikiUuidAsc(dto.key().trim())
          .orElse(null);
    }
    return null;
  }

  /**
   * Resolves the produced item of a blueprint by its {@code __ref} GUID against {@code
   * game_item.external_uuid}.
   *
   * @param producedItemGuid the produced item GUID string, or {@code null}
   * @return the produced {@link GameItem}, or {@code null} if unparseable / not found
   */
  @Nullable
  private GameItem resolveProducedItem(@Nullable String producedItemGuid) {
    UUID guid = parseUuid(producedItemGuid);
    if (guid == null) {
      return null;
    }
    return gameItemRepository.findByExternalUuid(guid).orElse(null);
  }

  /**
   * Resolves still-unresolved existing ingredient lines of a blueprint by their stored Wiki UUIDs:
   * a RESOURCE line with a null {@code material} and a non-null {@code wiki_resource_uuid} is
   * linked to {@code material.findByScwikiUuid(...)}; an ITEM line with a null {@code game_item}
   * and a non-null {@code wiki_item_uuid} is linked to {@code game_item.findByExternalUuid(...)}.
   * Only counts and (in apply mode) sets a line when the lookup actually finds a row.
   *
   * @param blueprint the matched blueprint whose ingredient lines to resolve
   * @param apply whether to persist the FK link (dry run only counts)
   * @return the number of ingredient lines newly resolvable / resolved
   */
  private int resolveExistingIngredients(@NotNull Blueprint blueprint, boolean apply) {
    int resolved = 0;
    for (BlueprintIngredient ingredient : blueprint.getIngredients()) {
      if (ingredient.getKind() == BlueprintIngredientKind.RESOURCE
          && ingredient.getMaterial() == null
          && ingredient.getWikiResourceUuid() != null) {
        Material material =
            materialRepository.findByScwikiUuid(ingredient.getWikiResourceUuid()).orElse(null);
        if (material != null) {
          if (apply) {
            ingredient.setMaterial(material);
          }
          resolved++;
        }
      } else if (ingredient.getKind() == BlueprintIngredientKind.ITEM
          && ingredient.getGameItem() == null
          && ingredient.getWikiItemUuid() != null) {
        GameItem item =
            gameItemRepository.findByExternalUuid(ingredient.getWikiItemUuid()).orElse(null);
        if (item != null) {
          if (apply) {
            ingredient.setGameItem(item);
          }
          resolved++;
        }
      }
    }
    return resolved;
  }

  // ───────────────────────────────────────────────────────────────── seeding ──

  /**
   * Seeds a brand-new {@code game_item} for an unmatched item record when seeding is on and the
   * record looks like real player content. Requires a parseable GUID (the cross-source join key), a
   * resolved display name and a non-dev identifier; guards the {@code external_uuid} UNIQUE column.
   * In dry-run mode it performs every check but does not insert.
   *
   * @return {@code true} if a row was inserted (apply) or would be (dry run); {@code false} if the
   *     record was filtered out
   */
  private boolean maybeSeedItem(
      @NotNull P4kItemDto dto,
      @Nullable UUID guid,
      @NotNull Map<String, Manufacturer> manufacturerByGuid,
      boolean seed,
      boolean apply,
      @Nullable UUID runId,
      @NotNull Instant now) {
    if (!seed
        || guid == null
        || !isRealName(dto.name())
        || looksLikeDevAsset(dto.className(), dto.path())
        || gameItemRepository.findByExternalUuid(guid).isPresent()) {
      return false;
    }
    if (apply) {
      GameItem item = new GameItem();
      item.setName(dto.name().trim());
      item.setExternalUuid(guid);
      item.setClassName(StringNormalization.blankToNull(dto.className()));
      item.setManufacturer(manufacturerByGuid.get(dto.manufacturerGuid()));
      item.setMass(dto.mass());
      item.setDescriptionEn(StringNormalization.blankToNull(dto.desc()));
      item.setDescriptionDe(StringNormalization.blankToNull(dto.descDe()));
      item.setKind(GameItemKind.GENERIC);
      item.setSourceSystems(GameItemSourceSystem.P4K);
      item.setP4kUuid(guid);
      item.setP4kSyncedAt(now);
      gameItemRepository.save(item);
      recordSeed(runId, "game_item", guid, item.getName());
    }
    return true;
  }

  /**
   * Seeds a brand-new {@code ship_type} for an unmatched ship record. Requires a parseable GUID and
   * a resolved, non-dev name; guards both the {@code name} and {@code external_uuid} UNIQUE
   * columns.
   *
   * @return whether a row was (or would be) inserted
   */
  private boolean maybeSeedShip(
      @NotNull P4kShipDto dto,
      @Nullable UUID guid,
      @NotNull Map<String, Manufacturer> manufacturerByGuid,
      boolean seed,
      boolean apply,
      @Nullable UUID runId,
      @NotNull Instant now) {
    if (!seed
        || guid == null
        || !isRealName(dto.name())
        || looksLikeDevAsset(dto.className(), dto.path())) {
      return false;
    }
    String name = dto.name().trim();
    if (shipTypeRepository.findByExternalUuid(guid).isPresent()
        || shipTypeRepository.findByNameIgnoreCase(name).isPresent()) {
      return false;
    }
    if (apply) {
      ShipType ship = new ShipType();
      ship.setName(name);
      ship.setExternalUuid(guid);
      ship.setClassName(StringNormalization.blankToNull(dto.className()));
      ship.setManufacturer(manufacturerByGuid.get(dto.manufacturerGuid()));
      ship.setDescriptionEn(StringNormalization.blankToNull(dto.desc()));
      ship.setDescriptionDe(StringNormalization.blankToNull(dto.descDe()));
      ship.setSourceSystems(GameItemSourceSystem.P4K);
      ship.setP4kUuid(guid);
      ship.setP4kSyncedAt(now);
      shipTypeRepository.save(ship);
      recordSeed(runId, "ship_type", guid, name);
    }
    return true;
  }

  /**
   * Seeds a brand-new {@code manufacturer} for an unmatched record and registers it in {@code
   * byGuid} so items / ships in the same run can link to it — including on a dry-run preview (the
   * entity is built and indexed but only persisted on apply) so the preview's item/ship enrichment
   * counts match the apply. Requires a parseable GUID, a resolved name and a non-blank code ({@code
   * name} is NOT NULL + UNIQUE, {@code abbreviation} is NOT NULL but no longer UNIQUE since V158);
   * guards all three keys so it never re-seeds a manufacturer the run already knows.
   *
   * @return whether a row was (or would be) inserted
   */
  private boolean maybeSeedManufacturer(
      @NotNull P4kManufacturerDto dto,
      @Nullable UUID guid,
      @NotNull Map<String, Manufacturer> byGuid,
      boolean seed,
      boolean apply,
      @Nullable UUID runId,
      @NotNull Instant now) {
    if (!seed
        || guid == null
        || !isRealName(dto.name())
        || dto.code() == null
        || dto.code().isBlank()
        || looksLikeDevAsset(dto.code())) {
      return false;
    }
    String name = dto.name().trim();
    String code = dto.code().trim();
    if (manufacturerRepository.findByScwikiUuid(guid).isPresent()
        || manufacturerRepository.findByNameIgnoreCase(name).isPresent()
        || manufacturerRepository
            .findFirstByAbbreviationIgnoreCaseOrderByCreatedAtAsc(code)
            .isPresent()) {
      return false;
    }
    Manufacturer manufacturer = new Manufacturer();
    manufacturer.setName(name);
    manufacturer.setAbbreviation(code);
    manufacturer.setScwikiUuid(guid);
    manufacturer.setScwikiCode(code);
    manufacturer.setDescription(StringNormalization.blankToNull(dto.desc()));
    manufacturer.setP4kUuid(guid);
    manufacturer.setP4kSyncedAt(now);
    // Indexed for in-run linking on preview and apply (count parity); persisted only on apply.
    byGuid.put(dto.guid(), manufacturer);
    if (apply) {
      manufacturerRepository.save(manufacturer);
      recordSeed(runId, "manufacturer", guid, name);
    }
    return true;
  }

  /**
   * Seeds a brand-new {@code material} for an unmatched commodity record. Requires a parseable GUID
   * and a resolved, non-dev name; guards the {@code name} and {@code scwiki_uuid} UNIQUE columns.
   * The row is inserted {@code is_visible = false} and {@code type = NO_REFINE} so it stays out of
   * trading flows until an admin reviews it (mirrors the SC-Wiki commodity sync).
   *
   * @return whether a row was (or would be) inserted
   */
  private boolean maybeSeedCommodity(
      @NotNull P4kCommodityDto dto,
      @Nullable UUID guid,
      boolean seed,
      boolean apply,
      @Nullable UUID runId,
      @NotNull Instant now) {
    if (!seed
        || guid == null
        || !isRealName(dto.name())
        || looksLikeDevAsset(dto.className(), dto.path())) {
      return false;
    }
    String name = dto.name().trim();
    if (materialRepository.findByScwikiUuid(guid).isPresent()
        || materialRepository.findByNameIgnoreCase(name).isPresent()) {
      return false;
    }
    if (apply) {
      Material material = new Material();
      material.setName(name);
      material.setType(MaterialType.NO_REFINE);
      material.setDescription(StringNormalization.blankToNull(dto.desc()));
      material.setScwikiUuid(guid);
      material.setIsVisible(false);
      material.setSourceSystems(MaterialSourceSystem.P4K);
      material.setP4kUuid(guid);
      material.setP4kSyncedAt(now);
      materialRepository.save(material);
      recordSeed(runId, "material", guid, name);
    }
    return true;
  }

  /**
   * Seeds a brand-new {@code blueprint} (with its ingredient lines) for an unmatched record.
   * Requires a parseable GUID (the NOT NULL + UNIQUE {@code scwiki_uuid}) and a non-dev {@code
   * key}; guards {@code scwiki_uuid}. Ingredient lines are built from the catalog payload — a
   * RESOURCE line carries {@code quantity_scu} and resolves its {@code material} by resource GUID,
   * an ITEM line carries {@code quantity_units} and resolves its {@code game_item} by item GUID;
   * the raw Wiki UUIDs are always stored so a later pass can resolve a then-unknown FK.
   *
   * @return whether a row was (or would be) inserted
   */
  private boolean maybeSeedBlueprint(
      @NotNull P4kBlueprintDto dto,
      @Nullable UUID guid,
      boolean seed,
      boolean apply,
      @Nullable UUID runId,
      @NotNull Instant now) {
    if (!seed
        || guid == null
        || dto.key() == null
        || dto.key().isBlank()
        || looksLikeDevAsset(dto.key(), dto.path())
        || blueprintRepository.findByScwikiUuid(guid).isPresent()) {
      return false;
    }
    if (apply) {
      Blueprint blueprint = new Blueprint();
      blueprint.setScwikiUuid(guid);
      blueprint.setScwikiKey(dto.key().trim());
      GameItem produced = resolveProducedItem(dto.producedItemGuid());
      blueprint.setOutputItem(produced);
      if (produced != null) {
        // #327: apply the same guarded CIG-mislabel correction the SC Wiki sync uses, for
        // consistency on seeded rows (a no-op unless the produced name matches a known wrong name).
        blueprint.setOutputName(outputNameOverrides.correct(dto.key(), produced.getName()));
      }
      blueprint.setCraftTimeSeconds(dto.craftTimeSeconds());
      blueprint.setP4kUuid(guid);
      blueprint.setP4kSyncedAt(now);
      if (dto.ingredients() != null) {
        int orderIndex = 0;
        for (P4kIngredientDto ing : dto.ingredients()) {
          BlueprintIngredient line = buildIngredient(ing, orderIndex);
          if (line != null) {
            blueprint.addIngredient(line);
            orderIndex++;
          }
        }
      }
      blueprintRepository.save(blueprint);
      recordSeed(runId, "blueprint", guid, dto.key());
    }
    return true;
  }

  /**
   * Builds one {@link BlueprintIngredient} line from a catalog ingredient, honouring the {@code
   * blueprint_ingredient} CHECK constraints (RESOURCE ⇒ only {@code quantity_scu} + {@code
   * material}; ITEM ⇒ only {@code quantity_units} + {@code game_item}). Returns {@code null} when
   * the line carries neither a resource nor an item GUID (nothing to record).
   *
   * @param ing the catalog ingredient
   * @param orderIndex the line's position in the recipe
   * @return the built (unattached) line, or {@code null} to skip
   */
  @Nullable
  private BlueprintIngredient buildIngredient(@NotNull P4kIngredientDto ing, int orderIndex) {
    BlueprintIngredient line = new BlueprintIngredient();
    line.setOrderIndex(orderIndex);
    line.setWikiNameSnapshot(StringNormalization.blankToNull(ing.slot()));
    line.setMinQuality(ing.minQuality());
    UUID resourceGuid = parseUuid(ing.resourceGuid());
    if (resourceGuid != null) {
      line.setKind(BlueprintIngredientKind.RESOURCE);
      line.setWikiResourceUuid(resourceGuid);
      line.setMaterial(materialRepository.findByScwikiUuid(resourceGuid).orElse(null));
      line.setQuantityScu(ing.quantityScu());
      return line;
    }
    UUID itemGuid = parseUuid(ing.itemGuid());
    if (itemGuid != null) {
      line.setKind(BlueprintIngredientKind.ITEM);
      line.setWikiItemUuid(itemGuid);
      line.setGameItem(gameItemRepository.findByExternalUuid(itemGuid).orElse(null));
      line.setQuantityUnits(ing.quantityUnits());
      return line;
    }
    return null;
  }

  /**
   * Logs a {@link SyncEventType#CREATED_FROM_P4K} event for a seeded row. No-op when no run is
   * active (dry run).
   *
   * @param runId the audit run id, or {@code null}
   * @param aggregate the sync-report aggregate label
   * @param guid the seeded row's P4K GUID
   * @param name the seeded row's name / key for the audit detail
   */
  private void recordSeed(
      @Nullable UUID runId, @NotNull String aggregate, @NotNull UUID guid, @Nullable String name) {
    if (runId == null) {
      return;
    }
    syncReportService.logP4kEvent(
        runId,
        SyncEventType.CREATED_FROM_P4K,
        aggregate,
        guid,
        name,
        "Seeded new row from the P4K catalog (no UEX / SC-Wiki match).");
  }

  /**
   * A real, player-facing display name: present, not an unresolved {@code @LOC} key, and not a
   * placeholder token. Dev / test / template records in the DCB carry no localized name, so the
   * export leaves their name null — this is the primary filter that keeps the seed from inserting
   * the engine's thousands of internal records.
   *
   * @param name the candidate display name
   * @return {@code true} when the name looks like real content
   */
  private boolean isRealName(@Nullable String name) {
    if (name == null || name.isBlank()) {
      return false;
    }
    String trimmed = name.trim();
    if (trimmed.startsWith("@")) {
      return false;
    }
    String lower = trimmed.toLowerCase(Locale.ROOT);
    return !lower.equals("uninitialized")
        && !lower.equals("empty")
        && !lower.startsWith("loc_placeholder")
        && !lower.startsWith("loc_empty");
  }

  /**
   * True when any of the given engine identifiers (class name, record path, blueprint key,
   * manufacturer code) contains a {@link #DEV_TOKENS dev/test/template token} as a delimited word.
   * Applied only to identifiers, never to the localized display name.
   *
   * @param identifiers the engine identifiers to scan ({@code null} entries ignored)
   * @return {@code true} when the record smells like a non-shippable dev asset
   */
  private boolean looksLikeDevAsset(@Nullable String... identifiers) {
    if (identifiers == null) {
      return false;
    }
    for (String identifier : identifiers) {
      if (identifier == null || identifier.isBlank()) {
        continue;
      }
      String lower = identifier.toLowerCase(Locale.ROOT);
      for (String token : DEV_TOKENS) {
        if (containsToken(lower, token)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Whether {@code haystack} contains {@code token} as a delimited word — bounded by a
   * non-alphanumeric character (or the string edge) on both sides. This matches {@code "test_gun"},
   * {@code "gun/test"} and a bare {@code "test"} but not {@code "latest"} or {@code "contest"}.
   *
   * @param haystack the lower-cased identifier to scan
   * @param token the lower-cased dev token to find
   * @return {@code true} on a delimited-word match
   */
  private static boolean containsToken(@NotNull String haystack, @NotNull String token) {
    int index = haystack.indexOf(token);
    while (index >= 0) {
      char before = index == 0 ? '/' : haystack.charAt(index - 1);
      int end = index + token.length();
      char after = end >= haystack.length() ? '/' : haystack.charAt(end);
      if (!Character.isLetterOrDigit(before) && !Character.isLetterOrDigit(after)) {
        return true;
      }
      index = haystack.indexOf(token, index + 1);
    }
    return false;
  }

  // ──────────────────────────────────────────────────────────── shared steps ──

  /**
   * Resolves an inbound row to a single local entity through the standard chain: canonical UUID
   * first, then a case-insensitive {@code class_name} match used only when it returns exactly one
   * row, then a case-insensitive {@code name} match used only when it returns exactly one row.
   *
   * @param guid the parsed canonical GUID, or {@code null}
   * @param byUuid the UUID finder ({@code external_uuid})
   * @param className the inbound class name, or {@code null}
   * @param byClassName the case-insensitive class-name finder (returns all matches)
   * @param name the inbound display name, or {@code null}
   * @param byName the case-insensitive name finder (returns all matches)
   * @param <T> the entity type
   * @return the single resolved entity, or {@code null} when nothing matched or a fallback was
   *     ambiguous
   */
  @Nullable
  private <T> T resolveSingle(
      @Nullable UUID guid,
      @NotNull Function<UUID, Optional<T>> byUuid,
      @Nullable String className,
      @NotNull Function<String, List<T>> byClassName,
      @Nullable String name,
      @NotNull Function<String, List<T>> byName) {
    if (guid != null) {
      Optional<T> hit = byUuid.apply(guid);
      if (hit.isPresent()) {
        return hit.get();
      }
    }
    T byClass = single(className, byClassName);
    if (byClass != null) {
      return byClass;
    }
    return single(name, byName);
  }

  /**
   * Applies a case-insensitive finder and returns the matched entity only when it is unambiguous
   * (exactly one row); a blank key, no match, or more than one match all yield {@code null}.
   *
   * @param key the lookup value, or {@code null} / blank to skip
   * @param finder the case-insensitive finder returning all matches
   * @param <T> the entity type
   * @return the single match, or {@code null}
   */
  @Nullable
  private <T> T single(@Nullable String key, @NotNull Function<String, List<T>> finder) {
    if (key == null || key.isBlank()) {
      return null;
    }
    List<T> matches = finder.apply(key.trim());
    return matches.size() == 1 ? matches.get(0) : null;
  }

  /**
   * Backfills a {@code game_item}'s {@code external_uuid} from the P4K GUID under the keep-both
   * policy: fill only when currently null and unclaimed by another row; on a non-null differing
   * value, log a conflict and leave it.
   *
   * @param counts the type's counter accumulator (mutated)
   * @param target the matched game item
   * @param guid the parsed P4K GUID, or {@code null}
   * @param name the item name for the audit detail
   * @param apply whether to persist the backfill and emit audit rows
   * @param runId the audit run id, or {@code null}
   */
  private void backfillExternalUuidGameItem(
      @NotNull CountsAccumulator counts,
      @NotNull GameItem target,
      @Nullable UUID guid,
      @Nullable String name,
      boolean apply,
      @Nullable UUID runId) {
    backfillCanonicalUuid(
        counts,
        guid,
        target.getExternalUuid(),
        () -> gameItemRepository.findByExternalUuid(guid).isPresent(),
        value -> {
          if (apply) {
            target.setExternalUuid(value);
          }
        },
        existing -> logConflict(apply, runId, "game_item", existing, guid, name, "external_uuid"),
        apply,
        runId,
        "game_item",
        name);
  }

  /**
   * Backfills a {@code ship_type}'s {@code external_uuid} from the P4K GUID under the keep-both
   * policy (see {@link #backfillExternalUuidGameItem}).
   *
   * @param counts the type's counter accumulator (mutated)
   * @param target the matched ship
   * @param guid the parsed P4K GUID, or {@code null}
   * @param name the ship name for the audit detail
   * @param apply whether to persist the backfill and emit audit rows
   * @param runId the audit run id, or {@code null}
   */
  private void backfillExternalUuidShipType(
      @NotNull CountsAccumulator counts,
      @NotNull ShipType target,
      @Nullable UUID guid,
      @Nullable String name,
      boolean apply,
      @Nullable UUID runId) {
    backfillCanonicalUuid(
        counts,
        guid,
        target.getExternalUuid(),
        () -> shipTypeRepository.findByExternalUuid(guid).isPresent(),
        value -> {
          if (apply) {
            target.setExternalUuid(value);
          }
        },
        existing -> logConflict(apply, runId, "ship_type", existing, guid, name, "external_uuid"),
        apply,
        runId,
        "ship_type",
        name);
  }

  /**
   * Generic canonical-UUID backfill implementing the keep-both policy for one row. Increments
   * {@code uuidBackfilled} (and logs {@link SyncEventType#LINKED_VIA_NAME} in apply mode) when
   * {@code existingUuid} is null, the GUID is present and no other row already holds it — this only
   * happens when the row was reached by the name/slug fallback, so the link was established via the
   * name, not the UUID. Increments {@code uuidConflicts} and invokes {@code onConflict} when {@code
   * existingUuid} is non-null and differs. A null GUID or an already-matching GUID is a no-op.
   *
   * @param counts the type's counter accumulator (mutated)
   * @param guid the parsed P4K GUID, or {@code null}
   * @param existingUuid the row's current canonical UUID, or {@code null}
   * @param alreadyClaimed predicate that returns {@code true} when another row already holds the
   *     GUID (the UNIQUE-collision guard); only evaluated when a backfill is otherwise possible
   * @param setter sets the canonical UUID on the row (a no-op in dry-run mode by the caller's
   *     lambda)
   * @param onConflict invoked with the existing UUID when a non-null differing value is found
   * @param apply whether to emit the backfill audit row
   * @param runId the audit run id, or {@code null}
   * @param aggregate the sync-report aggregate label
   * @param name the row name for the audit detail
   */
  private void backfillCanonicalUuid(
      @NotNull CountsAccumulator counts,
      @Nullable UUID guid,
      @Nullable UUID existingUuid,
      @NotNull java.util.function.BooleanSupplier alreadyClaimed,
      @NotNull java.util.function.Consumer<UUID> setter,
      @NotNull java.util.function.Consumer<UUID> onConflict,
      boolean apply,
      @Nullable UUID runId,
      @NotNull String aggregate,
      @Nullable String name) {
    if (guid == null) {
      return;
    }
    if (existingUuid == null) {
      if (alreadyClaimed.getAsBoolean()) {
        // Another row already owns this GUID — skip the backfill to avoid a UNIQUE collision.
        log.debug(
            "P4K import: {} GUID {} already held by another row; skipping backfill of '{}'.",
            aggregate,
            guid,
            name);
        return;
      }
      setter.accept(guid);
      counts.uuidBackfilled++;
      if (apply && runId != null) {
        syncReportService.logP4kEvent(
            runId,
            SyncEventType.LINKED_VIA_NAME,
            aggregate,
            guid,
            name,
            "Linked by name/slug fallback and backfilled the canonical UUID from the P4K catalog.");
      }
      return;
    }
    if (!existingUuid.equals(guid)) {
      counts.uuidConflicts++;
      onConflict.accept(existingUuid);
    }
  }

  /**
   * Logs a {@link SyncEventType#BACKFILL_AMBIGUOUS} event for a UUID conflict (existing canonical
   * UUID differs from the P4K GUID, kept rather than overwritten). No-op outside apply mode.
   *
   * @param apply whether audit rows are being written
   * @param runId the audit run id, or {@code null}
   * @param aggregate the sync-report aggregate label
   * @param existingUuid the row's current canonical UUID (kept)
   * @param p4kGuid the conflicting P4K GUID (stored only in {@code p4k_uuid})
   * @param name the row name for the audit detail
   * @param column the canonical column name for the audit detail
   */
  private void logConflict(
      boolean apply,
      @Nullable UUID runId,
      @NotNull String aggregate,
      @NotNull UUID existingUuid,
      @NotNull UUID p4kGuid,
      @Nullable String name,
      @NotNull String column) {
    if (!apply || runId == null) {
      return;
    }
    syncReportService.logP4kEvent(
        runId,
        SyncEventType.BACKFILL_AMBIGUOUS,
        aggregate,
        p4kGuid,
        name,
        "Existing "
            + column
            + " "
            + existingUuid
            + " differs from P4K GUID "
            + p4kGuid
            + "; kept existing, stored P4K GUID in p4k_uuid.");
  }

  /**
   * Stamps the P4K provenance lane on a matched row: always sets {@code p4k_synced_at = now} and
   * records the observed GUID in {@code p4k_uuid} (when parseable). No-op outside apply mode.
   *
   * @param apply whether to persist
   * @param guid the parsed P4K GUID, or {@code null} (then {@code p4k_uuid} is left unchanged)
   * @param now the import timestamp
   * @param uuidSetter sets {@code p4k_uuid} on the row
   * @param syncedAtSetter sets {@code p4k_synced_at} on the row
   */
  private void stampP4k(
      boolean apply,
      @Nullable UUID guid,
      @NotNull Instant now,
      @NotNull java.util.function.Consumer<UUID> uuidSetter,
      @NotNull java.util.function.Consumer<Instant> syncedAtSetter) {
    if (!apply) {
      return;
    }
    if (guid != null) {
      uuidSetter.accept(guid);
    }
    syncedAtSetter.accept(now);
  }

  /**
   * Fill-if-null helper: when {@code current} is null and {@code incoming} is non-null, reports
   * that a write would happen and (in apply mode) performs it via {@code setter}. An existing
   * non-null value is never overwritten.
   *
   * @param current the row's current value
   * @param incoming the candidate value from the catalog
   * @param setter sets the value on the row
   * @param apply whether to persist the write
   * @param <V> the value type
   * @return {@code true} if a write happened (apply) or would happen (dry run)
   */
  private <V> boolean fillIfNull(
      @Nullable V current,
      @Nullable V incoming,
      @NotNull java.util.function.Consumer<V> setter,
      boolean apply) {
    if (current != null || incoming == null) {
      return false;
    }
    if (incoming instanceof String s && s.isBlank()) {
      return false;
    }
    if (apply) {
      setter.accept(incoming);
    }
    return true;
  }

  // ───────────────────────────────────────────────────────────────── parsing ──

  /**
   * Binds the uploaded catalog bytes straight to a {@link P4kCatalogDto} in one pass, with no
   * intermediate {@code JsonNode} tree, so a large catalog costs roughly the bound object rather
   * than a full tree plus the DTO. Empty bytes, malformed JSON, or a non-object root (e.g. a JSON
   * array) surface as a {@link BadRequestException} (HTTP 400), not a 500.
   *
   * @param bytes the uploaded catalog JSON bytes
   * @return the parsed catalog (its arrays may be {@code null})
   * @throws BadRequestException if the catalog is empty or not valid P4K catalog JSON
   */
  @NotNull
  private P4kCatalogDto parse(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new BadRequestException("The uploaded file is empty.");
    }
    P4kCatalogDto catalog;
    try {
      catalog = objectMapper.readValue(bytes, P4kCatalogDto.class);
    } catch (JacksonException e) {
      log.warn("P4K import: failed to read catalog JSON — {}", e.getMessage());
      throw new BadRequestException(
          "The uploaded file is not valid P4K catalog JSON (manufacturers / items / ships /"
              + " commodities / blueprints).");
    }
    if (catalog == null) {
      throw new BadRequestException("The uploaded file is empty or not a P4K catalog object.");
    }
    return catalog;
  }

  /**
   * Leniently parses a DataForge {@code __ref} GUID string into a {@link UUID}. A null, blank, or
   * malformed value yields {@code null} so one bad GUID never aborts the whole import (the record
   * is reported as unmatched instead).
   *
   * @param guid the GUID string, or {@code null}
   * @return the parsed UUID, or {@code null}
   */
  @Nullable
  private UUID parseUuid(@Nullable String guid) {
    if (guid == null || guid.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(guid.trim());
    } catch (IllegalArgumentException e) {
      log.debug("P4K import: ignoring unparseable GUID '{}'", guid);
      return null;
    }
  }

  /**
   * Renders the per-run {@link SyncEventType#SYNC_RUN_SUMMARY} detail string: a compact per-type
   * tally plus the cross-cutting {@code ingredientsResolved} and the seeding mode.
   *
   * @param result the computed result
   * @return the human-readable summary line
   */
  @NotNull
  private String summaryDetail(@NotNull P4kImportResultDto result) {
    return "P4K catalog import — manufacturers"
        + fmt(result.manufacturers())
        + " items"
        + fmt(result.items())
        + " ships"
        + fmt(result.ships())
        + " commodities"
        + fmt(result.commodities())
        + " blueprints"
        + fmt(result.blueprints())
        + "; ingredientsResolved="
        + result.ingredientsResolved()
        + "; seeding="
        + (result.seedingEnabled() ? "on" : "off");
  }

  /**
   * Formats one {@link P4kImportResultDto.Counts} block for the run-summary detail string.
   *
   * @param c the counts
   * @return a compact {@code [m=…,bf=…,cf=…,en=…,cr=…,un=…]} fragment
   */
  @NotNull
  private String fmt(@NotNull P4kImportResultDto.Counts c) {
    return "[m="
        + c.matched()
        + ",bf="
        + c.uuidBackfilled()
        + ",cf="
        + c.uuidConflicts()
        + ",en="
        + c.enriched()
        + ",cr="
        + c.created()
        + ",un="
        + c.unmatched()
        + "]";
  }

  // ───────────────────────────────────────────────────────────── value types ──

  /**
   * Mutable per-type counter accumulator used while scanning a type's records, converted to the
   * immutable {@link P4kImportResultDto.Counts} record at the end. Package-private fields keep the
   * inner accumulation terse; only {@link #toCounts()} is exposed to the result.
   */
  private static final class CountsAccumulator {
    private int matched;
    private int uuidBackfilled;
    private int uuidConflicts;
    private int enriched;
    private int created;
    private int unmatched;

    /**
     * Snapshots the accumulated counters into the immutable result record.
     *
     * @return the per-type counts
     */
    @NotNull
    private P4kImportResultDto.Counts toCounts() {
      return new P4kImportResultDto.Counts(
          matched, uuidBackfilled, uuidConflicts, enriched, created, unmatched);
    }
  }

  /**
   * Blueprint reconciliation outcome: the per-type counts plus the cross-cutting count of
   * ingredient lines whose FK this import resolved (which does not belong to any single {@link
   * P4kImportResultDto.Counts} field).
   *
   * @param counts the blueprint counts
   * @param ingredientsResolved the number of ingredient lines newly resolved
   */
  private record BlueprintOutcome(
      @NotNull P4kImportResultDto.Counts counts, int ingredientsResolved) {}
}
