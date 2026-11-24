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

import org.jetbrains.annotations.NotNull;
import org.openpdf.text.Document;
import org.openpdf.text.PageSize;
import org.openpdf.text.Rectangle;

/**
 * The vertical cursor of a KRT document on A4 pages with 40 pt side and 60 pt top and bottom
 * margins, and the page breaks it takes. Coordinates are PDF user space: points, origin at the
 * bottom-left corner of the page.
 */
public final class PdfPageFlow {

  /** The page format of every backend PDF. */
  public static final Rectangle PAGE = PageSize.A4;

  /** Left and right margin, in points. */
  public static final float SIDE_MARGIN = 40f;

  /** Top and bottom margin, in points. */
  public static final float TOP_BOTTOM_MARGIN = 60f;

  private final Document document;

  private float cursor;

  private boolean pageStart;

  /**
   * Starts the flow at the top of the document's first page.
   *
   * @param document the open document whose pages the flow fills
   */
  public PdfPageFlow(@NotNull Document document) {
    this.document = document;
    this.cursor = top();
    this.pageStart = true;
  }

  /**
   * The left edge of the content area.
   *
   * @return the x coordinate, in points
   */
  public float left() {
    return SIDE_MARGIN;
  }

  /**
   * The right edge of the content area.
   *
   * @return the x coordinate, in points
   */
  public float right() {
    return PAGE.getWidth() - SIDE_MARGIN;
  }

  /**
   * The width of the content area.
   *
   * @return the width, in points
   */
  public float width() {
    return right() - left();
  }

  /**
   * The top edge of the content area, where every page starts.
   *
   * @return the y coordinate, in points
   */
  public float top() {
    return PAGE.getHeight() - TOP_BOTTOM_MARGIN;
  }

  /**
   * The bottom edge of the content area, below which nothing is drawn.
   *
   * @return the y coordinate, in points
   */
  public float bottom() {
    return TOP_BOTTOM_MARGIN;
  }

  /**
   * The current position, where the next block starts.
   *
   * @return the y coordinate, in points
   */
  public float cursor() {
    return cursor;
  }

  /**
   * The height left on the current page below the cursor.
   *
   * @return the height, in points
   */
  public float available() {
    return cursor - bottom();
  }

  /**
   * The height of an empty page's content area.
   *
   * @return the height, in points
   */
  public float capacity() {
    return top() - bottom();
  }

  /**
   * Whether nothing has been placed on the current page yet.
   *
   * @return {@code true} at the top of a fresh page
   */
  public boolean atPageStart() {
    return pageStart;
  }

  /**
   * Moves the cursor down.
   *
   * @param height the distance, in points
   */
  public void advance(float height) {
    moveTo(cursor - height);
  }

  /**
   * Moves the cursor to a position a block reached, such as the last baseline of a text.
   *
   * @param y the new position, in points
   */
  public void moveTo(float y) {
    cursor = Math.max(y, bottom());
    pageStart = false;
  }

  /** Ends the current page and puts the cursor at the top of the next one. */
  public void newPage() {
    document.newPage();
    cursor = top();
    pageStart = true;
  }
}
