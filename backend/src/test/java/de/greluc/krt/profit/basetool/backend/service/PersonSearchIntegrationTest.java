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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonSearchHitDto;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one property of the admin Personensuche that needs seeded rows to prove: <b>it matches
 * case-insensitively, in both directions</b> (REQ-SEC-060, ADR-0184).
 *
 * <p>The casing the admin types and the casing the row happens to hold are independent of each
 * other. That is not a nicety. The search exists so that an Art. 16 rectification or a granted Art.
 * 17 erasure can cover <em>every</em> mention of a name, and a name written into free text six
 * months ago is written the way its author felt like writing it. A search that only matched the
 * typed casing would answer "no further mentions" and be wrong — the one failure mode this surface
 * must not have, because nothing downstream would reveal it.
 *
 * <p>The registry's completeness, the validity of its statements against the live schema, the
 * minimum term length and the wildcard escaping are asserted by {@link
 * de.greluc.krt.profit.basetool.backend.repository.PersonSearchCoverageTest} and are deliberately
 * not repeated here.
 */
@SpringBootTest
@ActiveProfiles("test")
class PersonSearchIntegrationTest {

  /**
   * Mixed case on purpose, and distinctive enough that a hit cannot be a coincidence in a container
   * the whole suite shares.
   */
  private static final String HANDLE = "ZzzMixedCasePersonZzz";

  @Autowired private PersonSearchService personSearchService;
  @Autowired private UserRepository userRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private final Set<UUID> seededUsers = new HashSet<>();

  /** Removes what this class committed into the container the whole suite shares. */
  @AfterEach
  void cleanUp() {
    transactionTemplate.executeWithoutResult(
        status -> {
          userRepository.deleteAllById(seededUsers);
          seededUsers.clear();
        });
  }

  @Test
  void findsTheMentionWhateverCaseTheAdminTypes() {
    // Given a member whose handle also sits inside a sentence in a free-text column -- the shape
    // the search exists for, where no foreign key leads to the mention
    seedMember(HANDLE, "Flew wing with " + HANDLE + " during the last operation.");

    // When the admin types it in each of the three plausible casings
    // Then every one of them finds the handle, and the wrong casing still finds the sentence
    assertThat(hits(HANDLE, "username")).as("typed exactly as stored").hasSize(1);
    assertThat(hits(HANDLE.toLowerCase(Locale.ROOT), "username")).as("typed lower").hasSize(1);
    assertThat(hits(HANDLE.toUpperCase(Locale.ROOT), "username")).as("typed upper").hasSize(1);
    assertThat(hits(HANDLE.toLowerCase(Locale.ROOT), "description"))
        .as("a mention inside a sentence, searched in the wrong case")
        .hasSize(1);
  }

  @Test
  void findsTheMentionWhateverCaseTheRowHolds() {
    // Given the same name written three different ways by three different authors, which is what
    // free text actually looks like after a year
    seedMember(HANDLE.toLowerCase(Locale.ROOT), null);
    seedMember(HANDLE.toUpperCase(Locale.ROOT), null);
    seedMember(HANDLE, null);

    // When the admin searches once
    List<PersonSearchHitDto> found = hits(HANDLE, "username");

    // Then all three rows are reported. A rectification that missed two of them would leave the
    // name in the system while the record said it was gone.
    assertThat(found).as("one hit per seeded member").hasSize(3);
    assertThat(found).allSatisfy(h -> assertThat(h.area()).isEqualTo("MEMBER"));
  }

  // covers REQ-SEC-060 - the snippet has to contain the name that was searched for
  @Test
  void theSnippetIsAWindowAroundTheMatchAndNotTheValuesFirstCharacters() {
    // The snippet used to be left(column, 200). A long note that mentions the person late -- the
    // ordinary shape of a note -- then produced a snippet without the name in it, so the admin had
    // to open every hit to find out whether it was the right person. On a surface whose job is to
    // be exhaustive, that is the difference between a usable list and a list nobody finishes.
    String longPrefix = "x".repeat(400);
    seedMember(HANDLE, longPrefix + " und " + HANDLE + " waren beide dabei.");

    List<PersonSearchHitDto> found = hits(HANDLE, "description");

    assertThat(found).hasSize(1);
    assertThat(found.getFirst().snippet())
        .as("the window is centred on the match, so the name is in it")
        .contains(HANDLE);
  }

  /**
   * Commits a member so the native query can see it.
   *
   * @param username the handle, which is itself a searched column
   * @param description the free-text profile column, or {@code null} to leave it unset
   */
  private void seedMember(String username, String description) {
    UUID id = UUID.randomUUID();
    transactionTemplate.executeWithoutResult(
        status -> {
          User u = new User();
          u.setId(id);
          u.setUsername(username);
          u.setDescription(description);
          userRepository.save(u);
        });
    seededUsers.add(id);
  }

  /**
   * Searches for a term and keeps only the hits on one {@code app_user} column.
   *
   * <p>Narrowed to a single column because the assertions count seeded rows, and a seeded member
   * matches in both of the columns this class writes.
   *
   * @param term the search term
   * @param column the {@code app_user} column to keep
   * @return the matching hits
   */
  private List<PersonSearchHitDto> hits(String term, String column) {
    return personSearchService.search(term).hits().stream()
        .filter(h -> "app_user".equals(h.table()) && column.equals(h.column()))
        .toList();
  }
}
