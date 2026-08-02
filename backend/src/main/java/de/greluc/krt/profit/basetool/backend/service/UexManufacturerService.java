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
import de.greluc.krt.profit.basetool.backend.support.LogSafe;
import de.greluc.krt.profit.basetool.backend.support.UexValues;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Imports UEX Corp's {@code /companies} catalogue into the local {@code manufacturer} table.
 *
 * <p><strong>UEX ships duplicate companies for one brand.</strong> The same real-world manufacturer
 * appears as several distinct {@code /companies} records with different ids and frequently
 * different names — observed in prod: {@code 87 "Esperia"} (the item-side record) and {@code 278
 * "Esperia Incorporation"} (the vehicle-side record); {@code 70 "Denim Manufacture Corporation"} /
 * {@code 287 "DMC"}; {@code 62 "Covalex Shipping"} / {@code 293 "Covalex"}. They are the same
 * brand, so they must collapse onto <em>one</em> manufacturer row — otherwise the brand's ships and
 * items split across two rows (the item sync resolves by {@code id_company}, the vehicle sync by
 * {@code id_company}, but the two surfaces reference different ids for the same brand).
 *
 * <p><strong>Merge model (ADR-0023, REQ-DATA-004).</strong> A manufacturer may own several UEX
 * company ids via the {@link ManufacturerUexCompany} alias table. The <em>canonical</em> company of
 * a brand — the lowest {@code uex_company_id}, since the feed is processed in ascending-id order —
 * owns the row's display identity ({@code name} / {@code abbreviation} / {@code uex_company_id}).
 * Every other company of the same brand resolves to that row, registers its id as an alias and only
 * <em>OR</em>s the {@code is_item_manufacturer} / {@code is_vehicle_manufacturer} flags so the
 * surviving row serves both surfaces; it never hijacks the canonical identity, which is what keeps
 * the result ping-pong-free across runs.
 *
 * <p>Match chain per company: alias-by-{@code id} → {@code byNameIgnoreCase} → {@code
 * byAbbreviation} (oldest row). The first company of a brand creates the row (canonical); the rest
 * adopt it as aliases. A row whose {@code uex_company_id} is still {@code null} (a legacy
 * hand-seeded or P4K-only row) is adopted as canonical by the first UEX company that matches it by
 * name or abbreviation.
 *
 * <p><strong>Per-company isolation.</strong> Each company is upserted in its own {@code
 * REQUIRES_NEW} transaction via {@link #upsertCompanyWithinTransaction(UexCompanyDto, Instant)},
 * invoked through the {@link #self} proxy (a direct {@code this} call would be self-invocation and
 * silently skip the new transaction — the CLAUDE.md self-invocation trap). A row that violates a
 * constraint rolls back only itself; the {@code catch} in the loop counts it as skipped and the
 * remaining companies still commit (REQ-DATA-004). Because the canonical company is always the
 * lowest id and the feed is processed ascending, the canonical row is committed before any of its
 * aliases are processed.
 *
 * <p>Empty UEX response short-circuits without wiping local data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UexManufacturerService {

  /**
   * Cap for the upstream-supplied company name / nickname in log lines. UEX is a third party we do
   * not control, so both are untrusted free text and go through {@link LogSafe} first; 64
   * characters comfortably fit any real brand name.
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
   * Pulls the company catalogue and upserts every row, each in its own {@code REQUIRES_NEW}
   * transaction so one constraint violation cannot roll back the whole sweep. Companies are
   * processed in ascending {@code id} order so the canonical (lowest-id) company of every brand is
   * persisted before the duplicates that alias onto it. Deliberately not {@code @Transactional}:
   * the HTTP fetch and the loop hold no transaction, and the only writes happen inside the
   * per-company nested transactions. Counter summary at INFO when done.
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

    // Ascending id order makes the lowest-id (canonical) company of each brand commit before the
    // duplicates that alias onto it — the invariant the merge logic relies on. Null ids (if any)
    // sort last; they cannot be canonical-by-id anyway.
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
        // The failed company's own REQUIRES_NEW transaction has rolled back; the outer sweep is
        // untouched, so we just tally it and carry on with the next company.
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
   * Upserts a single UEX company DTO in its own {@code REQUIRES_NEW} transaction, merging duplicate
   * companies of the same brand onto one manufacturer row (ADR-0023 / REQ-DATA-004).
   *
   * <p>Resolution chain: alias-by-{@code id} → {@code byNameIgnoreCase} → oldest {@code
   * byAbbreviation}. The company is treated as the row's <em>canonical</em> owner when the row is
   * brand new, still unclaimed ({@code uex_company_id IS NULL}, a legacy seed), or already stamped
   * with this very company id; otherwise it is a duplicate that adopts the existing row as an alias
   * — refreshing only the manufacturer-surface flags, never the canonical name / abbreviation /
   * {@code uex_company_id}. In all cases this company's id is registered in the {@code
   * manufacturer_uex_company} alias table so the item and vehicle syncs resolve it to the surviving
   * row.
   *
   * <p>Running in a dedicated nested transaction means a failure here rolls back only this company
   * and never poisons the rest of the sweep; the caller's loop catches it and continues. Must be
   * invoked through the {@link #self} proxy — a direct {@code this} call would be self-invocation
   * and skip the new transaction.
   *
   * @param dto inbound UEX row
   * @param now timestamp to stamp on the row
   * @return whether the row was created, updated as canonical, or merged as an alias
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UpsertOutcome upsertCompanyWithinTransaction(UexCompanyDto dto, Instant now) {
    String abbreviation = StringUtils.hasText(dto.nickname()) ? dto.nickname() : dto.name();

    Manufacturer manufacturer = resolveManufacturer(dto, abbreviation);
    final boolean isNew = manufacturer == null;
    if (isNew) {
      manufacturer = new Manufacturer();
    }

    // The company owns the row's identity when the row is new, still unclaimed (legacy seed), or
    // already stamped with this exact company id. Otherwise it is a duplicate of the same brand and
    // must adopt the existing row without hijacking its canonical identity.
    final boolean canonical =
        isNew
            || manufacturer.getUexCompanyId() == null
            || (dto.id() != null && dto.id().equals(manufacturer.getUexCompanyId()));

    if (canonical) {
      applyCanonicalFields(manufacturer, dto, abbreviation);
    } else {
      // Duplicate company of an already-resolved brand: only widen the surfaces it serves.
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
      Manufacturer manufacturer, UexCompanyDto dto, String abbreviation) {
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
