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

package de.greluc.krt.profit.basetool.backend.platform.internal.pdf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openpdf.text.pdf.PdfArray;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfDictionary;
import org.openpdf.text.pdf.PdfIndirectReference;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfObject;
import org.openpdf.text.pdf.PdfString;
import org.openpdf.text.pdf.PdfStructureElement;
import org.openpdf.text.pdf.PdfStructureTreeRoot;
import org.openpdf.text.pdf.PdfWriter;

/**
 * The logical structure of one tagged PDF in the PDF 2.0 namespace (ISO 32000-2 14.7, 14.8.6): a
 * single {@code Document} element under the structure tree root, group elements written when they
 * end and content elements written as soon as their marked content is complete (REQ-API-029).
 *
 * <p>OpenPDF assigns the marked-content identifiers, the pages' {@code StructParents} and the
 * parent tree, but keeps every element of its own tree in memory until the file closes. A content
 * element is therefore created as a child of the structure tree root, written once complete and
 * detached again; at close the root's {@code K} becomes the reference of the {@code Document}
 * element. Memory then grows with the open groups and one table row, not with the document.
 */
public final class PdfStructureTree {

  /** The PDF 2.0 standard structure namespace (ISO 32000-2 14.8.6.1). */
  public static final String PDF_2_0_NAMESPACE = "http://iso.org/pdf2/ssn";

  private static final PdfName NAMESPACE = new PdfName("Namespace");

  private static final PdfName NAMESPACES = new PdfName("Namespaces");

  private static final PdfName NS = new PdfName("NS");

  private static final PdfName STRUCT_ELEM = new PdfName("StructElem");

  private final PdfWriter writer;

  private final PdfStructureTreeRoot root;

  private final PdfIndirectReference namespace;

  private final Element document;

  /**
   * Starts the tree of an open, tagged document: the PDF 2.0 namespace and the {@code Document}
   * element.
   *
   * @param writer the writer of the open document; {@link PdfWriter#setTagged()} was called before
   *     it opened
   */
  public PdfStructureTree(@NotNull PdfWriter writer) {
    this.writer = writer;
    this.root = writer.getStructureTreeRoot();
    PdfDictionary namespaceDictionary = new PdfDictionary(NAMESPACE);
    namespaceDictionary.put(NS, new PdfString(PDF_2_0_NAMESPACE));
    this.namespace = write(namespaceDictionary, writer.getPdfIndirectReference());
    root.put(NAMESPACES, new PdfArray(namespace));
    this.document =
        new Element(PdfName.DOCUMENT, root.getReference(), writer.getPdfIndirectReference(), null);
  }

  /**
   * The single {@code Document} element, parent of every block of the page flow.
   *
   * @return the document element
   */
  @NotNull
  public Element document() {
    return document;
  }

  /**
   * Opens a group element, such as a table or a table row, under its parent; it is written by
   * {@link #end(Element)}.
   *
   * @param parent the enclosing element
   * @param type the PDF 2.0 structure type
   * @return the open group
   */
  @NotNull
  public Element group(@NotNull Element parent, @NotNull PdfName type) {
    Element group = new Element(type, parent.reference, writer.getPdfIndirectReference(), null);
    parent.kids.add(group.reference);
    return group;
  }

  /**
   * Opens a content element, such as a heading, a paragraph or a table cell, under its parent; its
   * marked content is drawn between {@link #beginContent} and {@link
   * PdfContentByte#endMarkedContentSequence()}.
   *
   * @param parent the enclosing element
   * @param type the PDF 2.0 structure type
   * @return the open content element
   */
  @NotNull
  public Element content(@NotNull Element parent, @NotNull PdfName type) {
    PdfStructureElement marked = new PdfStructureElement(root, type);
    Element content = new Element(type, parent.reference, marked.getReference(), marked);
    parent.kids.add(content.reference);
    return content;
  }

