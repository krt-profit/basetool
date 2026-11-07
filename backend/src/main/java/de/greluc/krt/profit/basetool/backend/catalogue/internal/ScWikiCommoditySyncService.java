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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialNameCanonicalizer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiCommodityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiResponseDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Merges the SC Wiki commodity feed ({@code /api/commodities}) into the {@code material} table,
 * dropping non-tradeable junk.
 *
 * <p>Writes only the Wiki-owned columns on a matched UEX row and flips {@code source_systems} to
 * {@code BOTH}; an unmatched commodity becomes an invisible {@code WIKI_ONLY} row, and an ambiguous
 * canonical-name match is skipped with a {@link SyncEventType#MULTI_MATCH_AMBIGUOUS} event. Gated
 * behind {@code krt.scwiki.commodity-sync-enabled} (default {@code false}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScWikiCommoditySyncService {

  /**
   * Atmosphere and environment-system entries in the Wiki commodity pool that are not tradeable and
   * are dropped at sync time.
   */
  private static final Set<String> HARDCODED_ATMOSPHERE_SET =
      Set.of("Cooler", "Heat", "Oxygen", "Life Support", "EVA Fuel", "Mixed Mining");

  /**
   * Wiki commodity-pool entries that verification (§4.3) showed are almost certainly game items,
   * not commodities — but the filter cannot tell automatically. Imported {@code is_visible=false}
   * and flagged {@link SyncEventType#LOOKS_LIKE_ITEM} so an admin decides, rather than hard-junked.
   */
  private static final Set<String> LOOKS_LIKE_ITEM_SET =
      Set.of(
          "Ace Interceptor Helmet",
          "MedGel",
          "HLX99 Hyperprocessors",
          "mobyGlass Personal Computers",
          "RS1 Odysey Spacesuits");

  private final ScWikiClient scWikiClient;
  private final ScWikiProperties properties;
  private final MaterialRepository materialRepository;
  private final MaterialExternalAliasService aliasService;
  private final SyncReportService syncReportService;
  private final SyncChunkWriter chunkWriter;

  /**
   * Imports one Wiki commodity census in isolated database transactions.
   *
   * @return successfully applied commodity rows
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public int syncCommodities() {
    if (!Boolean.TRUE.equals(properties.commoditySyncEnabled())) {
      return 0;
    }
    try (var _ = scWikiClient.openSyncRun()) {
      return syncCensus();
    }
  }

  private int syncCensus() {
    var fetched =
        scWikiClient.fetchAllPagesResult(
            properties.commoditiesEndpoint(),
            new ParameterizedTypeReference<ScWikiResponseDto<ScWikiCommodityDto>>() {},
            "commodities");

    List<ScWikiCommodityDto> rows = fetched.data();
    if (rows.isEmpty()) {
      syncReportService.recordSweep(false);
      return 0;
    }
    UUID runId = syncReportService.beginRun();
    Instant now = Instant.now();
    Set<UUID> census = new HashSet<>();
    rows.stream()
        .filter(dto -> dto != null && dto.uuid() != null)
        .forEach(dto -> census.add(dto.uuid()));
    Map<String, Long> sourceCounts =
        rows.stream()
            .filter(dto -> dto != null)
            .map(dto -> canonicalName(dto.name()))
            .filter(name -> name != null && !name.isBlank())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    Map<String, Long> exactNames =
        rows.stream()
            .filter(dto -> dto != null)
            .map(ScWikiCommodityDto::name)
            .filter(StringUtils::hasText)
            .map(name -> name.strip().toLowerCase(Locale.ROOT))
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    Map<String, List<UUID>> candidates = chunkWriter.inNewTransaction(this::buildCanonicalIndex);
    var outcome =
        chunkWriter.write(
            rows,
            SyncChunkWriter.DEFAULT_CHUNK_SIZE,
            chunk ->
                chunk.stream()
                    .map(dto -> upsert(dto, candidates, sourceCounts, exactNames, runId, now))
                    .filter(uuid -> uuid != null)
                    .toList(),
            "Wiki commodity",
            dto -> dto == null ? "missing payload" : String.valueOf(dto.uuid()));
    long eligible =
        rows.stream()
            .filter(dto -> dto != null && dto.uuid() != null)
            .filter(dto -> !isCommodityHardJunk(dto))
            .count();
    boolean complete =
        fetched.complete()
            && census.size() == rows.size()
            && outcome.failedRows() == 0
            && outcome.results().size() == eligible;
    if (complete && !outcome.results().isEmpty()) {
      int marked =
          chunkWriter.inNewTransaction(() -> materialRepository.markScwikiDeleted(census, now));
      syncReportService.recordApplied(marked);
      syncReportService.recordSweep(true);
    } else {
      syncReportService.recordIncomplete();
      syncReportService.recordSweep(false);
    }
    syncReportService.pruneRuns(SyncSourceSystem.SCWIKI);
    return outcome.results().size();
  }

  @Nullable
  private UUID upsert(
      ScWikiCommodityDto dto,
      Map<String, List<UUID>> candidates,
      Map<String, Long> sourceCounts,
      Map<String, Long> exactNames,
      UUID runId,
      Instant now) {
    if (dto == null || dto.uuid() == null || isCommodityHardJunk(dto)) {
      syncReportService.logCommodityEvent(
          runId,
          SyncEventType.SKIP_JUNK,
          dto == null ? null : dto.uuid(),
          dto == null ? null : dto.name(),
          "Missing identity or excluded commodity name.");
      return null;
    }
    ResolveResult resolution = resolve(dto, candidates, sourceCounts, exactNames, runId);
    if (resolution.skip()) {
      syncReportService.recordIncomplete();
      return null;
    }
    Material material = resolution.material();
    if (material == null) {
      material = createWikiOnlyMaterial(dto, runId);
    }
    applyWikiFields(material, dto, now, runId);
    if (material.getSourceSystems() == MaterialSourceSystem.UEX_ONLY) {
      material.setSourceSystems(MaterialSourceSystem.BOTH);
    }
    materialRepository.save(material);
    return dto.uuid();
  }

  /**
   * Resolves a Wiki commodity by {@code scwiki_uuid}, alias, exact name, then canonical name.
   *
   * <p>A canonical multi-match yields a skip rather than a new row, so a later admin alias can
   * still take effect; it is reported as {@link SyncEventType#MULTI_MATCH_AMBIGUOUS}.
   *
   * @param dto the Wiki commodity row
   * @param canonicalIndex canonical-name index of unmatched materials
   * @param exactNames Wiki rows per normalized exact source name
   * @param sourceCounts Wiki rows per canonical commodity core
   * @param runId current run id for event logging
   * @return a matched material, a "create new" result, or a skip result
   */
  private ResolveResult resolve(
      @NotNull ScWikiCommodityDto dto,
      Map<String, List<UUID>> canonicalIndex,
      Map<String, Long> sourceCounts,
      Map<String, Long> exactNames,
      UUID runId) {
    Optional<Material> byUuid = materialRepository.findByScwikiUuid(dto.uuid());
    if (byUuid.isPresent()) {
      return ResolveResult.matched(byUuid.orElseThrow());
    }

    if (StringUtils.hasText(dto.name())
        && exactNames.getOrDefault(dto.name().strip().toLowerCase(Locale.ROOT), 0L) > 1) {
      syncReportService.logCommodityEvent(
          runId,
          SyncEventType.MULTI_MATCH_AMBIGUOUS,
          dto.uuid(),
          dto.name(),
          "Several Wiki UUIDs share the exact source name.");
      return ResolveResult.skipped();
    }

    Material byAlias =
        aliasService.resolveMaterialByAlias(MaterialExternalAliasSource.SCWIKI, dto.name());
    if (byAlias != null) {
      ResolveResult compatibleAlias = compatible(dto, byAlias, runId);
      if (compatibleAlias.skip()) {
        return compatibleAlias;
      }
      syncReportService.logCommodityEvent(
          runId,
          SyncEventType.LINKED_VIA_ALIAS,
          dto.uuid(),
          dto.name(),
          "Linked to material '" + byAlias.getName() + "' via alias");
      return compatibleAlias;
    }

    if (StringUtils.hasText(dto.name())) {
      Optional<Material> byName = materialRepository.findByName(dto.name());
      if (byName.isPresent()) {
        return compatible(dto, byName.orElseThrow(), runId);
      }
    }

    String canon = canonicalName(dto.name());
    if (canon != null && !canon.isBlank()) {
      if (sourceCounts.getOrDefault(canon, 0L) > 1) {
        syncReportService.logCommodityEvent(
            runId,
            SyncEventType.MULTI_MATCH_AMBIGUOUS,
            dto.uuid(),
            dto.name(),
            "Canonical name is shared by several Wiki commodities.");
        return ResolveResult.skipped();
      }
      List<Material> candidates =
          canonicalIndex.getOrDefault(canon, List.of()).stream()
              .map(materialRepository::findById)
              .flatMap(Optional::stream)
              .toList();
      if (candidates.size() == 1) {
        return compatible(dto, candidates.getFirst(), runId);
      }
      if (candidates.size() > 1) {
        String names =
            candidates.stream().map(Material::getName).reduce((a, b) -> a + ", " + b).orElse("");
        syncReportService.logCommodityEvent(
            runId,
            SyncEventType.MULTI_MATCH_AMBIGUOUS,
            dto.uuid(),
            dto.name(),
            "Canonical name '" + canon + "' matched multiple UEX rows: " + names);
        return ResolveResult.skipped();
      }
    }

    return ResolveResult.createNew();
  }

  private ResolveResult compatible(ScWikiCommodityDto dto, Material material, UUID runId) {
    UUID existing = material.getScwikiUuid();
    if (existing != null && !existing.equals(dto.uuid())) {
      syncReportService.logCommodityEvent(
          runId,
          SyncEventType.SOURCE_IDENTITY_CONFLICT,
          dto.uuid(),
          dto.name(),
          "Existing material is linked to another Wiki UUID.");
      return ResolveResult.skipped();
    }
    return ResolveResult.matched(material);
  }

  /**
   * Builds a {@code canonicalName → materials} index over materials without a Wiki UUID, so the
   * each write reloads the candidate and validates its current Wiki identity.
   *
   * @return canonical-name index of unmatched materials
   */
  @NotNull
  private Map<String, List<UUID>> buildCanonicalIndex() {
    Map<String, List<UUID>> index = new HashMap<>();
    for (Material material : materialRepository.findAll()) {
      if (material.getScwikiUuid() != null) {
        continue;
      }
      String canon = canonicalName(material.getName());
      if (canon == null || canon.isBlank()) {
        continue;
      }
      index.computeIfAbsent(canon, _ -> new ArrayList<>()).add(material.getId());
    }
    return index;
  }

  /**
   * Creates an invisible {@code WIKI_ONLY} material for a Wiki commodity with no UEX counterpart,
   * logging {@link SyncEventType#LOOKS_LIKE_ITEM} or {@link SyncEventType#CREATED_WIKI_ONLY}.
   *
   * @param dto the Wiki commodity row
   * @param runId current run id for event logging
   * @return a new, unsaved {@link Material}
   */
  @NotNull
  private Material createWikiOnlyMaterial(ScWikiCommodityDto dto, UUID runId) {
    Material material = new Material();
    material.setName(dto.name());
    material.setType(MaterialType.NO_REFINE);
    material.setSourceSystems(MaterialSourceSystem.WIKI_ONLY);
    material.setIsVisible(false);
    boolean looksLikeItem = LOOKS_LIKE_ITEM_SET.contains(dto.name());
    syncReportService.logCommodityEvent(
        runId,
        looksLikeItem ? SyncEventType.LOOKS_LIKE_ITEM : SyncEventType.CREATED_WIKI_ONLY,
        dto.uuid(),
        dto.name(),
        looksLikeItem
            ? "Imported invisible — looks like an item, not a commodity"
            : "Imported invisible — no UEX counterpart");
    return material;
  }

  /**
   * Writes only the Wiki-owned columns onto a material; the UEX-canonical name, code, kind, type,
   * prices and {@code is_*} flags are never touched. The density of a row the P4K matched stays the
   * P4K's, and a different Wiki density is reported (REQ-P4K-024).
   *
   * @param material the row to update
   * @param dto the Wiki commodity row
   * @param now timestamp for {@code scwiki_synced_at}
   * @param runId current run id for event logging
   */
  private void applyWikiFields(
      @NotNull Material material, @NotNull ScWikiCommodityDto dto, Instant now, UUID runId) {
    material.setMiningData(dto.miningData());
    material.setScwikiUuid(dto.uuid());
    material.setScwikiKey(dto.key());
    material.setScwikiSlug(dto.slug());
    Double density = dto.densityGramPerCc();
    if (material.getP4kUuid() == null) {
      if (density != null) {
        material.setDensityGramPerCc(density);
      }
    } else if (density != null && !density.equals(material.getDensityGramPerCc())) {
      syncReportService.logP4kDisagreement(
          SyncSourceSystem.SCWIKI,
          runId,
          "commodity",
          material.getP4kUuid(),
          "density_g_per_cc",
          String.valueOf(material.getDensityGramPerCc()),
          String.valueOf(density));
    }
    material.setScwikiSyncedAt(now);
    material.setScwikiDeletedAt(null);
  }

  /**
   * Name-pattern filter that drops placeholder, HTML, raw-asset and atmosphere entries from the
   * Wiki commodity pool.
   *
   * @param dto the Wiki commodity row
   * @return {@code true} iff the row should be dropped without import
   */
  static boolean isCommodityHardJunk(ScWikiCommodityDto dto) {
    if (dto.name() == null || dto.name().isBlank()) {
      return true;
    }
    String n = dto.name();
    if (n.contains("<") || n.contains(">")) {
      return true;
    }
    if (n.startsWith("<=")) {
      return true;
    }
    if (n.contains("_")) {
      return true;
    }
    if (n.endsWith(":")) {
      return true;
    }
    if (n.startsWith("Ship Ammunition")) {
      return true;
    }
    return HARDCODED_ATMOSPHERE_SET.contains(n);
  }

  /**
   * Computes a commodity's canonical core via {@link MaterialNameCanonicalizer}, so {@code "Raw
   * Silicon"}, {@code "Silicon (Raw)"} and {@code "Silicon"} all yield {@code "silicon"}.
   *
   * @param name the raw commodity name
   * @return the canonical core, or {@code null} for null or blank input
   */
  @Nullable
  static String canonicalName(String name) {
    return MaterialNameCanonicalizer.canonicalCore(name);
  }

  /**
   * Outcome of the {@link #resolve} chain: a matched material, a "create a new WIKI_ONLY row"
   * signal ({@code material == null}, {@code skip == false}), or a "skip this row" signal ({@code
   * skip == true}) for a canonical multi-match.
   *
   * @param material the resolved material, or {@code null} when none matched
   * @param skip {@code true} to skip the row entirely (ambiguous canonical match)
   */
  private record ResolveResult(@Nullable Material material, boolean skip) {

    /**
     * A successful match.
     *
     * @param material the matched material
     * @return a matched result
     */
    @NotNull
    static ResolveResult matched(Material material) {
      return new ResolveResult(material, false);
    }

    /**
     * No match — the caller should create a new {@code WIKI_ONLY} row.
     *
     * @return a create-new result
     */
    @NotNull
    static ResolveResult createNew() {
      return new ResolveResult(null, false);
    }

    /**
     * Ambiguous canonical match — skip the row and defer to the admin alias UI.
     *
     * @return a skip result
     */
    @NotNull
    static ResolveResult skipped() {
      return new ResolveResult(null, true);
    }
  }
}
