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
 * SCMDB blueprint import engine (#327, Phase 4). Two steps:
 *
 * <ol>
 *   <li>{@link #previewImport(String, MultipartFile)} parses an uploaded blueprint export — the
 *       SCMDB log-watcher, the <a
 *       href="https://github.com/krt-profit/basetool-bp-extractor">Basetool Blueprint
 *       Extractor</a>, or the <a href="https://scmdb.net">scmdb.net</a> profile / tracking export
 *       (REQ-INV-014), all of which carry a {@code blueprints} array — de-duplicates by product
 *       name, and resolves each entry against the master product list through a fixed chain: first
 *       the scmdb.net structural {@code tag} match (REQ-INV-019), then normalized exact name match,
 *       then a curated {@code blueprint_external_alias} lookup, then dependency-free fuzzy
 *       suggestions — flagging names the caller already owns. Nothing is persisted.
 *   <li>{@link #applyImport(String, List)} takes the user's per-name resolutions, creates the
 *       missing {@code personal_blueprint} rows, and — for every manual pick (where the name did
 *       not already match by normalization) — learns a {@code blueprint_external_alias} so the next
 *       import auto-resolves it. Re-importing an already-owned blueprint never inserts a duplicate
 *       (also guarded by the {@code (owner_sub, product_key)} unique constraint); it only pulls the
 *       stored acquisition time earlier when the import carries an earlier timestamp.
 * </ol>
 *
 * <p>The engine is {@code ownerSub}-parameterised and never reads the security context, so the
 * Phase 7 admin surface can drive an import on behalf of a target user.
 *
 * <p>Apply is bulk-safe per the CLAUDE.md detach-clear rule: it issues no {@code @Modifying
 * clearAutomatically} query, so saving one new row never detaches the siblings created earlier in
 * the same transaction — there is no second {@code @Version} bump and thus no spurious 409.
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
   * Parses an uploaded blueprint export (SCMDB log-watcher, Basetool Blueprint Extractor, or
   * scmdb.net profile / tracking export) and previews how each unique blueprint resolves against
   * the master product list for {@code ownerSub} — scmdb.net entries first try their structural
   * {@code tag}, then every entry falls through the name chain. No rows are persisted.
   *
   * @param ownerSub {@code app_user.id} the import is being previewed for (owned-flag computation)
   * @param file the uploaded blueprint export JSON
   * @return the preview with per-name rows and per-status counts
   * @throws BadRequestException if the file is empty, not valid JSON, or carries no blueprint array
   */
  @NotNull
  public BlueprintImportPreviewDto previewImport(
      @NotNull UUID ownerSub, @NotNull MultipartFile file) {
    List<BlueprintExportParser.ParsedEntry> parsed =
        BlueprintExportParser.parse(objectMapper, file);

    Map<String, ResolvedProduct> productByKey = productIndex();
    List<ResolvedProduct> allProducts = new ArrayList<>(productByKey.values());
    // The structural tag index is only consulted for entries carrying a tag (scmdb.net). Build it
    // lazily so the watcher / extractor / bare-array imports — which never carry a tag — pay no
    // extra active-blueprint scan, exactly as before this source was added.
    Map<String, String> tagIndex =
        parsed.stream().anyMatch(e -> e.tag() != null)
            ? blueprintProductService.scwikiKeyToProductKeyIndex()
            : Map.of();

    // First pass: resolve each name (without the owned check) and collect resolved keys.
    List<Resolution> resolutions = new ArrayList<>(parsed.size());
    Set<String> resolvedKeys = new HashSet<>();
    for (BlueprintExportParser.ParsedEntry entry : parsed) {
      Resolution resolution = resolve(entry, productByKey, allProducts, tagIndex);
      resolutions.add(resolution);
      if (resolution.product != null) {
        resolvedKeys.add(resolution.product.productKey());
      }
    }

    // Second pass: a single bulk lookup decides which resolved products are already owned.
    Set<String> ownedKeys = ownedKeys(ownerSub, resolvedKeys);

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
        default -> {
          /* exhaustive */
        }
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
        "Blueprint import preview for ownerSub={}: total={} matched={} alias={} suggested={}"
            + " unmatched={} alreadyOwned={}",
        ownerSub,
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
   * Applies the user's per-name resolutions: creates the missing owned-blueprint rows and learns an
   * alias for every manual pick. Blank choices and product keys that no longer resolve are skipped.
   * Repeated names / products within one request are de-duplicated, so re-submitting a preview is
   * idempotent.
   *
   * <p>An already-owned blueprint is never duplicated (also guarded by the {@code (owner_sub,
   * product_key)} unique constraint); re-importing it only pulls the stored acquisition time
   * earlier when the current import carries an earlier timestamp (a missing or later timestamp
   * leaves it untouched). That earlier value is written by mutating the managed entity and relying
   * on dirty-checking — no {@code save()} / {@code flush()} — per the CLAUDE.md concurrency rules.
   *
   * @param ownerSub {@code app_user.id} the rows are created for
   * @param resolutions the per-name decisions (see {@link BlueprintImportApplyRequest})
   * @return a summary of added / learned / skipped / already-owned counts
   */
  @Transactional
  @NotNull
  public BlueprintImportResultDto applyImport(
      @NotNull UUID ownerSub, @NotNull List<BlueprintImportResolutionDto> resolutions) {
    Map<String, ResolvedProduct> productByKey = productIndex();

    int added = 0;
    int aliasesLearned = 0;
    int skipped = 0;
    int alreadyOwned = 0;
    int acquiredAtUpdated = 0;
    Map<String, PersonalBlueprint> ownedByKey = ownedByKey(ownerSub, productByKey.keySet());
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

      if (learnAliasIfManual(ownerSub, externalName, product, aliasNamesSeen)) {
        aliasesLearned++;
      }

      PersonalBlueprint existing = ownedByKey.get(product.productKey());
      if (existing != null) {
        // Re-import of an already-owned blueprint: never insert a duplicate (also guarded by the
        // (owner_sub, product_key) unique constraint); only pull the acquisition time earlier when
        // this import carries an earlier timestamp. Mutating the managed entity relies on
        // dirty-checking — no save()/flush() — per the CLAUDE.md concurrency rules.
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
              newOwned(ownerSub, product, resolution.acquiredAt(), resolution.note())));
      added++;
    }

    log.info(
        "Blueprint import apply for ownerSub={}: added={} aliasesLearned={} skipped={}"
            + " alreadyOwned={} acquiredAtUpdated={}",
        ownerSub,
        added,
        aliasesLearned,
        skipped,
        alreadyOwned,
        acquiredAtUpdated);
    return new BlueprintImportResultDto(
        added, aliasesLearned, skipped, alreadyOwned, acquiredAtUpdated);
  }

  /**
   * Persists a {@code blueprint_external_alias} for a manual resolution — one where the external
   * name does not already normalize to the chosen product key — unless an alias for that name
   * (case-insensitively) already exists or was just created in this request.
   *
   * <p>The duplicate guard folds case on both sides — the in-request seen-set keys on the
   * lower-cased name and the DB check uses {@code findBySourceSystemAndExternalNameIgnoreCase} —
   * matching the case-insensitive resolution lookup and the {@code (source_system,
   * LOWER(external_name))} unique index (REQ-INV-020). This stops two case-only variants (which the
   * tag match REQ-INV-019 can route here for differently-cased display names) from inserting two
   * rows that the {@code Optional}-returning resolution lookup would then choke on.
   *
   * @param ownerSub {@code app_user.id} stamped as the alias creator
   * @param externalName the SCMDB / scmdb.net name being resolved (exact, trimmed)
   * @param product the chosen product
   * @param aliasNamesSeen lower-cased external names already aliased in this request (mutated)
   * @return {@code true} if a new alias row was persisted
   */
  private boolean learnAliasIfManual(
      @NotNull UUID ownerSub,
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
    alias.setCreatedBy(ownerSub.toString());
    aliasRepository.save(alias);
    log.info(
        "Learned blueprint alias: external='{}' -> productKey='{}' by={}",
        externalName,
        product.productKey(),
        ownerSub);
    return true;
  }

  /**
   * Resolves one parsed entry through the fixed chain (structural tag → exact name → alias →
   * fuzzy), without the owned-flag check which the caller applies afterwards from a single bulk
   * lookup.
   *
   * <p>The <strong>tag</strong> step (REQ-INV-019) runs first and only for the scmdb.net export,
   * which uniquely carries the DataForge blueprint key: it short-circuits to a {@link
   * BlueprintImportStatus#MATCHED} when the entry's {@code tag} maps (case-insensitively, and only
   * when unambiguous) to a known product key, bypassing the name match entirely. This makes the
   * scmdb.net import robust against the CIG-mislabeled {@code output_name}s the name match has to
   * correct for (REQ-INV-007) and against cosmetic-variant name drift. For the watcher / extractor
   * exports, which carry no {@code tag}, the step is a no-op and the name chain decides as before.
   *
   * @param entry the parsed external name + tag + acquisition suggestion
   * @param productByKey master products indexed by normalized key
   * @param allProducts master products as a list (fuzzy candidate set)
   * @param tagIndex structural key (lower-cased {@code scwiki_key}) → normalized product key
   * @return the resolution (status, resolved product, suggestions)
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
   * Resolves the scmdb.net structural {@code tag} (the DataForge blueprint key) to a master product
   * (REQ-INV-019): the tag is matched case-insensitively against the {@code scwiki_key} index and,
   * when it maps to a known product key, dereferenced to that product. Returns {@code null} when
   * the entry carries no tag (watcher / extractor exports), when the tag is unknown or ambiguous
   * (absent from the index — the index excludes ambiguous keys), or when the mapped product key no
   * longer resolves to a master product, so the caller falls back to the name chain.
   *
   * @param tag the raw structural blueprint key from the upload, or {@code null}
   * @param productByKey master products indexed by normalized product key
   * @param tagIndex structural key (lower-cased {@code scwiki_key}) → normalized product key
   * @return the resolved product, or {@code null} if the tag does not unambiguously resolve
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
   * Looks up a curated SCMDB alias for the raw external name and dereferences it to a master
   * product. Falls back to the alias's own name / output-item snapshot if the master no longer
   * carries that product key (a renamed-away product), so a learned alias never silently regresses
   * to unmatched.
   *
   * @param externalName the raw external name from the upload
   * @param productByKey master products indexed by normalized key
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
   * @param ownerSub {@code app_user.id} of the owner
   * @param keys the product keys to test
   * @return the owned product keys (empty if {@code keys} is empty)
   */
  @NotNull
  private Set<String> ownedKeys(@NotNull UUID ownerSub, @NotNull Set<String> keys) {
    return new HashSet<>(ownedByKey(ownerSub, keys).keySet());
  }

  /**
   * Loads the owner's existing blueprint rows for the given product keys, indexed by product key,
   * via a single bulk lookup. Backs {@link #applyImport}'s duplicate skip and earliest-acquisition
   * refresh; the returned entities are managed, so mutating one (e.g. its {@code acquiredAt}) is
   * flushed by dirty-checking without an explicit {@code save()}.
   *
   * @param ownerSub {@code app_user.id} of the owner
   * @param keys the product keys to load
   * @return owned rows indexed by product key (empty if {@code keys} is empty)
   */
  @NotNull
  private Map<String, PersonalBlueprint> ownedByKey(
      @NotNull UUID ownerSub, @NotNull Set<String> keys) {
    if (keys.isEmpty()) {
      return new HashMap<>();
    }
    Map<String, PersonalBlueprint> owned = new HashMap<>();
    for (PersonalBlueprint pb :
        personalBlueprintRepository.findAllByOwnerSubAndProductKeyIn(
            ownerSub, new ArrayList<>(keys))) {
      owned.putIfAbsent(pb.getProductKey(), pb);
    }
    return owned;
  }

  /**
   * Decides whether an owned row's acquisition time should be pulled to an incoming import value:
   * only when the incoming value is present and is either earlier than the stored one or fills a
   * stored {@code null}. A {@code null} incoming never overwrites a stored value, so re-importing
   * an export that lacks a timestamp can never erase a known acquisition time.
   *
   * @param incoming the acquisition instant from the current import, or {@code null}
   * @param existing the instant currently stored on the owned row, or {@code null}
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
   * @param ownerSub {@code app_user.id} of the owner
   * @param product the chosen product to stamp
   * @param acquiredAt optional acquisition time
   * @param note optional note
   * @return the new transient entity
   */
  @NotNull
  private PersonalBlueprint newOwned(
      @NotNull UUID ownerSub,
      @NotNull ResolvedProduct product,
      @Nullable Instant acquiredAt,
      @Nullable String note) {
    PersonalBlueprint entity = new PersonalBlueprint();
    entity.setOwnerSub(ownerSub);
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
   * Intermediate per-name resolution carried between the two preview passes. Mutable status is not
   * needed — the owned-flag override is applied when building the response DTO.
   *
   * @param externalName the SCMDB name
   * @param status the chain outcome before the owned-flag override
   * @param product the resolved product, or {@code null}
   * @param suggestedAcquiredAt acquisition suggestion from {@code ts}
   * @param suggestions fuzzy candidates (empty unless {@code status} is SUGGESTED)
   */
  private record Resolution(
      @NotNull String externalName,
      @NotNull BlueprintImportStatus status,
      @Nullable ResolvedProduct product,
      @Nullable Instant suggestedAcquiredAt,
      @NotNull List<BlueprintImportSuggestionDto> suggestions) {}
}
