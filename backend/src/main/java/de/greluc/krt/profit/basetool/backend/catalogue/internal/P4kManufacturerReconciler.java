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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kManufacturerDto;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reconciles the catalogue's {@code manufacturers} with {@code manufacturer} (REQ-P4K-020,
 * REQ-P4K-021): match by P4K GUID, SC Wiki UUID and name, never by abbreviation; write the name,
 * the abbreviation from {@code code}, the German name and both descriptions the catalogue leads;
 * leave a name another row holds on the review list. Auxiliary records and reviewed aliases can
 * resolve references without leading the brand's fields; a brand's siblings resolve to its row, and
 * a record borrowing another brand's name never matches by it nor leads it (REQ-P4K-058).
 */
@Component
@RequiredArgsConstructor
public class P4kManufacturerReconciler {

  private final ManufacturerRepository manufacturerRepository;
  private final ManufacturerP4kAliasRepository p4kAliases;
  private final P4kRunResolvedCases resolvedCases;

  /**
   * Reconciles canonical manufacturer records and holds the unmatched ones for seeding.
   *
   * @param manufacturers the catalogue's manufacturers
   * @param context the run
   */
  public void reconcile(
      @NotNull List<P4kManufacturerDto> manufacturers, @NotNull P4kImportContext context) {
    List<Manufacturer> stored = manufacturerRepository.findAll();
    P4kRowIndex<Manufacturer> rows =
        new P4kRowIndex<>(stored, Manufacturer::getScwikiUuid, _ -> null, Manufacturer::getName);
    context.indexManufacturerRows(rows);
    Set<UUID> aliasGuids = new HashSet<>();
    for (ManufacturerP4kAlias alias : p4kAliases.findLinkedAliases()) {
      if (rows.byP4kUuid(alias.getP4kUuid()).isEmpty()) {
        aliasGuids.add(alias.getP4kUuid());
        context.indexManufacturer(alias.getP4kUuid(), alias.getManufacturer());
      }
    }
    List<P4kManufacturerDto> canonical =
        manufacturers.stream()
            .filter(record -> !P4kManufacturerRecordPath.isAuxiliary(record.recordPath()))
            .toList();
    P4kManufacturerFamilies families = P4kManufacturerFamilies.of(canonical);
    context.indexRepeated(
        P4kKind.MANUFACTURER,
        canonical.stream()
            .filter(record -> !aliasGuids.contains(record.parsedGuid()))
            .filter(record -> !families.isSibling(record.parsedGuid()))
            .filter(record -> families.lenderOf(record.parsedGuid()) == null)
            .toList());
    Set<UUID> auxiliaryGuids = new HashSet<>();
    Set<UUID> canonicalGuids = new HashSet<>();
    P4kRowMatcher<Manufacturer> matcher =
        new P4kRowMatcher<>(
            P4kKind.MANUFACTURER,
            rows::byP4kUuid,
            rows::byWikiUuid,
            Manufacturer::getScwikiUuid,
            "scwiki_uuid",
            _ -> List.of(),
            rows::byName,
            _ -> false,
            new P4kManufacturerRules(families, stored, rows::byP4kUuid));
    matcher.forEachMovesFirst(
        manufacturers,
        stored,
        context,
        manufacturerRepository::flush,
        manufacturer -> {
          UUID guid = manufacturer.parsedGuid();
          if (P4kManufacturerRecordPath.isAuxiliary(manufacturer.recordPath())) {
            if (guid != null) {
              auxiliaryGuids.add(guid);
            }
            observeAuxiliary(manufacturer, guid, rows, aliasGuids, context);
            return;
          }
          if (guid != null) {
            canonicalGuids.add(guid);
          }
          if (guid != null && aliasGuids.contains(guid)) {
            context.getReport().matched(P4kKind.MANUFACTURER, false);
            return;
          }
          reconcileCanonical(manufacturer, guid, families, matcher, rows, context);
        });
    auxiliaryGuids.removeAll(canonicalGuids);
    context.indexAuxiliaryManufacturers(auxiliaryGuids);
  }

