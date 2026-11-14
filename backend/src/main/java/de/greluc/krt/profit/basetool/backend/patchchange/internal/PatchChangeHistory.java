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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kPatchVersionReader;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the patch-change history and what the admin's form starts from (REQ-PATCH-001,
 * REQ-PATCH-008), and maps a record to its history row.
 */
@Service
@RequiredArgsConstructor
public class PatchChangeHistory {

  private final PatchChangeRepository repository;
  private final P4kPatchVersionReader patchVersions;
  private final UserRepository userRepository;
  private final HomeLocationPhases phases;
  private final HomeLocationOffer offer;

  /**
   * Lists every patch change, newest live time first.
   *
   * @return the history rows
   */
  @Transactional(readOnly = true)
  public @NotNull @Unmodifiable List<PatchChangeDto> history() {
    List<PatchChange> records = repository.findAllByOrderByLiveAtDesc();
    Map<UUID, String> names =
        names(records.stream().map(PatchChange::getTriggeredBy).filter(Objects::nonNull).toList());
    UUID latestPrompt =
        records.stream()
            .filter(PatchChange::isHomeLocationPrompt)
            .map(PatchChange::getId)
            .findFirst()
            .orElse(null);
    return records.stream().map(record -> toDto(record, names, latestPrompt)).toList();
  }

  /**
   * Returns what a new patch change's form starts from: the label suggested by the latest P4K
   * import, the previous patch change's label and live time, the presets of each kind and the
   * curated home locations.
   *
   * @return the form's defaults
   */
  @Transactional(readOnly = true)
  public @NotNull PatchChangeFormDto form() {
    PatchChange previous = repository.findFirstByOrderByLiveAtDesc().orElse(null);
    return new PatchChangeFormDto(
        patchVersions.latestPatchVersion().orElse(null),
        previous == null ? null : previous.getLabel(),
        previous == null ? null : previous.getLiveAt(),
        Arrays.stream(PatchKind.values())
            .map(kind -> new PatchChangeFormDto.Preset(kind, PatchChangeOptions.presetFor(kind)))
            .toList(),
        offer.offered());
  }

  /**
   * Maps one record to its history row inside the caller's transaction.
   *
   * @param record the record, its wipe groups and counts readable
   * @return the history row
   */
  @NotNull
  PatchChangeDto toDto(@NotNull PatchChange record) {
    UUID admin = record.getTriggeredBy();
    return toDto(record, admin == null ? Map.of() : names(Set.of(admin)), phases.latestPromptId());
  }

  /**
   * Maps one record to its history row with the admins' names already read.
   *
   * @param record the record, its wipe groups and counts readable
   * @param names the display name by member id
   * @param latestPrompt the latest patch change with the prompt on, or {@code null}
   * @return the history row
   */
  private @NotNull PatchChangeDto toDto(
      @NotNull PatchChange record, @NotNull Map<UUID, String> names, @Nullable UUID latestPrompt) {
    UUID admin = record.getTriggeredBy();
    return new PatchChangeDto(
        record.getId(),
        Objects.requireNonNullElse(record.getVersion(), 0L),
        record.getLabel(),
        record.getKind(),
        record.getLiveAt(),
        record.getTriggeredAt(),
        admin == null ? null : names.get(admin),
        record.options(),
        record.counts().toDtos(),
        phases.phaseOf(record, latestPrompt));
  }

  /**
   * Reads the display names of the given members in one query.
   *
   * @param ids the members
   * @return the display name by id; a deleted member is absent
   */
  private @NotNull Map<UUID, String> names(@NotNull Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return userRepository.findAllById(Set.copyOf(ids)).stream()
        .collect(Collectors.toUnmodifiableMap(User::getId, User::getEffectiveName));
  }
}
