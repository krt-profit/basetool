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

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kRejectionReason;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kUploadRejectedException;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kBuild;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogueFormatException;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogueHead;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogueReader;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kChangeNumber;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCompression;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kContentTooLargeException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The synchronous upload gate (REQ-P4K-001, REQ-P4K-002, ADR-0335): it accepts only a LIVE
 * catalogue of {@code schemaVersion} 2 that names its Build, as plain, gzip or zstd JSON within the
 * two size limits. The Channel rule is hard-wired; no property or profile changes it.
 */
@Component
@RequiredArgsConstructor
public class P4kUploadGate {

  /** The contract's {@code UPLOAD_LIMIT}: the most bytes of the file as uploaded. */
  public static final long UPLOAD_LIMIT_BYTES = 64_000_000L;

  /** The only shape version the import reads. */
  static final int SCHEMA_VERSION = 2;

  /** The only Channel the import reads. */
  static final String LIVE = "LIVE";

  private final P4kCatalogueReader reader;
  private final P4kImportProperties properties;
  private final P4kRejections rejections;

  /**
   * Checks an upload and returns what the job stores.
   *
   * @param upload the uploaded file
   * @return the accepted upload, its payload compressed for storage
   * @throws P4kUploadRejectedException when the gate refuses the file; the refusal is logged and
   *     counted
   */
  @NotNull
  public P4kAcceptedUpload check(byte @NotNull [] upload) {
    P4kRejectionReason reason = refusal(upload);
    if (reason != null) {
      rejections.record(reason);
      throw new P4kUploadRejectedException(reason);
    }
    return new P4kAcceptedUpload(P4kCompression.toStoredPayload(upload), upload.length);
  }

  /**
   * Finds the first rule the upload breaks.
   *
   * @param upload the uploaded file
   * @return the reason, or {@code null} when the upload passes
   */
  @Nullable
  private P4kRejectionReason refusal(byte @NotNull [] upload) {
    if (upload.length > UPLOAD_LIMIT_BYTES) {
      return P4kRejectionReason.TOO_LARGE;
    }
    long limit = properties.decompressedLimitBytes();
    try {
      P4kCatalogueHead head;
      try (InputStream in = P4kCompression.openDecompressed(upload, limit)) {
        head = reader.readHead(in);
      }
      P4kRejectionReason headRefusal = headRefusal(head);
      if (headRefusal != null) {
        return headRefusal;
      }
      try (InputStream in = P4kCompression.openDecompressed(upload, limit)) {
        in.transferTo(OutputStream.nullOutputStream());
      }
      return null;
    } catch (P4kContentTooLargeException _) {
      return P4kRejectionReason.TOO_LARGE;
    } catch (P4kCatalogueFormatException _) {
      return P4kRejectionReason.SCHEMA_VERSION;
    } catch (IOException e) {
      throw new UncheckedIOException("Reading an in-memory upload failed.", e);
    }
  }

  /**
   * Checks the head rules in the order of REQ-P4K-001.
   *
   * @param head the head of the upload
   * @return the reason, or {@code null} when the head passes
   */
  @Nullable
  static P4kRejectionReason headRefusal(@NotNull P4kCatalogueHead head) {
    if (!Objects.equals(head.schemaVersion(), SCHEMA_VERSION)) {
      return P4kRejectionReason.SCHEMA_VERSION;
    }
    if (head.source() == null || !LIVE.equals(head.source().channel())) {
      return P4kRejectionReason.CHANNEL;
    }
    P4kBuild build = head.build();
    if (build == null || P4kChangeNumber.parse(build.changeNumber()) == null) {
      return P4kRejectionReason.BUILD_MISSING;
    }
    return null;
  }
}