  /**
   * Matches one canonical record that no reviewed alias resolves: a borrower never by name, and an
   * unmatched record of its own brand is held for seeding (REQ-P4K-021, REQ-P4K-058).
   *
   * @param manufacturer the record
   * @param guid the record's GUID, or {@code null}
   * @param families the catalogue's brands
   * @param matcher the run's manufacturer matcher
   * @param rows the manufacturer rows
   * @param context the run
   */
  private void reconcileCanonical(
      @NotNull P4kManufacturerDto manufacturer,
      @Nullable UUID guid,
      @NotNull P4kManufacturerFamilies families,
      @NotNull P4kRowMatcher<Manufacturer> matcher,
      @NotNull P4kRowIndex<Manufacturer> rows,
      @NotNull P4kImportContext context) {
    boolean borrower = families.lenderOf(guid) != null;
    boolean ownBrand = !borrower && !families.isSibling(guid);
    matcher.reconcile(
        manufacturer,
        null,
        borrower ? null : manufacturer.nameEn(),
        false,
        context,
        rows,
        (row, matchedGuid) -> {
          context.indexManufacturer(matchedGuid, row);
          return write(row, manufacturer, matchedGuid, borrower, context);
        },
        unmatchedGuid -> {
          if (unmatchedGuid == null || !ownBrand) {
            context.getReport().unmatched(P4kKind.MANUFACTURER, manufacturer);
          } else {
            context.holdUnmatchedManufacturer(unmatchedGuid, manufacturer);
          }
        },
        (row, coveredGuid) -> context.indexManufacturer(coveredGuid, row));
  }

  private static void observeAuxiliary(
      @NotNull P4kManufacturerDto record,
      @Nullable UUID guid,
      @NotNull P4kRowIndex<Manufacturer> rows,
      @NotNull Set<UUID> aliases,
      @NotNull P4kImportContext context) {
    Optional<Manufacturer> exact = guid == null ? Optional.empty() : rows.byP4kUuid(guid);
    if (guid != null && exact.isPresent()) {
      Manufacturer row = exact.get();
      context.indexManufacturer(guid, row);
      context.stamp(P4kKind.MANUFACTURER, record, row, guid);
      context.getReport().matched(P4kKind.MANUFACTURER, false);
    } else if (guid != null && aliases.contains(guid)) {
      context.getReport().matched(P4kKind.MANUFACTURER, false);
    } else {
      context.getReport().unmatched(P4kKind.MANUFACTURER, record);
    }
  }

  /**
   * Creates the held unmatched manufacturer an item or ship the run seeds references, on first use
   * (REQ-P4K-021), and indexes it so {@link P4kImportContext#manufacturer} returns it; a preview
   * only reports the creation. A matched or unknown GUID changes nothing.
   *
   * @param guid the referenced manufacturer GUID text, or {@code null}
   * @param context the run
   */
  public void seed(@Nullable String guid, @NotNull P4kImportContext context) {
    P4kManufacturerDto held = context.takeUnmatchedManufacturer(guid);
    if (held == null) {
      return;
    }
    UUID parsed = held.parsedGuid();
    String name = stripped(held.nameEn());
    if (parsed == null || name == null) {
      context.getReport().unmatched(P4kKind.MANUFACTURER, held);
      return;
    }
    if (nameTaken(name, held, parsed, context)) {
      return;
    }
    context.getReport().added(P4kKind.MANUFACTURER, held);
    if (!context.isApply()) {
      context.claimManufacturerName(name, null);
      return;
    }
    Manufacturer row = new Manufacturer();
    row.setName(name);
    String code = stripped(held.code());
    row.setAbbreviation(code == null ? name : code);
    row.setNameDe(P4kFields.germanName(held.name()));
    row.setDescription(StringNormalization.blankToNull(P4kLocalisedText.enOf(held.description())));
    row.setDescriptionDe(
        StringNormalization.blankToNull(P4kLocalisedText.deOf(held.description())));
    context.stamp(P4kKind.MANUFACTURER, held, row, parsed);
    Manufacturer saved = manufacturerRepository.save(row);
    context.claimManufacturerName(name, saved.getId());
    context.manufacturerRows().add(saved);
    context.indexManufacturer(parsed, saved);
  }

