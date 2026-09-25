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
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiCommodityDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiResponseDto;
import de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.model.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.SyncEventType;
import de.greluc.krt.profit.basetool.backend.model.SyncSourceSystem;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.service.MaterialExternalAliasService;
import de.greluc.krt.profit.basetool.backend.service.MaterialNameCanonicalizer;
import de.greluc.krt.profit.basetool.backend.service.SyncReportService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
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
@Transactional(readOnly = true)
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

  /**
   * Runs the full Wiki commodity merge; a no-op when {@code krt.scwiki.commodity-sync-enabled} is
   * off, and an empty response skips the orphan sweep.
   *
   * @return the number of {@code material} rows written ({@code linked + createdWikiOnly}), {@code
   *     0} when disabled or empty, or {@link MaterialRepository#countLiveScwikiMaterials() the live
   *     linked-material count} on a {@code 304 Not Modified}
   */
  @Transactional
  public int syncCommodities() {
    if (!Boolean.TRUE.equals(properties.commoditySyncEnabled())) {
      log.info(
          "SC Wiki commodity sync invoked but disabled "
              + "(krt.scwiki.commodity-sync-enabled=false) — skipping.");
      return 0;
    }

    log.info("Starting SC Wiki commodity merge...");
    ScWikiClient.FetchResult<ScWikiCommodityDto> fetchResult =
        scWikiClient.fetchAllPagesResult(
            properties.commoditiesEndpoint(),
            new ParameterizedTypeReference<ScWikiResponseDto<ScWikiCommodityDto>>() {},
            "commodities");
    if (fetchResult.notModified()) {
      long live = materialRepository.countLiveScwikiMaterials();
      log.info(
          "SC Wiki commodity catalogue unchanged since last sync (304) — reporting {} live linked"
              + " material row(s) instead of 0.",
          live);
      return (int) live;
    }
    List<ScWikiCommodityDto> fetched = fetchResult.data();
    if (fetched.isEmpty()) {
      log.warn("No commodities received from SC Wiki API. Aborting merge (no orphan sweep).");
      return 0;
    }

    UUID runId = syncReportService.beginRun();
    Instant now = Instant.now();
    Map<String, List<Material>> canonicalIndex = buildCanonicalIndex();

    Set<UUID> seenScwikiUuids = new HashSet<>();
    int linked = 0;
    int createdWikiOnly = 0;
    int skippedJunk = 0;
    int skippedAmbiguous = 0;

    for (ScWikiCommodityDto dto : fetched) {
      try {
        if (isCommodityHardJunk(dto)) {
          syncReportService.logCommodityEvent(
              runId, SyncEventType.SKIP_JUNK, dto.uuid(), dto.name(), "Hard-junk name pattern");
          skippedJunk++;
          continue;
        }
        if (dto.uuid() == null) {
          syncReportService.logCommodityEvent(
              runId, SyncEventType.SKIP_JUNK, null, dto.name(), "Wiki commodity without uuid");
          skippedJunk++;
          continue;
        }

        ResolveResult result = resolve(dto, canonicalIndex, runId);
        if (result.skip()) {
          skippedAmbiguous++;
          continue;
        }

        seenScwikiUuids.add(dto.uuid());
        Material material = result.material();
        if (material == null) {
          material = createWikiOnlyMaterial(dto, runId);
          createdWikiOnly++;
        } else {
          linked++;
        }
        applyWikiFields(material, dto, now);
        if (material.getSourceSystems() == MaterialSourceSystem.UEX_ONLY) {
          material.setSourceSystems(MaterialSourceSystem.BOTH);
        }
        materialRepository.save(material);
      } catch (Exception e) {
        log.error("Failed to process SC Wiki commodity dto: {}", dto.uuid(), e);
      }
    }

    if (seenScwikiUuids.isEmpty()) {
      log.warn("Skipping orphan sweep — no SC Wiki commodity was merged this run.");
    } else if (!fetchResult.complete()) {
      log.warn(
          "Skipping the material scwiki_deleted sweep: the Wiki commodity page walk did not"
              + " enumerate the whole feed this run, so the {} uuid(s) it saw are not a complete"
              + " census and every row outside them would be tombstoned for never having been"
              + " fetched.",
          seenScwikiUuids.size());
    } else {
      int marked = materialRepository.markScwikiDeleted(seenScwikiUuids, now);
      if (marked > 0) {
        log.info("Marked {} material row(s) scwiki_deleted (no longer in Wiki feed)", marked);
      }
    }

    syncReportService.pruneRuns(SyncSourceSystem.SCWIKI);
    log.info(
        "Finished SC Wiki commodity merge: {} linked, {} created WIKI_ONLY, {} junk-skipped, "
            + "{} ambiguous-skipped.",
        linked,
        createdWikiOnly,
        skippedJunk,
        skippedAmbiguous);
    return linked + createdWikiOnly;
  }

  /**
   * Resolves a Wiki commodity by {@code scwiki_uuid}, alias, exact name, then canonical name.
   *
   * <p>A canonical multi-match yields a skip rather than a new row, so a later admin alias can
   * still take effect; it is reported as {@link SyncEventType#MULTI_MATCH_AMBIGUOUS}.
   *
   * @param dto the Wiki commodity row
   * @param canonicalIndex canonical-name index of unmatched materials
   * @param runId current run id for event logging
   * @return a matched material, a "create new" result, or a skip result
   */
  private ResolveResult resolve(
      @NotNull ScWikiCommodityDto dto, Map<String, List<Material>> canonicalIndex, UUID runId) {
    Optional<Material> byUuid = materialRepository.findByScwikiUuid(dto.uuid());
    if (byUuid.isPresent()) {
      return ResolveResult.matched(byUuid.orElseThrow());
    }

    Material byAlias =
        aliasService.resolveMaterialByAlias(MaterialExternalAliasSource.SCWIKI, dto.name());
    if (byAlias != null) {
      syncReportService.logCommodityEvent(
          runId,
          SyncEventType.LINKED_VIA_ALIAS,
          dto.uuid(),
          dto.name(),
          "Linked to material '" + byAlias.getName() + "' via alias");
      return ResolveResult.matched(byAlias);
    }

    if (StringUtils.hasText(dto.name())) {
      Optional<Material> byName = materialRepository.findByName(dto.name());
      if (byName.isPresent()) {
        return ResolveResult.matched(byName.orElseThrow());
      }
    }

    String canon = canonicalName(dto.name());
    if (canon != null && !canon.isBlank()) {
      List<Material> candidates = canonicalIndex.getOrDefault(canon, List.of());
      if (candidates.size() == 1) {
        return ResolveResult.matched(candidates.getFirst());
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

  /**
   * Builds a {@code canonicalName → materials} index over materials without a Wiki UUID, so the
   * canonical step never links a row to a second Wiki commodity.
   *
   * @return canonical-name index of unmatched materials
   */
  @NotNull
  private Map<String, List<Material>> buildCanonicalIndex() {
    Map<String, List<Material>> index = new HashMap<>();
    for (Material material : materialRepository.findAll()) {
      if (material.getScwikiUuid() != null) {
        continue;
      }
      String canon = canonicalName(material.getName());
      if (canon == null || canon.isBlank()) {
        continue;
      }
      index.computeIfAbsent(canon, k -> new ArrayList<>()).add(material);
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
   * prices and {@code is_*} flags are never touched.
   *
   * @param material the row to update
   * @param dto the Wiki commodity row
   * @param now timestamp for {@code scwiki_synced_at}
   */
  private void applyWikiFields(
      @NotNull Material material, @NotNull ScWikiCommodityDto dto, Instant now) {
    material.setScwikiUuid(dto.uuid());
    material.setScwikiKey(dto.key());
    material.setScwikiSlug(dto.slug());
    if (dto.densityGramPerCc() != null) {
      material.setDensityGramPerCc(dto.densityGramPerCc());
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
  private record ResolveResult(Material material, boolean skip) {

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
