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

package de.greluc.krt.profit.basetool.ingest.web;

import de.greluc.krt.profit.basetool.ingest.contract.ExchangeDocuments;
import de.greluc.krt.profit.basetool.ingest.problem.NotFoundException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the exchange contract anonymously: the OpenAPI document and the JSON Schemas at their
 * permanent {@code $id} (REQ-XCH-001, REQ-XCH-011). The document served here is the gateway's only
 * API description.
 */
@RestController
@RequestMapping("/exchange/v1")
@RequiredArgsConstructor
@PreAuthorize("permitAll()")
public class ExchangeDocumentsController {

  /** The media type of a JSON Schema document. */
  static final String SCHEMA_JSON = "application/schema+json";

  /** Documents change only with a release, so clients and proxies may keep them for an hour. */
  private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

  private final ExchangeDocuments documents;

  /**
   * Returns the committed OpenAPI 3.1 document of the exchange API.
   *
   * @return the document
   */
  @NotNull
  @GetMapping("/openapi.json")
  @PreAuthorize("permitAll()")
  public ResponseEntity<byte[]> openApi() {
    return ResponseEntity.ok()
        .cacheControl(CACHE)
        .contentType(MediaType.APPLICATION_JSON)
        .body(documents.openApi());
  }

  /**
   * Returns one committed JSON Schema.
   *
   * @param name the schema's file name, e.g. {@code item-ref.schema.json}
   * @return the schema
   * @throws NotFoundException if no committed schema has that name
   */
  @NotNull
  @GetMapping("/schemas/{name}")
  @PreAuthorize("permitAll()")
  public ResponseEntity<byte[]> schema(@NotNull @PathVariable String name) {
    byte[] schema = documents.schema(name);
    if (schema == null) {
      throw new NotFoundException("No exchange schema has that name.");
    }
    return ResponseEntity.ok()
        .cacheControl(CACHE)
        .contentType(MediaType.parseMediaType(SCHEMA_JSON))
        .body(schema);
  }
}
