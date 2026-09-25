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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.BlueprintExternalAlias;
import de.greluc.krt.profit.basetool.backend.model.BlueprintExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportApplyRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportPreviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportResolutionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportStatus;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintImportSuggestionDto;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintExternalAliasRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * Imports a user's blueprints from an uploaded export (REQ-INV-014) in two steps.
 *
 * <ol>
 *   <li>{@link #previewImport(String, MultipartFile)} resolves each entry by structural tag, exact
 *       name, alias, then fuzzy suggestion, without persisting anything.
 *   <li>{@link #applyImport(String, List)} creates the chosen owned-blueprint rows and learns an
 *       alias for every manual pick.
 * </ol>
 *
 * <p>Works on an explicit owner id and never reads the security context.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class BlueprintImportService {

  /** External catalogue every import row and learned alias belongs to. */
  private static final BlueprintExternalAliasSource SOURCE = BlueprintExternalAliasSource.SCMDB;

  private final ObjectMapper objectMapper;
  private final BlueprintProductService blueprintProductService;
  private final BlueprintNameNormalizer normalizer;
  private final BlueprintFuzzyMatcher fuzzyMatcher;
  private final BlueprintExternalAliasRepository aliasRepository;
  private final PersonalBlueprintRepository personalBlueprintRepository;
  private final GameItemRepository gameItemRepository;

  /**
   * Parses an uploaded export and previews how each unique blueprint resolves for {@code
   * ownerUserId}. Nothing is persisted.
   *
   * @param ownerUserId the {@code app_user.id} the preview is for
   * @param file the uploaded blueprint export JSON
   * @return the preview with per-name rows and per-status counts
   * @throws BadRequestException if the file is empty, not valid JSON, or carries no blueprint array
   */
  @NotNull
  public BlueprintImportPreviewDto previewImport(
      @NotNull UUID ownerUserId, @NotNull MultipartFile file) {
    List<BlueprintExportParser.ParsedEntry> parsed =
        BlueprintExportParser.parse(objectMapper, file);

    Map<String, ResolvedProduct> productByKey = productIndex();
    List<ResolvedProduct> allProducts = new ArrayList<>(productByKey.values());
    Map<String, String> tagIndex =
        parsed.stream().anyMatch(e -> e.tag() != null)
            ? blueprintProductService.scwikiKeyToProductKeyIndex()
            : Map.of();

    List<Resolution> resolutions = new ArrayList<>(parsed.size());
    Set<String> resolvedKeys = new HashSet<>();
    for (BlueprintExportParser.ParsedEntry entry : parsed) {
      Resolution resolution = resolve(entry, productByKey, allProducts, tagIndex);
      resolutions.add(resolution);
      if (resolution.product != null) {
        resolvedKeys.add(resolution.product.productKey());
      }
    }

    Set<String> ownedKeys = ownedKeys(ownerUserId, resolvedKeys);

    List<BlueprintImportEntryDto> entries = new ArrayList<>(resolutions.size());
    int matched = 0;
    int matchedByAlias = 0;
    int suggested = 0;
    int unmatched = 0;
    int alreadyOwned = 0;
    for (Resolution resolution : resolutions) {
      BlueprintImportStatus status = resolution.status;
      ResolvedProduct product = resolution.product;
      if (product != null && ownedKeys.contains(product.productKey())) {
        status = BlueprintImportStatus.ALREADY_OWNED;
      }
      switch (status) {
        case MATCHED -> matched++;
        case MATCHED_BY_ALIAS -> matchedByAlias++;
        case SUGGESTED -> suggested++;
        case UNMATCHED -> unmatched++;
        case ALREADY_OWNED -> alreadyOwned++;
        default -> {}
      }
      entries.add(
          new BlueprintImportEntryDto(
              resolution.externalName,
              status,
              product == null ? null : product.productKey(),
              product == null ? null : product.productName(),
              product == null ? null : product.outputItemId(),
              resolution.suggestedAcquiredAt,
              resolution.suggestions));
    }

    log.info(
        "Blueprint import preview for ownerUserId={}: total={} matched={} alias={} suggested={}"
            + " unmatched={} alreadyOwned={}",
        ownerUserId,
        entries.size(),
        matched,
        matchedByAlias,
        suggested,
        unmatched,
        alreadyOwned);
    return new BlueprintImportPreviewDto(
        entries.size(), matched, matchedByAlias, suggested, unmatched, alreadyOwned, entries);
  }

  /**
   * Applies the per-name resolutions: creates missing owned-blueprint rows and learns an alias for
   * every manual pick. Idempotent for a resubmitted preview.
   *
   * <p>An owned blueprint is never duplicated; its acquisition time is only moved earlier.
   *
   * @param ownerUserId the {@code app_user.id} the rows are created for
   * @param resolutions the per-name decisions (see {@link BlueprintImportApplyRequest})
   * @return the added, learned, skipped and already-owned counts
   */
  @Transactional
  @NotNull
  public BlueprintImportResultDto applyImport(
      @NotNull UUID ownerUserId, @NotNull List<BlueprintImportResolutionDto> resolutions) {
    Map<String, ResolvedProduct> productByKey = productIndex();

    int added = 0;
    int aliasesLearned = 0;
    int skipped = 0;
    int alreadyOwned = 0;
    int acquiredAtUpdated = 0;
    Map<String, PersonalBlueprint> ownedByKey = ownedByKey(ownerUserId, productByKey.keySet());
    Set<String> aliasNamesSeen = new HashSet<>();

    for (BlueprintImportResolutionDto resolution : resolutions) {
      String externalName =
          resolution.externalName() == null ? "" : resolution.externalName().trim();
      String chosenKey = resolution.productKey() == null ? "" : resolution.productKey().trim();
      if (chosenKey.isEmpty()) {
        skipped++;
        continue;
      }
      ResolvedProduct product = productByKey.get(chosenKey);
      if (product == null) {
        log.debug("Import apply: skipping unresolvable product key '{}'", chosenKey);
        skipped++;
        continue;
      }

      if (learnAliasIfManual(ownerUserId, externalName, product, aliasNamesSeen)) {
        aliasesLearned++;
      }

      PersonalBlueprint existing = ownedByKey.get(product.productKey());
      if (existing != null) {
        if (isEarlierAcquiredAt(resolution.acquiredAt(), existing.getAcquiredAt())) {
          existing.setAcquiredAt(resolution.acquiredAt());
          acquiredAtUpdated++;
        }
        alreadyOwned++;
        continue;
      }
      ownedByKey.put(
          product.productKey(),
          personalBlueprintRepository.save(
              newOwned(ownerUserId, product, resolution.acquiredAt(), resolution.note())));
      added++;
    }

    log.info(
        "Blueprint import apply for ownerUserId={}: added={} aliasesLearned={} skipped={}"
            + " alreadyOwned={} acquiredAtUpdated={}",
        ownerUserId,
        added,
        aliasesLearned,
        skipped,
        alreadyOwned,
        acquiredAtUpdated);
    return new BlueprintImportResultDto(
        added, aliasesLearned, skipped, alreadyOwned, acquiredAtUpdated);
  }

  /**
   * Persists an alias for a manual resolution unless one already exists for that name,
   * case-insensitively (REQ-INV-020).
   *
   * @param ownerUserId the {@code app_user.id} stamped as creator
   * @param externalName the external name being resolved (trimmed)
   * @param product the chosen product
   * @param aliasNamesSeen lower-cased names already aliased in this request (mutated)
   * @return {@code true} if a new alias row was persisted
   */
  private boolean learnAliasIfManual(
      @NotNull UUID ownerUserId,
      @NotNull String externalName,
      @NotNull ResolvedProduct product,
      @NotNull Set<String> aliasNamesSeen) {
    if (externalName.isEmpty() || normalizer.normalize(externalName).equals(product.productKey())) {
      return false;
    }
    if (!aliasNamesSeen.add(externalName.toLowerCase(Locale.ROOT))
        || aliasRepository
            .findBySourceSystemAndExternalNameIgnoreCase(SOURCE, externalName)
            .isPresent()) {
      return false;
    }
    BlueprintExternalAlias alias = new BlueprintExternalAlias();
    alias.setSourceSystem(SOURCE);
    alias.setExternalName(externalName);
    alias.setProductKey(product.productKey());
    alias.setProductName(product.productName());
    if (product.outputItemId() != null) {
      alias.setOutputItem(gameItemRepository.getReferenceById(product.outputItemId()));
    }
    alias.setCreatedBy(ownerUserId.toString());
    aliasRepository.save(alias);
    log.info(
        "Learned blueprint alias: external='{}' -> productKey='{}' by={}",
        externalName,
        product.productKey(),
        ownerUserId);
    return true;
  }

  /**
   * Resolves one entry by structural tag (REQ-INV-019), exact name, alias, then fuzzy match; the
   * owned flag is applied later by the caller.
   *
   * @param entry the parsed name, tag and acquisition suggestion
   * @param productByKey master products by normalized key
   * @param allProducts master products as the fuzzy candidate set
   * @param tagIndex lower-cased {@code scwiki_key} to normalized product key
   * @return the resolution (status, product, suggestions)
   */
  @NotNull
  private Resolution resolve(
      @NotNull BlueprintExportParser.ParsedEntry entry,
      @NotNull Map<String, ResolvedProduct> productByKey,
      @NotNull List<ResolvedProduct> allProducts,
      @NotNull Map<String, String> tagIndex) {
    ResolvedProduct viaTag = resolveViaTag(entry.tag(), productByKey, tagIndex);
    if (viaTag != null) {
      return new Resolution(
          entry.externalName(),
          BlueprintImportStatus.MATCHED,
          viaTag,
          entry.suggestedAcquiredAt(),
          List.of());
    }

    String normalized = normalizer.normalize(entry.externalName());

    ResolvedProduct exact = productByKey.get(normalized);
    if (exact != null) {
      return new Resolution(
          entry.externalName(),
          BlueprintImportStatus.MATCHED,
          exact,
          entry.suggestedAcquiredAt(),
          List.of());
    }

    ResolvedProduct viaAlias = resolveViaAlias(entry.externalName(), productByKey);
    if (viaAlias != null) {
      return new Resolution(
          entry.externalName(),
          BlueprintImportStatus.MATCHED_BY_ALIAS,
          viaAlias,
          entry.suggestedAcquiredAt(),
          List.of());
    }

    List<BlueprintImportSuggestionDto> suggestions =
        fuzzyMatcher.topSuggestions(
            normalized,
            allProducts,
            BlueprintFuzzyMatcher.DEFAULT_LIMIT,
            BlueprintFuzzyMatcher.DEFAULT_THRESHOLD);
    return new Resolution(
        entry.externalName(),
        suggestions.isEmpty() ? BlueprintImportStatus.UNMATCHED : BlueprintImportStatus.SUGGESTED,
        null,
        entry.suggestedAcquiredAt(),
        suggestions);
  }

  /**
   * Resolves a structural {@code tag} case-insensitively to a master product (REQ-INV-019).
   *
   * @param tag the raw structural blueprint key, or {@code null}
   * @param productByKey master products by normalized key
   * @param tagIndex lower-cased {@code scwiki_key} to normalized product key
   * @return the product, or {@code null} when the tag is absent, unknown, ambiguous or unresolvable
   */
  @Nullable
  private ResolvedProduct resolveViaTag(
      @Nullable String tag,
      @NotNull Map<String, ResolvedProduct> productByKey,
      @NotNull Map<String, String> tagIndex) {
    if (tag == null || tag.isBlank()) {
      return null;
    }
    String productKey = tagIndex.get(tag.trim().toLowerCase(Locale.ROOT));
    if (productKey == null) {
      return null;
    }
    return productByKey.get(productKey);
  }

  /**
   * Resolves a curated alias for the external name to a master product, falling back to the alias's
   * own snapshot when the product key is no longer in the master list.
   *
   * @param externalName the raw external name
   * @param productByKey master products by normalized key
   * @return the resolved product, or {@code null} if no alias exists
   */
  @Nullable
  private ResolvedProduct resolveViaAlias(
      @NotNull String externalName, @NotNull Map<String, ResolvedProduct> productByKey) {
    Optional<BlueprintExternalAlias> alias =
        aliasRepository.findBySourceSystemAndExternalNameIgnoreCase(SOURCE, externalName);
    if (alias.isEmpty()) {
      return null;
    }
    BlueprintExternalAlias a = alias.get();
    ResolvedProduct fromMaster = productByKey.get(a.getProductKey());
    if (fromMaster != null) {
      return fromMaster;
    }
    return new ResolvedProduct(
        a.getProductKey(),
        a.getProductName(),
        a.getOutputItem() == null ? null : a.getOutputItem().getId());
  }

  /**
   * Builds the master product index keyed by normalized product key, in master-scan order.
   *
   * @return the product index
   */
  @NotNull
  private Map<String, ResolvedProduct> productIndex() {
    Map<String, ResolvedProduct> map = new LinkedHashMap<>();
    for (ResolvedProduct product : blueprintProductService.allProducts()) {
      map.putIfAbsent(product.productKey(), product);
    }
    return map;
  }

  /**
   * Returns the subset of {@code keys} the owner already owns via a single bulk lookup.
   *
   * @param ownerUserId {@code app_user.id} of the owner
   * @param keys the product keys to test
   * @return the owned product keys (empty if {@code keys} is empty)
   */
  @NotNull
  private Set<String> ownedKeys(@NotNull UUID ownerUserId, @NotNull Set<String> keys) {
    return new HashSet<>(ownedByKey(ownerUserId, keys).keySet());
  }

  /**
   * Loads the owner's managed blueprint rows for the given product keys in one query.
   *
   * @param ownerUserId the owner's {@code app_user.id}
   * @param keys the product keys to load
   * @return owned rows by product key (empty if {@code keys} is empty)
   */
  @NotNull
  private Map<String, PersonalBlueprint> ownedByKey(
      @NotNull UUID ownerUserId, @NotNull Set<String> keys) {
    if (keys.isEmpty()) {
      return new HashMap<>();
    }
    Map<String, PersonalBlueprint> owned = new HashMap<>();
    for (PersonalBlueprint pb :
        personalBlueprintRepository.findAllByOwnerUserIdAndProductKeyIn(
            ownerUserId, new ArrayList<>(keys))) {
      owned.putIfAbsent(pb.getProductKey(), pb);
    }
    return owned;
  }

  /**
   * Decides whether a stored acquisition time is replaced: only by a non-null incoming value that
   * is earlier or fills a stored {@code null}.
   *
   * @param incoming the imported acquisition instant, or {@code null}
   * @param existing the stored instant, or {@code null}
   * @return {@code true} if {@code existing} should be replaced with {@code incoming}
   */
  private boolean isEarlierAcquiredAt(@Nullable Instant incoming, @Nullable Instant existing) {
    if (incoming == null) {
      return false;
    }
    return existing == null || incoming.isBefore(existing);
  }

  /**
   * Builds a new, unsaved owned-blueprint entity stamped with the resolved product, attaching the
   * output item as a lazy reference when the product carries one.
   *
   * @param ownerUserId {@code app_user.id} of the owner
   * @param product the chosen product to stamp
   * @param acquiredAt optional acquisition time
   * @param note optional note
   * @return the new transient entity
   */
  @NotNull
  private PersonalBlueprint newOwned(
      @NotNull UUID ownerUserId,
      @NotNull ResolvedProduct product,
      @Nullable Instant acquiredAt,
      @Nullable String note) {
    PersonalBlueprint entity = new PersonalBlueprint();
    entity.setOwnerUserId(ownerUserId);
    entity.setProductKey(product.productKey());
    entity.setProductName(product.productName());
    if (product.outputItemId() != null) {
      entity.setOutputItem(gameItemRepository.getReferenceById(product.outputItemId()));
    }
    entity.setAcquiredAt(acquiredAt);
    entity.setNote(note);
    return entity;
  }

  /**
   * Per-name resolution carried between the two preview passes.
   *
   * @param externalName the external name
   * @param status the chain outcome before the owned-flag override
   * @param product the resolved product, or {@code null}
   * @param suggestedAcquiredAt the acquisition suggestion
   * @param suggestions fuzzy candidates (empty unless {@code status} is SUGGESTED)
   */
  private record Resolution(
      @NotNull String externalName,
      @NotNull BlueprintImportStatus status,
      @Nullable ResolvedProduct product,
      @Nullable Instant suggestedAcquiredAt,
      @NotNull List<BlueprintImportSuggestionDto> suggestions) {}
}
