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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Parses the "Star Citizen: Hangar Extension" hangar CSV export ({@code my-hangar-*.csv}) into
 * import entries, one per row of content type {@code Ship} or {@code Included Ship}
 * (REQ-HANGAR-015).
 *
 * <p>Every other row (skins, equipment, hangar decorations, credits, …) is dropped; an {@code
 * Insurance} row only lends its insurance to the ships of the same pledge. The pledge columns
 * {@code Pledge ID}, {@code Pledge Date}, {@code Pledge Cost}, {@code Meltable} and {@code
 * Giftable} are carried when present and readable; an unreadable value becomes {@code null} rather
 * than failing the import.
 */
final class HangarExtensionCsvParser {

  /** Header of the pledge id column; its presence identifies the format. */
  static final String COL_PLEDGE_ID = "Pledge ID";

  private static final String COL_PLEDGE_DATE = "Pledge Date";
  private static final String COL_PLEDGE_COST = "Pledge Cost";
  private static final String COL_MELTABLE = "Meltable";
  private static final String COL_GIFTABLE = "Giftable";
  private static final String COL_CONTENT = "Pledge Content";
  private static final String COL_CONTENT_TYPE = "Content Type";

  private static final String TYPE_SHIP = "ship";
  private static final String TYPE_INCLUDED_SHIP = "included ship";
  private static final String TYPE_INSURANCE = "insurance";

