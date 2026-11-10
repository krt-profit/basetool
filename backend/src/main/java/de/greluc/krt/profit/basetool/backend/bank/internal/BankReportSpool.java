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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.kernel.ReportGenerationException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/** Private disk-backed immutable report snapshots, framed in bounded JSON chunks. */
public final class BankReportSpool implements AutoCloseable {
  static final int CHUNK_SIZE = 500;
  private static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;
  private final Path directory;

  private BankReportSpool(Path directory) {
    this.directory = directory;
  }

  static BankPdfExport export(BankExportProperties properties, Consumer<BankReportSpool> produce) {
    BankReportSpool spool = create(properties);
    try {
      produce.accept(spool);
      return new BankPdfExport(spool);
    } catch (RuntimeException | Error failure) {
      try {
        spool.close();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  /**
   * Creates an owner-only directory for a single export.
   *
   * @param properties the configured parent directory
   * @return a spool whose close removes all its files
   */
  static @NotNull BankReportSpool create(@NotNull BankExportProperties properties) {
    Path created = null;
    try {
      Files.createDirectories(properties.directory());
      if (Files.getFileStore(properties.directory()).supportsFileAttributeView("posix")) {
        created =
            Files.createTempDirectory(
                properties.directory(),
                "bank-export-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      } else {
        created = Files.createTempDirectory(properties.directory(), "bank-export-");
        AclFileAttributeView acl = Files.getFileAttributeView(created, AclFileAttributeView.class);
        if (acl == null) {
          throw new IOException(
              "Private Bank export storage requires POSIX permissions or filesystem ACLs");
        }
        acl.setAcl(
            List.of(
                AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(created))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                    .build()));
      }
      return new BankReportSpool(created);
    } catch (IOException | RuntimeException failure) {
      if (created != null) {
        try {
          Files.deleteIfExists(created);
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }
      }
      throw new ReportGenerationException("Bank export storage initialization failed", failure);
    }
  }

  Path file(String name) {
    return directory.resolve(name);
  }

  static DataOutputStream writer(Path file) throws IOException {
    return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)));
  }

  static void append(DataOutputStream out, ObjectMapper mapper, Object value) throws IOException {
    byte[] bytes = mapper.writeValueAsBytes(value);
    if (bytes.length > MAX_FRAME_BYTES) {
      throw new IOException("Bank export snapshot frame exceeds its structural limit");
    }
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  static <T> void read(Path file, ObjectMapper mapper, Class<T> type, Consumer<T> consume) {
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
      while (true) {
        byte[] header = in.readNBytes(Integer.BYTES);
        if (header.length == 0) {
          break;
        }
        if (header.length != Integer.BYTES) {
          throw new EOFException("Incomplete Bank export snapshot frame header");
        }
        int length = ByteBuffer.wrap(header).getInt();
        if (length < 0 || length > MAX_FRAME_BYTES) {
          throw new IOException("Invalid Bank export snapshot frame length");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
          throw new EOFException("Incomplete Bank export snapshot frame");
        }
        consume.accept(mapper.readValue(bytes, type));
      }
    } catch (IOException failure) {
      throw new ReportGenerationException("Bank export snapshot read failed", failure);
    }
  }

  /** Removes the export's files and its private directory. */
  @Override
  public void close() {
    try {
      if (Files.notExists(directory)) {
        return;
      }
      try (var entries = Files.list(directory)) {
        var iterator = entries.iterator();
        while (iterator.hasNext()) {
          Files.deleteIfExists(iterator.next());
        }
      }
      Files.deleteIfExists(directory);
    } catch (IOException failure) {
      throw new ReportGenerationException("Bank export storage cleanup failed", failure);
    }
  }

  /**
   * Immutable account data detached from the persistence context.
   *
   * @param id account identifier
   * @param accountNo printable account number
   * @param name account label
   */
  public record Account(UUID id, String accountNo, String name) {}

  /**
   * One immutable posting row with resolved display snapshots.
   *
   * @param createdAt posting time
   * @param type booking kind
   * @param amount signed account amount
   * @param note booking note
   * @param justification movement justification
   * @param staffNote staff-only annotation
   * @param holder rendered holder label
   * @param counterparty rendered counterparty cell
   */
  public record Line(
      Instant createdAt,
      BankTransactionType type,
      BigDecimal amount,
      @Nullable String note,
      @Nullable String justification,
      @Nullable String staffNote,
      String holder,
      String counterparty) {}

  /**
   * One bounded batch of posting snapshots.
   *
   * @param lines chronological posting rows
   */
  public record Lines(List<Line> lines) {}

  /**
   * One bounded batch of management holder totals.
   *
   * @param balances the ordered nonzero balances
   */
  public record Holders(List<BankHolderBalance> balances) {}

  /**
   * Metadata of one spooled account section.
   *
   * @param account detached account labels
   * @param rowsFile the private chunk file name
   * @param opening opening balance
   * @param inflow positive period movements
   * @param outflow negative period movements
   * @param rows total posting count
   */
  public record Section(
      Account account,
      String rowsFile,
      BigDecimal opening,
      BigDecimal inflow,
      BigDecimal outflow,
      long rows) {
    /** Returns the closing account balance. */
    public BigDecimal closing() {
      return opening.add(inflow).add(outflow);
    }
  }
}