  /**
   * Whether a row or an earlier seed of the run holds a seed's name; that opens the {@code
   * NAME_CONFLICT} review case against the holder and the seed is not created (REQ-P4K-021).
   *
   * @param name the seed's stripped name
   * @param held the seed's record
   * @param guid the seed's GUID
   * @param context the run
   * @return {@code true} when the name is taken
   */
  private boolean nameTaken(
      @NotNull String name,
      @NotNull P4kManufacturerDto held,
      @NotNull UUID guid,
      @NotNull P4kImportContext context) {
    UUID holderId;
    P4kImportContext.NameClaim claim = context.manufacturerNameClaim(name);
    if (claim != null) {
      holderId = claim.rowId();
    } else {
      Optional<Manufacturer> holder = context.manufacturerRows().byName(name).stream().findFirst();
      if (holder.isEmpty()) {
        return false;
      }
      holderId = holder.get().getId();
    }
    context
        .getReport()
        .review(P4kKind.MANUFACTURER, held, guid, P4kReviewReason.NAME_CONFLICT, holderId);
    return true;
  }

  /**
   * Reports the unmatched manufacturers no seeded item or ship took and, on an APPLY, settles the
   * manufacturer cases the run no longer raises (REQ-P4K-016); runs after items and ships, whose
   * seeding raises name conflicts.
   *
   * @param catalogue the catalogue
   * @param context the run
   */
  public void finish(@NotNull P4kCatalogue catalogue, @NotNull P4kImportContext context) {
    context
        .heldUnmatchedManufacturers()
        .forEach(held -> context.getReport().unmatched(P4kKind.MANUFACTURER, held));
    if (context.isApply()) {
      resolvedCases.settleRun(
          P4kKind.MANUFACTURER,
          List.of(P4kReviewReason.GUID_CONFLICT, P4kReviewReason.NAME_CONFLICT),
          catalogue.manufacturers(),
          catalogue.head().isComplete(P4kKind.MANUFACTURER),
          context);
    }
  }

  /**
   * Writes the values the catalogue leads onto a matched row; a record borrowing another brand's
   * name leads only its abbreviation (REQ-P4K-058).
   *
   * @param row the matched manufacturer
   * @param manufacturer the record
   * @param guid the record's GUID
   * @param borrower whether the record borrows another brand's name and description
   * @param context the run
   * @return whether a field changes
   */
  private boolean write(
      @NotNull Manufacturer row,
      @NotNull P4kManufacturerDto manufacturer,
      @NotNull UUID guid,
      boolean borrower,
      @NotNull P4kImportContext context) {
    P4kRowWriter writer = new P4kRowWriter(context, P4kKind.MANUFACTURER, manufacturer);
    writer.lead(
        "abbreviation", row.getAbbreviation(), stripped(manufacturer.code()), row::setAbbreviation);
    if (borrower) {
      return writer.isChanged();
    }
    writeName(row, manufacturer, guid, writer, context);
    writer.lead(
        P4kReportColumns.NAME_DE,
        row.getNameDe(),
        P4kFields.germanName(manufacturer.name()),
        row::setNameDe);
    writer.leadGameText(
        "description",
        row.getDescription(),
        P4kLocalisedText.enOf(manufacturer.description()),
        row::setDescription);
    writer.leadGameText(
        "description_de",
        row.getDescriptionDe(),
        P4kLocalisedText.deOf(manufacturer.description()),
        row::setDescriptionDe);
    return writer.isChanged();
  }

  /**
   * Writes the P4K name unless another row holds it; that conflict goes to the review list and both
   * rows keep their names (REQ-P4K-020).
   *
   * @param row the matched manufacturer
   * @param manufacturer the record
   * @param guid the record's GUID
   * @param writer the row's writer
   * @param context the run
   */
  private void writeName(
      @NotNull Manufacturer row,
      @NotNull P4kManufacturerDto manufacturer,
      @NotNull UUID guid,
      @NotNull P4kRowWriter writer,
      @NotNull P4kImportContext context) {
    String name = stripped(manufacturer.nameEn());
    if (name == null || name.equals(row.getName())) {
      return;
    }
    boolean held =
        context.manufacturerRows().byName(name).stream()
            .anyMatch(other -> !other.getId().equals(row.getId()));
    if (held) {
      context
          .getReport()
          .review(
              P4kKind.MANUFACTURER, manufacturer, guid, P4kReviewReason.NAME_CONFLICT, row.getId());
      return;
    }
    writer.lead("name", row.getName(), name, row::setName);
    context.claimManufacturerName(name, row.getId());
  }

  /**
   * Strips a catalogue text.
   *
   * @param text the text, or {@code null}
   * @return the stripped text, or {@code null} when it is absent or blank
   */
  @Nullable
  private static String stripped(@Nullable String text) {
    String value = StringNormalization.blankToNull(text);
    return value == null ? null : value.strip();
  }
}
