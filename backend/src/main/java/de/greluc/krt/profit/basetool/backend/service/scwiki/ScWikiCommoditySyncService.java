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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * R3 SC Wiki commodity merge (SC_WIKI_SYNC_PLAN.md §8.1). Pulls {@code /api/commodities}, filters
 * out non-tradeable junk (§8.9), and merges the rest into the existing {@code material} table.
 *
 * <p>Merge model (§4.6 conflict policy): UEX stays the canonical owner of {@code name} / {@code
 * code} / {@code kind} / prices / {@code is_*} flags. The Wiki sync only ever writes the Wiki-owned
 * columns ({@code scwiki_uuid} / {@code scwiki_key} / {@code scwiki_slug} / {@code
 * density_g_per_cc} / {@code scwiki_synced_at}) on a matched UEX row, and flips {@code
 * source_systems} from {@code UEX_ONLY} to {@code BOTH}. A Wiki commodity with no UEX counterpart
 * becomes a fresh {@code WIKI_ONLY} row inserted <b>invisible</b> ({@code is_visible = false}) so
 * it never pollutes the trading / refinery UI until an admin reviews it (§4.3).
 *
 * <p>Resolution chain (§8.1.1): {@code scwiki_uuid} → alias table → exact name → canonical-name
 * (qualifier-stripped) with multi-match rejection. On a canonical multi-match the row is
 * <b>skipped</b> (not turned into a {@code WIKI_ONLY} row) and a {@link
 * SyncEventType#MULTI_MATCH_AMBIGUOUS} event is logged — see {@link #resolve(ScWikiCommodityDto,
 * Map, UUID)} for why this deviates from the plan's literal pseudocode.
 *
 * <p>Gated behind {@code krt.scwiki.commodity-sync-enabled} (default {@code false}) so R3 ships
 * "dark": the code, table and admin page all land, but no live Wiki traffic is generated until an
 * operator flips the flag per the deployment runbook §3. Empty Wiki responses short-circuit without
 * touching local data; the orphan sweep is gated on a non-empty seen-set (§8.7).
 *
 * <p>Lives in {@code integration.scwiki} and injects {@link ScWikiClient} — the {@code
 * scWikiIntegrationClassesMustWireScWikiClient} ArchUnit rule enforces that.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScWikiCommoditySyncService {

  /**
   * Atmospheric / environment-system entries that pollute the Wiki commodity pool but are not
   * tradeable commodities (§8.9). Hard-dropped at sync time. A maintained constant — grow it via PR
   * review, not at runtime.
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
   * Runs the full Wiki commodity merge. No-op (with an INFO line) when {@code
   * krt.scwiki.commodity-sync-enabled} is {@code false}. An empty Wiki response short-circuits
   * before the orphan sweep so a transient outage never wipes the merge state.
   *
   * <p>Returns the number of {@code material} rows this run wrote — matched rows linked plus fresh
   * {@code WIKI_ONLY} rows created (junk- and ambiguous-skips excluded) — which {@link
   * ScWikiScheduler} accumulates into {@code
   * basetool_scheduled_job_items_total{job="scwiki_sync"}}. The disabled and empty-response
   * short-circuits return {@code 0} so a Wiki outage surfaces as a zero-item run ({@code
   * SyncZeroItems}, #1041 item 2) rather than a fresh success.
   *
   * @return the number of {@code material} rows written this run ({@code linked + createdWikiOnly})
   */
  @Transactional
  public int syncCommodities() {
    if (!Boolean.TRUE.equals(properties.getCommoditySyncEnabled())) {
      log.info(
          "SC Wiki commodity sync invoked but disabled "
              + "(krt.scwiki.commodity-sync-enabled=false) — skipping.");
      return 0;
    }

    log.info("Starting SC Wiki commodity merge...");
    List<ScWikiCommodityDto> fetched =
        scWikiClient.fetchAllPages(
            properties.getCommoditiesEndpoint(),
            new ParameterizedTypeReference<ScWikiResponseDto<ScWikiCommodityDto>>() {},
            "commodities");
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
   * Resolution chain (§8.1.1). Returns a matched material, a {@code null}-material "create new"
   * result, or a "skip" result for a canonical multi-match.
   *
   * <p><b>Deviation from the plan's literal pseudocode:</b> §8.1's pseudocode turns a {@code null}
   * resolution into a new {@code WIKI_ONLY} row unconditionally. For the canonical multi-match case
   * (§8.1.1 step 4) that is unsafe: creating a {@code WIKI_ONLY} row would stamp the Wiki UUID onto
   * a fresh row, and step 1 ({@code byScwikiUuid}) would then shadow it on every subsequent run —
   * the admin's later alias fix could never take effect. Since an ambiguous canonical match means
   * the commodity is almost certainly already represented by one of the matched UEX rows, the
   * correct action is to skip (no row created, no UUID stamped) and let the admin add an alias; the
   * next run resolves it cleanly via step 2. The {@link SyncEventType#MULTI_MATCH_AMBIGUOUS} event
   * surfaces it for review.
   *
   * @param dto the Wiki commodity row
   * @param canonicalIndex pre-built canonical-name → unmatched-materials index
   * @param runId current run id for event logging
   * @return resolution outcome
   */
  private ResolveResult resolve(
      ScWikiCommodityDto dto, Map<String, List<Material>> canonicalIndex, UUID runId) {
    // 1. by Wiki UUID (set on a previous sync).
    Optional<Material> byUuid = materialRepository.findByScwikiUuid(dto.uuid());
    if (byUuid.isPresent()) {
      return ResolveResult.matched(byUuid.orElseThrow());
    }

    // 2. by alias table (seeded §4.1/§4.2 + admin-curated).
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

    // 3. exact name.
    if (StringUtils.hasText(dto.name())) {
      Optional<Material> byName = materialRepository.findByName(dto.name());
      if (byName.isPresent()) {
        return ResolveResult.matched(byName.orElseThrow());
      }
    }

    // 4. canonical (qualifier-stripped) name, restricted to materials without a Wiki UUID yet.
    String canon = canonicalName(dto.name());
    if (canon != null && !canon.isBlank()) {
      List<Material> candidates = canonicalIndex.getOrDefault(canon, List.of());
      if (candidates.size() == 1) {
        return ResolveResult.matched(candidates.get(0));
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

    // 5/6. no match → caller creates a WIKI_ONLY row.
    return ResolveResult.createNew();
  }

  /**
   * Builds a {@code canonicalName → materials} index over the materials that do not yet carry a
   * Wiki UUID. Materials already linked by UUID are excluded so the canonical step can never
   * re-link a row to a second Wiki commodity. Built once per run (the commodity catalogue is a few
   * hundred rows; the Wiki sync runs at most daily).
   *
   * @return canonical-name index of unmatched materials
   */
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
      index.computeIfAbsent(canon, k -> new java.util.ArrayList<>()).add(material);
    }
    return index;
  }

  /**
   * Creates a fresh {@code WIKI_ONLY} material for a Wiki commodity with no UEX counterpart. The
   * row is invisible ({@code is_visible = false}) so it stays out of trading flows until an admin
   * reviews it. Emits {@link SyncEventType#LOOKS_LIKE_ITEM} for the §4.3 "items in the commodity
   * pool" set, otherwise {@link SyncEventType#CREATED_WIKI_ONLY}.
   *
   * @param dto the Wiki commodity row
   * @param runId current run id for event logging
   * @return a new, unsaved {@link Material}
   */
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
   * Writes only the Wiki-owned columns onto a material, per the §4.6 conflict policy. Never touches
   * {@code name} / {@code code} / {@code kind} / {@code type} / prices / {@code is_*} flags — those
   * stay UEX-canonical. For a brand-new {@code WIKI_ONLY} row the name was already set from the
   * Wiki at creation time.
   *
   * @param material the row to update
   * @param dto the Wiki commodity row
   * @param now timestamp to stamp on {@code scwiki_synced_at}
   */
  private void applyWikiFields(Material material, ScWikiCommodityDto dto, Instant now) {
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
   * Hard-junk name filter (§8.9). Drops placeholder / HTML / raw-asset / atmosphere entries that
   * pollute the Wiki commodity pool. Purely name-pattern based — the verified-unreliable flag-based
   * heuristic was removed (§4.3).
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
   * Computes a commodity's canonical core: lowercased, parenthetical suffixes removed, qualifier
   * words ({@code raw} / {@code ore} / {@code refined} / {@code pure} / {@code r}) dropped, and
   * non-alphanumeric runs folded away. {@code "Raw Silicon"}, {@code "Silicon (Raw)"} and {@code
   * "Silicon"} all canonicalise to {@code "silicon"}. The folding itself lives in the shared {@link
   * MaterialNameCanonicalizer} since #434 so the refinery screenshot import applies bit-identical
   * rules; this delegate keeps the sync's historical call sites and tests stable.
   *
   * @param name the raw commodity name
   * @return the canonical core, or {@code null} for null / blank input
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
    static ResolveResult matched(Material material) {
      return new ResolveResult(material, false);
    }

    /**
     * No match — the caller should create a new {@code WIKI_ONLY} row.
     *
     * @return a create-new result
     */
    static ResolveResult createNew() {
      return new ResolveResult(null, false);
    }

    /**
     * Ambiguous canonical match — skip the row and defer to the admin alias UI.
     *
     * @return a skip result
     */
    static ResolveResult skipped() {
      return new ResolveResult(null, true);
    }
  }
}
