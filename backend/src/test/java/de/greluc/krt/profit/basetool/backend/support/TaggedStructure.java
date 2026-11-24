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

package de.greluc.krt.profit.basetool.backend.support;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openpdf.text.pdf.PdfArray;
import org.openpdf.text.pdf.PdfDictionary;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfObject;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.PdfString;

/**
 * Reads the logical structure of a tagged PDF back into a tree of element types, attributes and
 * alternate descriptions, so tests can assert heading order, table headers and figure descriptions
 * (REQ-API-029).
 *
 * @param type the structure type, such as {@code H1} or {@code TD}
 * @param namespace the namespace URI of the element, or {@code null} for the default namespace
 * @param attributes every attribute of the element, keyed {@code <owner>.<name>}, values as PDF
 *     text ({@code /Column}, {@code 5}, {@code [40 ...]})
 * @param alternateDescription the {@code Alt} entry, or {@code null}
 * @param marked whether the element encloses marked content
 * @param kids the child elements in order
 */
public record TaggedStructure(
    @NotNull String type,
    @Nullable String namespace,
    @NotNull Map<String, String> attributes,
    @Nullable String alternateDescription,
    boolean marked,
    @NotNull List<TaggedStructure> kids) {

  private static final PdfName NS = new PdfName("NS");

  private static final PdfName NAMESPACES = new PdfName("Namespaces");

  /**
   * Reads the element tree below the structure tree root.
   *
   * @param pdf the rendered PDF
   * @return the root's only child, normally the {@code Document} element
   * @throws IOException when the file cannot be read
   */
  public static @NotNull TaggedStructure read(byte @NotNull [] pdf) throws IOException {
    PdfReader reader = new PdfReader(pdf);
    try {
      PdfDictionary root = reader.getCatalog().getAsDict(PdfName.STRUCTTREEROOT);
      if (root == null) {
        throw new AssertionError("The PDF has no structure tree");
      }
      List<TaggedStructure> top = kids(root);
      if (top.size() != 1) {
        throw new AssertionError("The structure tree root has " + top.size() + " children");
      }
      return top.getFirst();
    } finally {
      reader.close();
    }
  }

  /**
   * The namespace URIs the structure tree root declares.
   *
   * @param pdf the rendered PDF
   * @return the URIs of the root's {@code Namespaces} array
   * @throws IOException when the file cannot be read
   */
  public static @NotNull List<String> declaredNamespaces(byte @NotNull [] pdf) throws IOException {
    PdfReader reader = new PdfReader(pdf);
    try {
      PdfArray namespaces =
          reader.getCatalog().getAsDict(PdfName.STRUCTTREEROOT).getAsArray(NAMESPACES);
      List<String> uris = new ArrayList<>();
      for (int index = 0; index < namespaces.size(); index++) {
        uris.add(namespaces.getAsDict(index).getAsString(NS).toUnicodeString());
      }
      return uris;
    } finally {
      reader.close();
    }
  }

  /**
   * This element and every element below it, depth first in reading order.
   *
   * @return the flattened tree
   */
  public @NotNull Stream<TaggedStructure> flatten() {
    return Stream.concat(Stream.of(this), kids.stream().flatMap(TaggedStructure::flatten));
  }

  /**
   * Every element of a type below and including this one, in reading order.
   *
   * @param elementType the structure type
   * @return the matching elements
   */
  public @NotNull List<TaggedStructure> all(@NotNull String elementType) {
    return flatten().filter(byType(elementType)).toList();
  }

  /**
   * The types of the direct children, in order.
   *
   * @return the child types
   */
  public @NotNull List<String> kidTypes() {
    return kids.stream().map(TaggedStructure::type).toList();
  }

  private static @NotNull Predicate<TaggedStructure> byType(@NotNull String elementType) {
    return element -> element.type.equals(elementType);
  }

  private static @NotNull List<TaggedStructure> kids(@NotNull PdfDictionary element) {
    PdfObject k = PdfReader.getPdfObject(element.get(PdfName.K));
    List<PdfObject> items = new ArrayList<>();
    if (k instanceof PdfArray array) {
      for (int index = 0; index < array.size(); index++) {
        items.add(array.getDirectObject(index));
      }
    } else if (k != null) {
      items.add(k);
    }
    List<TaggedStructure> kids = new ArrayList<>();
    for (PdfObject item : items) {
      if (item instanceof PdfDictionary dictionary && dictionary.get(PdfName.S) != null) {
        kids.add(element(dictionary));
      }
    }
    return kids;
  }

  private static @NotNull TaggedStructure element(@NotNull PdfDictionary dictionary) {
    PdfDictionary namespace = dictionary.getAsDict(NS);
    PdfString alt = dictionary.getAsString(PdfName.ALT);
    return new TaggedStructure(
        PdfName.decodeName(dictionary.getAsName(PdfName.S).toString()),
        namespace == null ? null : namespace.getAsString(NS).toUnicodeString(),
        attributes(dictionary),
        alt == null ? null : alt.toUnicodeString(),
        marked(dictionary),
        kids(dictionary));
  }

  private static boolean marked(@NotNull PdfDictionary dictionary) {
    PdfObject k = PdfReader.getPdfObject(dictionary.get(PdfName.K));
    if (k == null) {
      return false;
    }
    if (k.isNumber()) {
      return true;
    }
    if (k instanceof PdfArray array && !array.isEmpty()) {
      PdfObject first = array.getDirectObject(0);
      return first.isNumber()
          || first instanceof PdfDictionary mcr && PdfName.MCR.equals(mcr.getAsName(PdfName.TYPE));
    }
    return false;
  }

  private static @NotNull Map<String, String> attributes(@NotNull PdfDictionary dictionary) {
    Map<String, String> attributes = new LinkedHashMap<>();
    PdfObject a = PdfReader.getPdfObject(dictionary.get(PdfName.A));
    List<PdfDictionary> owners = new ArrayList<>();
    if (a instanceof PdfDictionary single) {
      owners.add(single);
    } else if (a instanceof PdfArray array) {
      for (int index = 0; index < array.size(); index++) {
        PdfDictionary owner = array.getAsDict(index);
        if (owner != null) {
          owners.add(owner);
        }
      }
    }
    for (PdfDictionary owner : owners) {
      String prefix = PdfName.decodeName(owner.getAsName(PdfName.O).toString());
      for (PdfName key : owner.getKeys()) {
        if (!PdfName.O.equals(key)) {
          attributes.put(
              prefix + "." + PdfName.decodeName(key.toString()),
              PdfReader.getPdfObject(owner.get(key)).toString());
        }
      }
    }
    return attributes;
  }
}
