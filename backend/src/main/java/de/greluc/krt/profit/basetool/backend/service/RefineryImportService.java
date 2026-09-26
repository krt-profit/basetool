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

import de.greluc.krt.profit.basetool.backend.config.RefineryImportProperties;
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.mapper.LocationMapper;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.mapper.RefiningMethodMapper;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAlias;
import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.model.dto.ImportIssueCode;
import de.greluc.krt.profit.basetool.backend.model.dto.ImportIssueDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ImportIssueSeverity;
import de.greluc.krt.profit.basetool.backend.model.dto.ImportSuggestionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryExtractDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryExtractGoodDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryExtractImageDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryExtractOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryGoodDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryImportDraftDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Turns a {@code RefineryExtract} JSON into a non-persisted {@link RefineryImportDraftDto} by
 * matching the screen reads against master data.
 *
 * <p>Envelope problems throw {@link BadRequestException}; content problems yield a draft with
 * {@link ImportIssueDto}s. Materials are matched by canonical name, curated {@code REFINERY_SCREEN}
 * alias, truncation match, then {@link BlueprintFuzzyMatcher}; fuzzy hits are always flagged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefineryImportService {

  /** The only {@code RefineryExtract} schema version this backend accepts. */
  public static final int SUPPORTED_SCHEMA_VERSION = 1;

  /** The only panel type v1 drafts from; PROCESSING/UNKNOWN are envelope-level rejects. */
  private static final String PANEL_TYPE_SETUP = "SETUP";

  /**
   * Minimum canonical-core length for the suffix/contains stage. Guards against a heavily truncated
   * read (e.g. {@code "RE"}) accidentally being "contained" in half the catalogue.
   */
  private static final int MIN_PARTIAL_MATCH_LENGTH = 5;

  /** Savable quality range of the {@code refinery_good.quality} column (entity constraint). */
  private static final int QUALITY_MIN = 0;

  /** Upper bound of the savable quality range. */
  private static final int QUALITY_MAX = 1000;

  private final MaterialRepository materialRepository;
  private final RefiningMethodRepository refiningMethodRepository;
  private final LocationRepository locationRepository;
  private final UserRepository userRepository;
  private final MaterialExternalAliasService materialExternalAliasService;
  private final BlueprintFuzzyMatcher fuzzyMatcher;
  private final RefineryImportProperties properties;
  private final MaterialMapper materialMapper;
  private final LocationMapper locationMapper;
  private final RefiningMethodMapper refiningMethodMapper;
  private final UserMapper userMapper;

  /**
   * Validates the extract envelope and builds the best-effort draft from {@code orders[0]},
   * reporting skipped rows, unmatched names and total mismatches as {@link ImportIssueDto}s.
   * Nothing is persisted.
   *
   * @param extract the validated {@code RefineryExtract} payload
   * @param callerId id of the uploading user; becomes the draft's owner when it resolves
   * @return the draft order plus issues and match counters
   * @throws BadRequestException with an i18n key when {@code schemaVersion != 1} or {@code
   *     orders[0].panelType} is not {@code SETUP}
   */
  @NotNull
  public RefineryImportDraftDto buildDraft(
      @NotNull RefineryExtractDto extract, @Nullable UUID callerId) {
    if (extract.schemaVersion() == null || extract.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
      throw new BadRequestException("error.refineryImport.unsupportedSchemaVersion");
    }
    RefineryExtractOrderDto order = extract.orders().getFirst();
    if (order.panelType() == null || !PANEL_TYPE_SETUP.equalsIgnoreCase(order.panelType().trim())) {
      throw new BadRequestException("error.refineryImport.unsupportedPanelType");
    }

    List<ImportIssueDto> issues = new ArrayList<>();
    if (extract.orders().size() > 1) {
      issues.add(
          issue(
              "orders",
              String.valueOf(extract.orders().size()),
              ImportIssueCode.MULTIPLE_ORDERS_TRUNCATED,
              ImportIssueSeverity.INFO,
              null,
              null));
    }

    Optional<Location> location = matchRefineryLocation(order.rawLocationName());
    if (location.isEmpty()) {
      issues.add(
          issue(
              "location",
              order.rawLocationName(),
              ImportIssueCode.UNRESOLVED_LOCATION,
              ImportIssueSeverity.WARNING,
              null,
              null));
    }
    Optional<RefiningMethod> method = matchMethod(order.rawMethodName());
    if (method.isEmpty()) {
      issues.add(
          issue(
              "refiningMethod",
              order.rawMethodName(),
              ImportIssueCode.UNRESOLVED_METHOD,
              ImportIssueSeverity.WARNING,
              null,
              null));
    }

    List<RefineryExtractGoodDto> sourceGoods = sortedByRowIndex(order.goods());
    boolean orderUnquoted =
        Boolean.FALSE.equals(order.quoted())
            || (!sourceGoods.isEmpty()
                && sourceGoods.stream().allMatch(g -> g.outputQuantity() == null));
    if (orderUnquoted) {
      issues.add(
          issue(
              "quoted",
              String.valueOf(order.quoted()),
              ImportIssueCode.UNQUOTED_ORDER,
              ImportIssueSeverity.BLOCKING,
              null,
              null));
    }

    MatchContext context = prepareMatchContext();
    List<RefineryGoodDto> draftGoods = new ArrayList<>();
    int goodsMatched = 0;
    int rowsSkipped = 0;
    for (int sourceIndex = 0; sourceIndex < sourceGoods.size(); sourceIndex++) {
      RefineryExtractGoodDto good = sourceGoods.get(sourceIndex);
      int screenRow = good.rowIndex() != null ? good.rowIndex() : sourceIndex;
      ImportIssueCode skipReason = skipReason(good);
      if (skipReason != null) {
        rowsSkipped++;
        issues.add(
            issue(
                "goods[" + screenRow + "]",
                good.rawMaterialName(),
                skipReason,
                skipReason == ImportIssueCode.SKIPPED_REFINE_OFF
                    ? ImportIssueSeverity.INFO
                    : ImportIssueSeverity.WARNING,
                good.confidence(),
                null));
        continue;
      }
      int draftIndex = draftGoods.size();
      MaterialMatch match = matchMaterialDetailed(good.rawMaterialName(), context);
      MaterialDto inputMaterial = null;
      MaterialDto outputMaterial = null;
      if (match.material() != null) {
        goodsMatched++;
        inputMaterial = materialMapper.toDto(match.material());
        if (match.fuzzy()) {
          issues.add(
              issue(
                  "goods[" + draftIndex + "].inputMaterial",
                  good.rawMaterialName(),
                  ImportIssueCode.LOW_CONFIDENCE_MATERIAL,
                  ImportIssueSeverity.WARNING,
                  match.score(),
                  match.suggestions()));
        }
        Material refined = match.material().getRefinedMaterial();
        if (refined != null) {
          outputMaterial = materialMapper.toDto(refined);
        } else {
          issues.add(
              issue(
                  "goods[" + draftIndex + "].outputMaterial",
                  match.material().getName(),
                  ImportIssueCode.NO_REFINED_MATERIAL,
                  ImportIssueSeverity.INFO,
                  good.confidence(),
                  null));
        }
      } else {
        issues.add(
            issue(
                "goods[" + draftIndex + "].inputMaterial",
                good.rawMaterialName(),
                ImportIssueCode.UNMATCHED_MATERIAL,
                ImportIssueSeverity.WARNING,
                good.confidence(),
                match.suggestions()));
      }
      int quality = good.quality() != null ? good.quality() : 0;
      if (quality < QUALITY_MIN || quality > QUALITY_MAX) {
        issues.add(
            issue(
                "goods[" + draftIndex + "].quality",
                String.valueOf(good.quality()),
                ImportIssueCode.OUT_OF_RANGE_QUALITY,
                ImportIssueSeverity.WARNING,
                good.confidence(),
                null));
      }
      draftGoods.add(
          new RefineryGoodDto(
              null,
              inputMaterial,
              good.inputQuantity(),
              outputMaterial,
              good.outputQuantity(),
              quality,
              null));
    }

    reconcileHeaderTotals(order, sourceGoods, issues);

    RefineryOrderDto draftOrder =
        new RefineryOrderDto(
            null,
            resolveOwnerReference(callerId),
            location.map(locationMapper::toDto).orElse(null),
            null,
            deriveStartedAt(order.sourceImages()),
            order.durationMinutes(),
            order.expenses(),
            null,
            null,
            null,
            method.map(refiningMethodMapper::toDto).orElse(null),
            RefineryOrderStatus.OPEN.name(),
            draftGoods,
            null,
            null,
            null);
    log.debug(
        "Built refinery import draft: {} of {} rows matched, {} skipped, {} issues",
        goodsMatched,
        sourceGoods.size(),
        rowsSkipped,
        issues.size());
    return new RefineryImportDraftDto(
        draftOrder, List.copyOf(issues), goodsMatched, sourceGoods.size(), rowsSkipped);
  }

  /**
   * Resolves a raw screen material name against the refinery-input candidates; the hit-only view of
   * {@link #matchMaterialDetailed(String, MatchContext)}.
   *
   * @param rawName verbatim screen read, e.g. {@code "STILERON (ORE)"}
   * @return the matched material, or empty when no stage produced a hit
   */
  @NotNull
  public Optional<Material> matchMaterial(@Nullable String rawName) {
    return Optional.ofNullable(matchMaterialDetailed(rawName, prepareMatchContext()).material());
  }

  /**
   * Resolves a raw method read against {@code refining_method}, stopping at the first hit.
   *
   * <ol>
   *   <li>exact case-insensitive name;
   *   <li>unique canonical-core fold;
   *   <li>best {@link BlueprintFuzzyMatcher} candidate at or above {@link
   *       RefineryImportProperties#getMethodFuzzyAcceptThreshold()}.
   * </ol>
   *
   * @param rawName verbatim screen read; null/blank yields empty
   * @return the matched refining method, or empty
   */
  public Optional<RefiningMethod> matchMethod(@Nullable String rawName) {
    if (!StringUtils.hasText(rawName)) {
      return Optional.empty();
    }
    String trimmed = rawName.trim();
    Optional<RefiningMethod> exact = refiningMethodRepository.findByNameIgnoreCase(trimmed);
    if (exact.isPresent()) {
      return exact;
    }

    List<RefiningMethod> candidates = refiningMethodRepository.findAll();
    if (candidates.isEmpty()) {
      return Optional.empty();
    }

    String canonical = MaterialNameCanonicalizer.canonicalCore(trimmed);
    if (canonical != null && !canonical.isEmpty()) {
      List<RefiningMethod> canonicalHits =
          candidates.stream()
              .filter(m -> canonical.equals(MaterialNameCanonicalizer.canonicalCore(m.getName())))
              .toList();
      if (canonicalHits.size() == 1) {
        return Optional.of(canonicalHits.getFirst());
      }
    }

    String fuzzyKey = MaterialNameCanonicalizer.fuzzyKey(trimmed);
    if (fuzzyKey == null || fuzzyKey.isEmpty()) {
      return Optional.empty();
    }
    List<BlueprintFuzzyMatcher.Scored<RefiningMethod>> ranked =
        fuzzyMatcher.topMatches(
            fuzzyKey,
            candidates,
            m -> MaterialNameCanonicalizer.fuzzyKey(m.getName()),
            Comparator.comparing(
                RefiningMethod::getName, Comparator.nullsLast(String::compareToIgnoreCase)),
            1,
            properties.methodFuzzyAcceptThreshold());
    return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked.getFirst().candidate());
  }

  /**
   * Resolves a raw location read against the non-hidden refinery locations by unique canonical name
   * (REQ-REFINERY-020).
   *
   * @param rawName verbatim terminal-header read; null/blank yields empty
   * @return the matched location, or empty when none or several candidates share the folded name
   */
  @NotNull
  public Optional<Location> matchRefineryLocation(@Nullable String rawName) {
    String canonical = MaterialNameCanonicalizer.canonicalCore(rawName);
    if (canonical == null || canonical.isEmpty()) {
      return Optional.empty();
    }
    List<Location> hits =
        locationRepository.findLocationsWithRefinery().stream()
            .filter(l -> canonical.equals(MaterialNameCanonicalizer.canonicalCore(l.getName())))
            .toList();
    return hits.size() == 1 ? Optional.of(hits.getFirst()) : Optional.empty();
  }

  /**
   * Classifies a source row that must not become a draft good: REFINE off, un-quoted yield ({@code
   * outputQuantity == null}), or zero quantity.
   *
   * @param good the source row
   * @return the skip reason, or {@code null} when the row is draftable
   */
  private @Nullable ImportIssueCode skipReason(RefineryExtractGoodDto good) {
    if (Boolean.FALSE.equals(good.refine())) {
      return ImportIssueCode.SKIPPED_REFINE_OFF;
    }
    if (good.outputQuantity() == null) {
      return ImportIssueCode.UNQUOTED_ROW;
    }
    if (good.inputQuantity() == null || good.inputQuantity() < 1 || good.outputQuantity() < 1) {
      return ImportIssueCode.SKIPPED_ZERO_QTY;
    }
    return null;
  }

  /**
   * Flags a {@code SUM_MISMATCH} warning when the refine-on row quantities exceed {@code
   * rawToRefineTotal} beyond per-row rounding, or one row alone exceeds it by more than 1
   * (REQ-REFINERY-007). A shortfall is never flagged.
   *
   * @param order the extracted order carrying the nullable header totals
   * @param sourceGoods all source rows (including skipped ones)
   * @param issues sink for the mismatch findings
   */
  private void reconcileHeaderTotals(
      @NotNull RefineryExtractOrderDto order,
      List<RefineryExtractGoodDto> sourceGoods,
      List<ImportIssueDto> issues) {
    Long toRefineTotal = order.rawToRefineTotal();
    if (toRefineTotal == null) {
      return;
    }
    List<Long> refineOnQuantities =
        sourceGoods.stream()
            .filter(g -> !Boolean.FALSE.equals(g.refine()))
            .map(g -> g.inputQuantity() != null ? g.inputQuantity().longValue() : 0L)
            .toList();
    long sumRefineOn = refineOnQuantities.stream().mapToLong(Long::longValue).sum();
    long tolerance = sourceGoods.size();
    boolean anyRowExceeds = refineOnQuantities.stream().anyMatch(qty -> qty > toRefineTotal + 1);
    if (sumRefineOn > toRefineTotal + tolerance || anyRowExceeds) {
      issues.add(
          issue(
              "rawToRefineTotal",
              toRefineTotal + " != " + sumRefineOn,
              ImportIssueCode.SUM_MISMATCH,
              ImportIssueSeverity.WARNING,
              null,
              null));
    }
  }

  /**
   * Loads the candidate materials once per request and builds the canonical-name and alias indexes
   * the matching stages use; aliases whose target fails the candidate gate are omitted
   * (REQ-REFINERY-012).
   *
   * @return the per-request matching context
   */
  @NotNull
  private MatchContext prepareMatchContext() {
    List<Material> candidates = materialRepository.findRefineryInputCandidates(MaterialType.RAW);
    Map<String, List<Material>> canonicalIndex = new HashMap<>();
    for (Material candidate : candidates) {
      String canonical = MaterialNameCanonicalizer.canonicalCore(candidate.getName());
      if (canonical != null && !canonical.isEmpty()) {
        canonicalIndex.computeIfAbsent(canonical, k -> new ArrayList<>()).add(candidate);
      }
    }
    Set<UUID> candidateIds = new HashSet<>();
    for (Material candidate : candidates) {
      if (candidate.getId() != null) {
        candidateIds.add(candidate.getId());
      }
    }
    Map<String, List<Material>> aliasCanonicalIndex = new HashMap<>();
    for (MaterialExternalAlias alias :
        materialExternalAliasService.findBySourceSystem(
            MaterialExternalAliasSource.REFINERY_SCREEN)) {
      Material target = alias.getMaterial();
      String aliasCanonical = MaterialNameCanonicalizer.canonicalCore(alias.getExternalName());
      if (aliasCanonical == null
          || aliasCanonical.isEmpty()
          || target == null
          || target.getId() == null
          || !candidateIds.contains(target.getId())) {
        continue;
      }
      aliasCanonicalIndex.computeIfAbsent(aliasCanonical, k -> new ArrayList<>()).add(target);
    }
    return new MatchContext(candidates, canonicalIndex, candidateIds, aliasCanonicalIndex);
  }

  /**
   * Runs the material matching stages (canonical match, alias, truncation, fuzzy) against the
   * prepared context; fuzzy hits below the threshold leave the row unmatched with suggestions.
   *
   * @param rawName verbatim screen read
   * @param context the per-request candidate context
   * @return the detailed outcome (never {@code null}; an unmatchable name yields an empty result)
   */
  private MaterialMatch matchMaterialDetailed(@Nullable String rawName, MatchContext context) {
    String canonical = MaterialNameCanonicalizer.canonicalCore(rawName);
    if (canonical == null || canonical.isEmpty()) {
      return MaterialMatch.unmatched(null);
    }
    List<Material> exact = context.canonicalIndex().getOrDefault(canonical, List.of());
    if (exact.size() == 1) {
      return MaterialMatch.exact(exact.getFirst());
    }

    Material viaAlias =
        materialExternalAliasService.resolveMaterialByAlias(
            MaterialExternalAliasSource.REFINERY_SCREEN, rawName);
    if (viaAlias != null) {
      if (viaAlias.getId() != null && context.candidateIds().contains(viaAlias.getId())) {
        return MaterialMatch.exact(viaAlias);
      }
      log.warn(
          "Ignoring REFINERY_SCREEN alias for '{}': target material '{}' fails the"
              + " refinery-input gate (RAW || isManualRawMaterial, visible)",
          rawName,
          viaAlias.getName());
    }

    if (canonical.length() >= MIN_PARTIAL_MATCH_LENGTH) {
      Map<UUID, Material> partial = new LinkedHashMap<>();
      for (Material candidate : context.candidates()) {
        String candidateCanonical = MaterialNameCanonicalizer.canonicalCore(candidate.getName());
        if (candidateCanonical != null && candidateCanonical.contains(canonical)) {
          partial.put(candidate.getId(), candidate);
        }
      }
      for (Map.Entry<String, List<Material>> aliasEntry :
          context.aliasCanonicalIndex().entrySet()) {
        if (aliasEntry.getKey().contains(canonical)) {
          aliasEntry.getValue().forEach(target -> partial.put(target.getId(), target));
        }
      }
      if (partial.size() == 1) {
        return MaterialMatch.exact(partial.values().iterator().next());
      }
    }

    String fuzzyKey = MaterialNameCanonicalizer.fuzzyKey(rawName);
    if (fuzzyKey == null || fuzzyKey.isEmpty()) {
      return MaterialMatch.unmatched(null);
    }
    List<BlueprintFuzzyMatcher.Scored<Material>> ranked =
        fuzzyMatcher.topMatches(
            fuzzyKey,
            context.candidates(),
            c -> MaterialNameCanonicalizer.fuzzyKey(c.getName()),
            Comparator.comparing(
                Material::getName, Comparator.nullsLast(String::compareToIgnoreCase)),
            properties.suggestionLimit(),
            properties.suggestionFloor());
    List<ImportSuggestionDto> suggestions =
        ranked.stream()
            .map(
                s ->
                    new ImportSuggestionDto(
                        s.candidate().getId(), s.candidate().getName(), s.score()))
            .toList();
    if (!ranked.isEmpty() && ranked.getFirst().score() >= properties.fuzzyAcceptThreshold()) {
      return MaterialMatch.fuzzy(
          ranked.getFirst().candidate(), ranked.getFirst().score(), suggestions);
    }
    return MaterialMatch.unmatched(suggestions.isEmpty() ? null : suggestions);
  }

  /**
   * Returns the source rows ordered by their stitched on-screen {@code rowIndex} (nulls last, in
   * arrival order) so the draft's goods mirror the in-game screen top-to-bottom.
   *
   * @param goods the contract's goods list (never {@code null} after bean validation)
   * @return a new sorted list
   */
  private List<RefineryExtractGoodDto> sortedByRowIndex(
      @NotNull List<RefineryExtractGoodDto> goods) {
    return goods.stream()
        .sorted(
            Comparator.comparing(
                RefineryExtractGoodDto::rowIndex, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  /**
   * Resolves the uploading user as the draft owner; an unknown id leaves the owner empty.
   *
   * @param callerId id of the authenticated uploader; may be {@code null}
   * @return the owner reference, or {@code null}
   */
  @Contract("null -> null")
  private @Nullable UserReferenceDto resolveOwnerReference(@Nullable UUID callerId) {
    if (callerId == null) {
      return null;
    }
    return userRepository.findPlainById(callerId).map(userMapper::toReferenceDto).orElse(null);
  }

  /**
   * Derives the draft's start time as the latest {@code capturedAt} across the order's source
   * images (REQ-REFINERY-017).
   *
   * @param sourceImages the order's source-image provenance; {@code null}-safe
   * @return the latest capture instant, or {@code null} when no image carries one
   */
  @Contract("null -> null")
  private static @Nullable Instant deriveStartedAt(
      @Nullable List<RefineryExtractImageDto> sourceImages) {
    if (sourceImages == null) {
      return null;
    }
    return sourceImages.stream()
        .filter(image -> image != null && image.capturedAt() != null)
        .map(RefineryExtractImageDto::capturedAt)
        .max(Comparator.naturalOrder())
        .orElse(null);
  }

  /**
   * Creates an {@link ImportIssueDto}.
   *
   * @param field dotted field path (see {@link ImportIssueDto})
   * @param rawValue verbatim read or compact diagnostic
   * @param code machine-readable reason
   * @param severity visual grading
   * @param confidence contextual confidence, nullable
   * @param suggestions ranked candidates, nullable
   * @return the assembled issue
   */
  @NotNull
  private static ImportIssueDto issue(
      String field,
      @Nullable String rawValue,
      ImportIssueCode code,
      ImportIssueSeverity severity,
      @Nullable Double confidence,
      @Nullable List<ImportSuggestionDto> suggestions) {
    return new ImportIssueDto(field, rawValue, code, severity, confidence, suggestions);
  }

  /**
   * Per-request matching context: the gated candidate set plus the derived indexes.
   *
   * @param candidates visible {@code RAW || isManualRawMaterial} materials
   * @param canonicalIndex canonical core to the candidates sharing it
   * @param candidateIds candidate primary keys, which an alias hit must belong to
   * @param aliasCanonicalIndex canonicalized {@code REFINERY_SCREEN} alias name to its gate-passing
   *     target materials
   */
  private record MatchContext(
      List<Material> candidates,
      Map<String, List<Material>> canonicalIndex,
      Set<UUID> candidateIds,
      Map<String, List<Material>> aliasCanonicalIndex) {}

  /**
   * Detailed outcome of one material-matching run.
   *
   * @param material the matched material, or {@code null}
   * @param fuzzy {@code true} when only the fuzzy stage produced the hit (must be flagged)
   * @param score the fuzzy score for fuzzy hits, else {@code null}
   * @param suggestions ranked candidates for the review pick list, or {@code null}
   */
  private record MaterialMatch(
      @Nullable Material material,
      boolean fuzzy,
      @Nullable Double score,
      @Nullable List<ImportSuggestionDto> suggestions) {

    /**
     * A deterministic (canonical / alias / suffix) hit.
     *
     * @param material the matched material
     * @return the match result
     */
    @NotNull
    static MaterialMatch exact(Material material) {
      return new MaterialMatch(material, false, null, null);
    }

    /**
     * A fuzzy-stage hit at or above the accept threshold.
     *
     * @param material the matched material
     * @param score the blended similarity score
     * @param suggestions the ranked alternatives shown alongside the flag
     * @return the match result
     */
    @NotNull
    static MaterialMatch fuzzy(
        Material material, double score, List<ImportSuggestionDto> suggestions) {
      return new MaterialMatch(material, true, score, suggestions);
    }

    /**
     * No stage matched.
     *
     * @param suggestions ranked candidates for the manual pick list, or {@code null}
     * @return the match result
     */
    @NotNull
    static MaterialMatch unmatched(@Nullable List<ImportSuggestionDto> suggestions) {
      return new MaterialMatch(null, false, null, suggestions);
    }
  }
}