  /**
   * Begins one marked-content sequence of a content element on the canvas; an element continued on
   * a later page begins one sequence per page.
   *
   * @param canvas the canvas the content is drawn on
   * @param content the open content element
   */
  public void beginContent(@NotNull PdfContentByte canvas, @NotNull Element content) {
    if (content.marked == null) {
      throw new IllegalArgumentException("A group element carries no marked content");
    }
    canvas.beginMarkedContentSequence(content.marked);
  }

  /**
   * Writes an element with its kids and attributes and releases it; an element is ended once, after
   * every element below it.
   *
   * @param element the open element
   */
  public void end(@NotNull Element element) {
    PdfDictionary dictionary = element.dictionary;
    dictionary.put(PdfName.TYPE, STRUCT_ELEM);
    dictionary.put(PdfName.S, element.type);
    dictionary.put(NS, namespace);
    dictionary.put(PdfName.P, element.parent);
    if (element.attributes.size() == 1) {
      dictionary.put(PdfName.A, element.attributes.getFirst());
    } else if (!element.attributes.isEmpty()) {
      PdfArray owners = new PdfArray();
      element.attributes.forEach(owners::add);
      dictionary.put(PdfName.A, owners);
    }
    if (element.marked == null) {
      dictionary.put(PdfName.K, element.kids);
    } else {
      root.getAsArray(PdfName.K).remove(element.marked);
    }
    write(dictionary, element.reference);
  }

  /**
   * Ends the {@code Document} element and hands it to the structure tree root, which OpenPDF writes
   * with the parent tree when the document closes.
   */
  public void close() {
    end(document);
    root.put(PdfName.K, document.reference);
  }

  private @NotNull PdfIndirectReference write(
      @NotNull PdfObject object, @NotNull PdfIndirectReference reference) {
    try {
      writer.addToBody(object, reference);
      return reference;
    } catch (IOException failure) {
      throw new UncheckedIOException("Structure element could not be written", failure);
    }
  }

  /**
   * One structure element of the tree. A group collects the references of its kids; a content
   * element wraps the OpenPDF element that receives the marked-content identifiers.
   */
  public static final class Element {

    private final PdfName type;

    private final PdfIndirectReference parent;

    private final @Nullable PdfStructureElement marked;

    private final PdfDictionary dictionary;

    private final PdfIndirectReference reference;

    private final PdfArray kids = new PdfArray();

    private final List<PdfDictionary> attributes = new ArrayList<>(0);

    private Element(
        @NotNull PdfName type,
        @NotNull PdfIndirectReference parent,
        @NotNull PdfIndirectReference reference,
        @Nullable PdfStructureElement marked) {
      this.type = type;
      this.parent = parent;
      this.reference = reference;
      this.marked = marked;
      this.dictionary = marked == null ? new PdfDictionary() : marked;
    }

    /**
     * Sets one attribute of a standard attribute owner (ISO 32000-2 14.8.5), such as the scope of a
     * table header or the bounding box of a figure.
     *
     * @param owner the attribute owner, such as {@code Table} or {@code Layout}
     * @param key the attribute name
     * @param value the attribute value
     */
    public void attribute(@NotNull PdfName owner, @NotNull PdfName key, @NotNull PdfObject value) {
      PdfDictionary owned =
          attributes.stream()
              .filter(candidate -> owner.equals(candidate.getAsName(PdfName.O)))
              .findFirst()
              .orElseGet(() -> {
                PdfDictionary created = new PdfDictionary();
                created.put(PdfName.O, owner);
                attributes.add(created);
                return created;
              });
      owned.put(key, value);
    }

    /**
     * Sets the alternate description (ISO 32000-2 14.9.3) a reader announces instead of the
     * content, such as the summary of a chart.
     *
     * @param text the description
     */
    public void alternateDescription(@NotNull String text) {
      dictionary.put(PdfName.ALT, new PdfString(text, PdfObject.TEXT_UNICODE));
    }
  }
}