  private static final Pattern PLEDGE_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
  private static final Pattern COST =
      Pattern.compile("^\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([A-Za-z]{3})?\\s*$");
  private static final BigDecimal MAX_COST = new BigDecimal("9999999999.99");
  private static final DateTimeFormatter RSI_DATE =
      DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH);

  private HangarExtensionCsvParser() {}

  /**
   * Tells whether a text looks like this CSV export, by the {@code Pledge ID} column in its header.
   *
   * @param text the decoded upload, without byte-order mark
   * @return {@code true} when the first line names the pledge id column
   */
  static boolean looksLikeHangarCsv(@NotNull String text) {
    int end = text.indexOf('\n');
    String header = end < 0 ? text : text.substring(0, end);
    return header.contains(COL_PLEDGE_ID);
  }

  /**
   * Parses the export into ship entries in file order.
   *
   * @param text the decoded upload, without byte-order mark
   * @return one entry per ship row; empty when the export lists no ships
   * @throws BadRequestException when the header lacks the content or content-type column
   */
  static @NotNull List<FleetExportParser.FleetImportEntry> parse(@NotNull String text) {
    List<List<String>> records = readRecords(text);
    if (records.isEmpty()) {
      return List.of();
    }
    Map<String, Integer> columns = new HashMap<>();
    List<String> header = records.getFirst();
    for (int i = 0; i < header.size(); i++) {
      columns.putIfAbsent(header.get(i).trim(), i);
    }
    Integer contentCol = columns.get(COL_CONTENT);
    Integer typeCol = columns.get(COL_CONTENT_TYPE);
    if (contentCol == null || typeCol == null) {
      throw new BadRequestException(
          "The hangar CSV lacks the 'Pledge Content' or 'Content Type' column.");
    }

    List<List<String>> rows = records.subList(1, records.size());
    Map<String, String> insuranceByPledge = new HashMap<>();
    for (List<String> row : rows) {
      if (TYPE_INSURANCE.equals(lower(cell(row, typeCol)))) {
        String pledgeId = pledgeId(cell(row, columns.get(COL_PLEDGE_ID)));
        String insurance = FleetExportParser.parseInsurance(cell(row, contentCol));
        if (pledgeId != null && insurance != null) {
          insuranceByPledge.putIfAbsent(pledgeId, insurance);
        }
      }
    }

    List<FleetExportParser.FleetImportEntry> entries = new ArrayList<>();
    for (List<String> row : rows) {
      String type = lower(cell(row, typeCol));
      if (!TYPE_SHIP.equals(type) && !TYPE_INCLUDED_SHIP.equals(type)) {
        continue;
      }
      String name = cell(row, contentCol);
      if (name == null || name.isBlank()) {
        continue;
      }
      String pledgeId = pledgeId(cell(row, columns.get(COL_PLEDGE_ID)));
      FleetExportParser.PledgeInfo pledge =
          pledgeId == null
              ? null
              : new FleetExportParser.PledgeInfo(
                  pledgeId,
                  parseDate(cell(row, columns.get(COL_PLEDGE_DATE))),
                  parseCost(cell(row, columns.get(COL_PLEDGE_COST))),
                  parseCurrency(cell(row, columns.get(COL_PLEDGE_COST))),
                  parseYesNo(cell(row, columns.get(COL_MELTABLE))),
                  parseYesNo(cell(row, columns.get(COL_GIFTABLE))));
      entries.add(
          new FleetExportParser.FleetImportEntry(
              name.trim(),
              null,
              pledgeId == null ? null : insuranceByPledge.get(pledgeId),
              null,
              null,
              pledge,
              withoutFirstWord(name.trim())));
    }
    return entries;
  }

  /**
   * Drops the leading manufacturer word the CSV puts before every model, e.g. {@code "Mirai Fury
   * LX"} to {@code "Fury LX"}, so the model does not also match its shorter siblings.
   *
   * @param name the trimmed pledge content
   * @return the name without its first word, or {@code null} when it has only one word
   */
  static @Nullable String withoutFirstWord(@NotNull String name) {
    int space = name.indexOf(' ');
    if (space < 0) {
      return null;
    }
    String rest = name.substring(space + 1).trim();
    return rest.isEmpty() ? null : rest;
  }

  /**
   * Splits the text into RFC 4180 records: comma-separated, double-quoted fields with {@code ""} as
   * an escaped quote, line breaks inside quotes kept, blank lines dropped.
   *
   * @param text the decoded upload
   * @return the records, each a list of raw field values
   */
  static @NotNull List<List<String>> readRecords(@NotNull String text) {
    List<List<String>> records = new ArrayList<>();
    List<String> fields = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    boolean fieldStarted = false;
    int length = text.length();
    for (int i = 0; i < length; i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < length && text.charAt(i + 1) == '"') {
            field.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          field.append(c);
        }
      } else if (c == '"') {
        quoted = true;
        fieldStarted = true;
      } else if (c == ',') {
        fields.add(field.toString());
        field.setLength(0);
        fieldStarted = true;
      } else if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
          i++;
        }
        endRecord(records, fields, field, fieldStarted);
        fields = new ArrayList<>();
        fieldStarted = false;
      } else {
        field.append(c);
        fieldStarted = true;
      }
    }
    endRecord(records, fields, field, fieldStarted);
    return records;
  }

  /**
   * Closes the current record and adds it unless the line was empty.
   *
   * @param records the records read so far
   * @param fields the closed fields of the current record
   * @param field the open field, cleared afterwards
   * @param fieldStarted whether the current line held any character
   */
  private static void endRecord(
      @NotNull List<List<String>> records,
      @NotNull List<String> fields,
      @NotNull StringBuilder field,
      boolean fieldStarted) {
    if (fieldStarted || !fields.isEmpty()) {
      fields.add(field.toString());
      records.add(fields);
    }
    field.setLength(0);
  }

  /**
   * Reads one cell of a row.
   *
   * @param row the record
   * @param col the column index, or {@code null} when the header lacks the column
   * @return the trimmed cell, or {@code null} when the column or cell is missing
   */
  private static @Nullable String cell(@NotNull List<String> row, @Nullable Integer col) {
    if (col == null || col >= row.size()) {
      return null;
    }
    return row.get(col).trim();
  }

  /**
   * Lower-cases a value for comparison.
   *
   * @param value the value
   * @return the lower-cased value, or the empty string for {@code null}
   */
  private static @NotNull String lower(@Nullable String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }

  /**
   * Accepts a pledge id of up to 32 letters, digits, hyphens or underscores.
   *
   * @param raw the cell value
   * @return the id, or {@code null} when blank or malformed
   */
  static @Nullable String pledgeId(@Nullable String raw) {
    if (raw == null || !PLEDGE_ID.matcher(raw).matches()) {
      return null;
    }
    return raw;
  }

  /**
   * Reads an RSI pledge date such as {@code "October 15, 2026"}, or an ISO date.
   *
   * @param raw the cell value
   * @return the date, or {@code null} when blank or unreadable
   */
  static @Nullable LocalDate parseDate(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw, RSI_DATE);
    } catch (DateTimeParseException _) {
      try {
        return LocalDate.parse(raw);
      } catch (DateTimeParseException ignored) {
        return null;
      }
    }
  }

  /**
   * Reads the amount of a price such as {@code "15,000.00 USD"}, rounded to cents.
   *
   * @param raw the cell value
   * @return the amount, or {@code null} when blank, unreadable or above the column's range
   */
  static @Nullable BigDecimal parseCost(@Nullable String raw) {
    Matcher m = costMatcher(raw);
    if (m == null) {
      return null;
    }
    BigDecimal amount =
        new BigDecimal(m.group(1).replace(",", "")).setScale(2, RoundingMode.HALF_UP);
    return amount.compareTo(MAX_COST) > 0 ? null : amount;
  }

  /**
   * Reads the currency code of a price such as {@code "15,000.00 USD"}.
   *
   * @param raw the cell value
   * @return the upper-case three-letter code, or {@code null} when the price names none
   */
  static @Nullable String parseCurrency(@Nullable String raw) {
    Matcher m = costMatcher(raw);
    if (m == null || m.group(2) == null) {
      return null;
    }
    return m.group(2).toUpperCase(Locale.ROOT);
  }

  /**
   * Matches a price cell.
   *
   * @param raw the cell value
   * @return the matched matcher, or {@code null} when the cell is no price
   */
  private static @Nullable Matcher costMatcher(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    Matcher m = COST.matcher(raw);
    return m.matches() ? m : null;
  }

  /**
   * Reads a {@code yes} / {@code no} flag, also accepting {@code true} / {@code false}.
   *
   * @param raw the cell value
   * @return the flag, or {@code null} when blank or anything else
   */
  static @Nullable Boolean parseYesNo(@Nullable String raw) {
    return switch (lower(raw)) {
      case "yes", "true" -> Boolean.TRUE;
      case "no", "false" -> Boolean.FALSE;
      default -> null;
    };
  }
}
