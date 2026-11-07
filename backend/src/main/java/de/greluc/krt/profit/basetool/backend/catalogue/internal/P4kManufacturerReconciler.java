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
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kManufacturerDto;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reconciles the catalogue's {@code manufacturers} with {@code manufacturer} (REQ-P4K-020,
 * REQ-P4K-021): match by P4K GUID, SC Wiki UUID and name, never by abbreviation; write the name,
 * the abbreviation from {@code code}, the German name and both descriptions the catalogue leads;
 * leave a name another row holds on the review list; stamp and index each match for the items and
 * ships of the run. An unmatched manufacturer is created only for an item or ship the run seeds.
 */
@Component
@RequiredArgsConstructor
public class P4kManufacturerReconciler {

  private final ManufacturerRepository manufacturerRepository;

  /**
   * Reconciles every manufacturer and holds the unmatched ones for {@link #seed} and {@link
   * #finish}.
   *
   * @param manufacturers the catalogue's manufacturers
   * @param context the run
   */
  public void reconcile(
      @NotNull List<P4kManufacturerDto> manufacturers, @NotNull P4kImportContext context) {
    P4kRowIndex<Manufacturer> rows =
        new P4kRowIndex<>(
            manufacturerRepository.findAll(),
            Manufacturer::getScwikiUuid,
            _ -> null,
            Manufacturer::getName);
    context.indexManufacturerRows(rows);
    P4kRowMatcher<Manufacturer> matcher =
        new P4kRowMatcher<>(
            P4kKind.MANUFACTURER,
            rows::byP4kUuid,
            rows::byWikiUuid,
            Manufacturer::getScwikiUuid,
            "scwiki_uuid",
            _ -> List.of(),
            rows::byName,
            _ -> false);
    for (P4kManufacturerDto manufacturer : manufacturers) {
      matcher.reconcile(
          manufacturer,
          null,
          manufacturer.nameEn(),
          false,
          context,
          rows,
          (row, guid) -> {
            context.indexManufacturer(guid, row);
            return write(row, manufacturer, guid, context);
          },
          guid -> {
            if (guid == null) {
              context.getReport().unmatched(P4kKind.MANUFACTURER, manufacturer);
            } else {
              context.holdUnmatchedManufacturer(guid, manufacturer);
            }
          });
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
   * Reports the unmatched manufacturers no seeded item or ship took; runs after items and ships.
   *
   * @param context the run
   */
  public void finish(@NotNull P4kImportContext context) {
    context
        .heldUnmatchedManufacturers()
        .forEach(held -> context.getReport().unmatched(P4kKind.MANUFACTURER, held));
  }

  /**
   * Writes the values the catalogue leads onto a matched row.
   *
   * @param row the matched manufacturer
   * @param manufacturer the record
   * @param guid the record's GUID
   * @param context the run
   * @return whether a field changes
   */
  private boolean write(
      @NotNull Manufacturer row,
      @NotNull P4kManufacturerDto manufacturer,
      @NotNull UUID guid,
      @NotNull P4kImportContext context) {
    P4kRowWriter writer = new P4kRowWriter(context, P4kKind.MANUFACTURER, manufacturer);
    writeName(row, manufacturer, guid, writer, context);
    writer.lead(
        "abbreviation", row.getAbbreviation(), stripped(manufacturer.code()), row::setAbbreviation);
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
