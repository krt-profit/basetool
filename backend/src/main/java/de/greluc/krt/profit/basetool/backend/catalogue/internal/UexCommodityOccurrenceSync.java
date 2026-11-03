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

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.UexKeyRef;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexCommodityDto;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Replaces each material's set of {@link MaterialOccurrence}s with the locations the UEX commodity
 * catalogue lists for it (REQ-DATA-031).
 *
 * <p>Runs after the commodity upsert, so every commodity of the feed resolves to a material, and
 * after the universe sync, so the location ids resolve; an id with no local location row is
 * skipped. Each chunk diffs against the stored rows: rows still listed stay, rows no longer listed
 * are deleted, new ones are inserted. A material the feed does not carry keeps its rows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UexCommodityOccurrenceSync {

  private final MaterialRepository materialRepository;
  private final MaterialOccurrenceRepository occurrenceRepository;
  private final StarSystemRepository starSystemRepository;
  private final PlanetRepository planetRepository;
  private final MoonRepository moonRepository;
  private final OrbitRepository orbitRepository;
  private final PoiRepository poiRepository;

  /** Writes the rows in short isolated transactions. */
  private final SyncChunkWriter chunkWriter;

  /**
   * What the sync did for one commodity.
   *
   * @param kept stored occurrences still listed
   * @param inserted occurrences added
   * @param deleted stored occurrences no longer listed
   * @param unresolved listed ids with no local location row
   */
  record RowResult(int kept, int inserted, int deleted, int unresolved) {}

  /**
   * Replaces the occurrence sets of every commodity in {@code commodities}. Holds no transaction of
   * its own: the lookup read and each chunk commit separately.
   *
   * @param commodities the commodity catalogue of one complete fetch
   */
  public void replaceAll(@NotNull List<UexCommodityDto> commodities) {
    Lookups lookups = chunkWriter.inNewTransaction(this::loadLookups);
    SyncChunkWriter.Outcome<RowResult> outcome =
        chunkWriter.write(
            commodities,
            SyncChunkWriter.DEFAULT_CHUNK_SIZE,
            chunk -> writeChunk(chunk, lookups),
            "commodity location",
            dto -> "(id=" + dto.id() + ")");
    int kept = 0;
    int inserted = 0;
    int deleted = 0;
    int unresolved = 0;
    for (RowResult row : outcome.results()) {
      kept += row.kept();
      inserted += row.inserted();
      deleted += row.deleted();
      unresolved += row.unresolved();
    }
    log.info(
        "Finished commodity location synchronization: {} kept, {} added, {} removed, {} unknown"
            + " location id(s) skipped ({} commodity row(s) failed).",
        kept,
        inserted,
        deleted,
        unresolved,
        outcome.failedRows());
  }

  /**
   * Reads the material map and the five location maps, one query each.
   *
   * @return the lookups, holding ids only
   */
  @NotNull
  private Lookups loadLookups() {
    Map<MaterialOccurrenceKind, Map<Integer, UUID>> locations =
        new EnumMap<>(MaterialOccurrenceKind.class);
    for (MaterialOccurrenceKind kind : MaterialOccurrenceKind.values()) {
      List<UexKeyRef> refs =
          switch (kind) {
            case STAR_SYSTEM -> starSystemRepository.findUexRefs();
            case PLANET -> planetRepository.findUexRefs();
            case MOON -> moonRepository.findUexRefs();
            case ORBIT -> orbitRepository.findUexRefs();
            case POI -> poiRepository.findUexRefs();
          };
      locations.put(kind, UexMatrixLookups.byUexId(refs));
    }
    return new Lookups(
        UexMatrixLookups.byUexId(materialRepository.findUexCommodityRefs()), locations);
  }

  /**
   * Diffs and writes one chunk inside its transaction.
   *
   * @param chunk the commodity rows of this chunk
   * @param lookups the preloaded id maps
   * @return one result per commodity whose material resolved
   */
  @NotNull
  private List<RowResult> writeChunk(
      @NotNull List<UexCommodityDto> chunk, @NotNull Lookups lookups) {
    Map<UUID, UexCommodityDto> byMaterial = new HashMap<>();
    for (UexCommodityDto dto : chunk) {
      UUID materialId = dto.id() == null ? null : lookups.materials().get(dto.id());
      if (materialId != null) {
        byMaterial.put(materialId, dto);
      }
    }
    if (byMaterial.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<MaterialOccurrence>> stored =
        occurrenceRepository.findByMaterialIds(byMaterial.keySet()).stream()
            .collect(Collectors.groupingBy(o -> o.getMaterial().getId()));

    List<MaterialOccurrence> toDelete = new ArrayList<>();
    List<MaterialOccurrence> toInsert = new ArrayList<>();
    List<RowResult> results = new ArrayList<>();
    for (Map.Entry<UUID, UexCommodityDto> entry : byMaterial.entrySet()) {
      UUID materialId = entry.getKey();
      Resolved wanted = resolve(entry.getValue(), lookups);
      Set<MaterialOccurrenceTarget> missing = new LinkedHashSet<>(wanted.targets());
      int kept = 0;
      int deleted = 0;
      for (MaterialOccurrence occurrence : stored.getOrDefault(materialId, List.of())) {
        if (missing.remove(occurrence.target())) {
          kept++;
        } else {
          toDelete.add(occurrence);
          deleted++;
        }
      }
      for (MaterialOccurrenceTarget target : missing) {
        toInsert.add(newOccurrence(materialId, target));
      }
      results.add(new RowResult(kept, missing.size(), deleted, wanted.unresolved()));
    }
    if (!toDelete.isEmpty()) {
      occurrenceRepository.deleteAllInBatch(toDelete);
    }
    occurrenceRepository.saveAll(toInsert);
    return results;
  }

  /**
   * Resolves the five UEX id lists of one commodity to local location targets.
   *
   * @param dto the commodity row
   * @param lookups the preloaded id maps
   * @return the targets in list order, and how many ids did not resolve
   */
  @NotNull
  private static Resolved resolve(@NotNull UexCommodityDto dto, @NotNull Lookups lookups) {
    Set<MaterialOccurrenceTarget> targets = new LinkedHashSet<>();
    int unresolved = 0;
    for (MaterialOccurrenceKind kind : MaterialOccurrenceKind.values()) {
      Map<Integer, UUID> known = lookups.locations().getOrDefault(kind, Map.of());
      for (Integer uexId : UexValues.parseIdList(idList(dto, kind))) {
        UUID locationId = known.get(uexId);
        if (locationId == null) {
          unresolved++;
        } else {
          targets.add(new MaterialOccurrenceTarget(kind, locationId));
        }
      }
    }
    return new Resolved(targets, unresolved);
  }

  /**
   * The raw UEX id list a commodity carries for one kind of location.
   *
   * @param dto the commodity row
   * @param kind the kind of location
   * @return the comma-separated ids, or {@code null} when UEX sent none
   */
  @Nullable
  private static String idList(@NotNull UexCommodityDto dto, @NotNull MaterialOccurrenceKind kind) {
    return switch (kind) {
      case STAR_SYSTEM -> dto.idsStarSystems();
      case PLANET -> dto.idsPlanets();
      case MOON -> dto.idsMoons();
      case ORBIT -> dto.idsOrbits();
      case POI -> dto.idsPoi();
    };
  }

  /**
   * Builds an unsaved occurrence pointing at {@code target}, using references, not loaded rows.
   *
   * @param materialId the local material id
   * @param target the location to point at
   * @return the new occurrence
   */
  @NotNull
  private MaterialOccurrence newOccurrence(
      @NotNull UUID materialId, @NotNull MaterialOccurrenceTarget target) {
    MaterialOccurrence occurrence = new MaterialOccurrence();
    occurrence.setMaterial(materialRepository.getReferenceById(materialId));
    UUID id = target.locationId();
    switch (target.kind()) {
      case STAR_SYSTEM -> occurrence.setStarSystem(starSystemRepository.getReferenceById(id));
      case PLANET -> occurrence.setPlanet(planetRepository.getReferenceById(id));
      case MOON -> occurrence.setMoon(moonRepository.getReferenceById(id));
      case ORBIT -> occurrence.setOrbit(orbitRepository.getReferenceById(id));
      case POI -> occurrence.setPoi(poiRepository.getReferenceById(id));
      case null -> throw new IllegalArgumentException("Occurrence target without a kind");
    }
    return occurrence;
  }

  /**
   * The id maps one run resolves against.
   *
   * @param materials material id by {@code id_commodity}
   * @param locations location id by UEX id, per kind
   */
  private record Lookups(
      @NotNull Map<Integer, UUID> materials,
      @NotNull Map<MaterialOccurrenceKind, Map<Integer, UUID>> locations) {}

  /**
   * One commodity's resolved location list.
   *
   * @param targets the resolved locations, in list order
   * @param unresolved how many listed ids had no local row
   */
  private record Resolved(@NotNull Set<MaterialOccurrenceTarget> targets, int unresolved) {}
}
