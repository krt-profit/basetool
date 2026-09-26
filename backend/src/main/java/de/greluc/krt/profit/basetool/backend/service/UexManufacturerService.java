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

import de.greluc.krt.profit.basetool.backend.dto.uex.UexCompanyDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.ManufacturerUexCompany;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerUexCompanyRepository;
import de.greluc.krt.profit.basetool.backend.support.UexValues;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Imports UEX Corp's {@code /companies} catalogue into {@code manufacturer}, merging the duplicate
 * companies UEX ships for one brand onto a single row (ADR-0023).
 *
 * <p>The lowest company id of a brand owns the row's identity; the others register as {@link
 * ManufacturerUexCompany} aliases and only add their manufacturer-surface flags. Each company is
 * upserted in its own transaction (REQ-DATA-004); an empty response leaves local data untouched.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UexManufacturerService {

  /**
   * Maximum length of an upstream company name / nickname in log lines, logged through {@link
   * LogSafe}.
   */
  private static final int MAX_NAME_LOG_LENGTH = 64;

  private final UexClient uexClient;
  private final ManufacturerRepository manufacturerRepository;
  private final ManufacturerUexCompanyRepository aliasRepository;

  /**
   * Self-reference, resolved lazily so each per-company upsert runs through the Spring transaction
   * proxy. Calling {@link #upsertCompanyWithinTransaction(UexCompanyDto, Instant)} via {@code this}
   * would be self-invocation and run in the caller's (here absent) transaction instead of opening
   * the {@code REQUIRES_NEW} one, defeating the per-company isolation.
   */
  private final ObjectProvider<UexManufacturerService> self;

  /**
   * Outcome of a single per-company upsert, used only for the run summary counters. Package-private
   * (not {@code private}) so the CGLIB {@code @Transactional} proxy — generated in this package but
   * not a nestmate — can reference it as the return type of the public proxied method.
   */
  enum UpsertOutcome {
    /** A brand-new canonical manufacturer row was inserted. */
    CREATED,
    /** An existing canonical row was refreshed by its owning company. */
    UPDATED,
    /** A duplicate company was merged into an existing brand row as an alias. */
    ALIASED
  }

  /**
   * Pulls the company catalogue and upserts every company in ascending id order, each in its own
   * transaction, so a brand's canonical company is persisted before its aliases.
   */
  public void syncManufacturers() {
    log.info("Starting synchronization of UEX manufacturers...");
    UexClient.FetchResult<UexCompanyDto> fetched = uexClient.getCompanies();
    if (fetched.notModified()) {
      log.info("UEX company catalogue unchanged since the last sync (304) — nothing to import.");
      return;
    }
    List<UexCompanyDto> companies = fetched.data();
    if (companies.isEmpty()) {
      log.warn("No companies received from UEX API. Aborting synchronization.");
      return;
    }

    List<UexCompanyDto> ordered =
        companies.stream()
            .sorted(Comparator.comparing(UexCompanyDto::id, Comparator.nullsLast(Integer::compare)))
            .toList();

    Instant now = Instant.now();
    int added = 0;
    int updated = 0;
    int aliased = 0;
    int skipped = 0;
    for (UexCompanyDto dto : ordered) {
      if (!StringUtils.hasText(dto.name())) {
        log.debug(
            "Skipping company with missing name (id={}, nickname='{}')",
            dto.id(),
            LogSafe.text(dto.nickname(), MAX_NAME_LOG_LENGTH));
        skipped++;
        continue;
      }
      try {
        UpsertOutcome outcome = self.getObject().upsertCompanyWithinTransaction(dto, now);
        if (outcome == UpsertOutcome.CREATED) {
          added++;
        } else if (outcome == UpsertOutcome.UPDATED) {
          updated++;
        } else {
          aliased++;
        }
      } catch (Exception e) {
        log.error(
            "Failed to process UEX company dto (id={}, name='{}')",
            dto.id(),
            LogSafe.text(dto.name(), MAX_NAME_LOG_LENGTH),
            e);
        skipped++;
      }
    }
    log.info(
        "Finished UEX manufacturer sync: {} added, {} updated, {} aliased (merged duplicates),"
            + " {} skipped",
        added,
        updated,
        aliased,
        skipped);
  }

  /**
   * Upserts one UEX company in its own {@code REQUIRES_NEW} transaction, merging duplicates of a
   * brand onto one manufacturer row (ADR-0023, REQ-DATA-004). Must be invoked through the {@link
   * #self} proxy.
   *
   * <p>Matches by alias id, then name, then oldest abbreviation. The company owns the row's
   * identity when the row is new, unclaimed or already stamped with its id; otherwise it registers
   * as an alias and refreshes only the surface flags.
   *
   * @param dto inbound UEX row
   * @param now timestamp to stamp on the row
   * @return whether the row was created, updated as canonical, or merged as an alias
   */
  @NotNull
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UpsertOutcome upsertCompanyWithinTransaction(@NotNull UexCompanyDto dto, Instant now) {
    String abbreviation = StringUtils.hasText(dto.nickname()) ? dto.nickname() : dto.name();

    Manufacturer manufacturer = resolveManufacturer(dto, abbreviation);
    final boolean isNew = manufacturer == null;
    if (isNew) {
      manufacturer = new Manufacturer();
    }

    final boolean canonical =
        isNew
            || manufacturer.getUexCompanyId() == null
            || (dto.id() != null && dto.id().equals(manufacturer.getUexCompanyId()));

    if (canonical) {
      applyCanonicalFields(manufacturer, dto, abbreviation);
    } else {
      manufacturer.setIsItemManufacturer(
          orFlag(
              manufacturer.getIsItemManufacturer(),
              UexValues.asBooleanOrFalse(dto.isItemManufacturer())));
      manufacturer.setIsVehicleManufacturer(
          orFlag(
              manufacturer.getIsVehicleManufacturer(),
              UexValues.asBooleanOrFalse(dto.isVehicleManufacturer())));
    }

    manufacturer.setUexSyncedAt(now);
    manufacturer.setUexDeletedAt(null);
    manufacturer = manufacturerRepository.save(manufacturer);

    registerAlias(dto.id(), manufacturer);

    if (isNew) {
      return UpsertOutcome.CREATED;
    }
    return canonical ? UpsertOutcome.UPDATED : UpsertOutcome.ALIASED;
  }

  /**
   * Runs the alias-by-id → name → abbreviation match chain.
   *
   * @param dto inbound UEX row
   * @param abbreviation derived short code (nickname, falling back to name)
   * @return the existing manufacturer this company belongs to, or {@code null} if none matched
   */
  private Manufacturer resolveManufacturer(UexCompanyDto dto, String abbreviation) {
    if (dto.id() != null) {
      Optional<Manufacturer> byAlias = aliasRepository.findManufacturerByUexCompanyId(dto.id());
      if (byAlias.isPresent()) {
        return byAlias.get();
      }
    }
    Optional<Manufacturer> byName = manufacturerRepository.findByNameIgnoreCase(dto.name());
    if (byName.isPresent()) {
      return byName.get();
    }
    return manufacturerRepository
        .findFirstByAbbreviationIgnoreCaseOrderByCreatedAtAsc(abbreviation)
        .orElse(null);
  }

  /**
   * Writes the canonical display identity + UEX cross-reference columns onto the row. Only the
   * canonical company of a brand calls this; duplicates leave these fields untouched.
   *
   * @param manufacturer the row being stamped
   * @param dto inbound UEX row
   * @param abbreviation derived short code
   */
  private static void applyCanonicalFields(
      @NotNull Manufacturer manufacturer, @NotNull UexCompanyDto dto, String abbreviation) {
    manufacturer.setName(dto.name());
    manufacturer.setAbbreviation(abbreviation);
    if (StringUtils.hasText(dto.nickname())) {
      manufacturer.setNickname(dto.nickname());
    }
    if (StringUtils.hasText(dto.wiki())) {
      manufacturer.setWiki(dto.wiki());
    }
    StringBuilder description = new StringBuilder();
    if (StringUtils.hasText(dto.industry())) {
      description.append("Industry: ").append(dto.industry()).append("\n");
    }
    if (StringUtils.hasText(dto.wiki())) {
      description.append("Wiki: ").append(dto.wiki());
    }
    if (!description.isEmpty()) {
      manufacturer.setDescription(description.toString().trim());
    }
    manufacturer.setUexCompanyId(dto.id());
    manufacturer.setIndustry(dto.industry());
    manufacturer.setIsItemManufacturer(UexValues.asBooleanOrFalse(dto.isItemManufacturer()));
    manufacturer.setIsVehicleManufacturer(UexValues.asBooleanOrFalse(dto.isVehicleManufacturer()));
  }

  /**
   * Maps this company's UEX id to the resolved manufacturer in the alias table (idempotent upsert).
   * No-op when the company carries no id.
   *
   * @param uexCompanyId UEX integer company id (may be {@code null})
   * @param manufacturer the manufacturer the id resolves to
   */
  private void registerAlias(Integer uexCompanyId, Manufacturer manufacturer) {
    if (uexCompanyId == null) {
      return;
    }
    ManufacturerUexCompany alias =
        aliasRepository
            .findById(uexCompanyId)
            .orElseGet(() -> new ManufacturerUexCompany(uexCompanyId, manufacturer));
    alias.setManufacturer(manufacturer);
    aliasRepository.save(alias);
  }

  /**
   * Combines an existing flag with an inbound one, treating {@code null} as {@code false}.
   *
   * @param existing the flag already on the row (may be {@code null})
   * @param incoming the flag from the inbound DTO (may be {@code null})
   * @return {@code true} if either side is {@code true}
   */
  private static Boolean orFlag(Boolean existing, Boolean incoming) {
    return Boolean.TRUE.equals(existing) || Boolean.TRUE.equals(incoming);
  }
}
