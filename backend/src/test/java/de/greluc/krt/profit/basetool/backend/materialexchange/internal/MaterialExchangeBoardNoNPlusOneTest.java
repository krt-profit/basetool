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

package de.greluc.krt.profit.basetool.backend.materialexchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Verifies against real Postgres that the Materialbörse board and an offer's detail issue the same
 * number of SQL statements whether a member offers a few or many Lager rows (REQ-DATA-003, plan
 * §7.5 P3-5d-4): the offered rows come in per page, never per offer.
 */
@SpringBootTest
class MaterialExchangeBoardNoNPlusOneTest {

  private static final int FEW = 3;

  private static final int MANY = 25;

  /** Ceiling per read; a per-offer lookup would exceed it at {@value #MANY} offers. */
  private static final int STATEMENT_BOUND = 15;

  @Autowired private MaterialExchangeBoardService boardService;
  @Autowired private MaterialExchangeOfferRepository offerRepository;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private LocationRepository locationRepository;
  @Autowired private EntityManagerFactory entityManagerFactory;

  private final List<Object> created = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    SecurityContextHolder.clearContext();
    for (Object entity : created.reversed()) {
      switch (entity) {
        case MaterialExchangeOffer offer -> offerRepository.deleteById(offer.getId());
        case InventoryItem item -> inventoryItemRepository.deleteById(item.getId());
        case Material material -> materialRepository.deleteById(material.getId());
        case Location location -> locationRepository.deleteById(location.getId());
        case User user -> userRepository.deleteById(user.getId());
        default -> throw new IllegalStateException("unexpected " + entity);
      }
    }
    created.clear();
  }

  @Test
  void theBoardAndAnOffersDetailStayStatementBoundedWhateverTheOffers() {
    List<MaterialExchangeOffer> few = seed(FEW);
    List<MaterialExchangeOffer> many = seed(MANY);
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);

    long fewBoard = boardStatements(stats, few);
    long manyBoard = boardStatements(stats, many);
    assertThat(manyBoard)
        .as("board statements for " + MANY + " offers against " + FEW + " (N+1)")
        .isEqualTo(fewBoard)
        .isLessThanOrEqualTo(STATEMENT_BOUND);

    signInAs(many.getFirst().getOwner());
    stats.clear();
    var detail = boardService.detail(many.getFirst().getId());
    assertThat(detail.material()).isNotNull();
    assertThat(detail.amount()).isEqualTo(4.0);
    assertThat(stats.getPrepareStatementCount())
        .as("detail statements")
        .isLessThanOrEqualTo(STATEMENT_BOUND);
  }

  private long boardStatements(Statistics stats, List<MaterialExchangeOffer> offers) {
    signInAs(offers.getFirst().getOwner());
    stats.clear();
    PageResponse<MaterialExchangeOfferDto> board =
        boardService.board("mein", null, null, null, null, 0, 50, false);
    long statements = stats.getPrepareStatementCount();
    assertThat(board.content()).hasSize(offers.size());
    assertThat(board.content())
        .allSatisfy(
            dto -> {
              assertThat(dto.material()).isNotNull();
              assertThat(dto.quality()).isEqualTo(500);
              assertThat(dto.amount()).isEqualTo(4.0);
              assertThat(dto.availableAmount()).isEqualTo(10.0);
            });
    return statements;
  }

  private void signInAs(User user) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("board-reads")
                    .header("alg", "none")
                    .subject(user.getId().toString())
                    .build(),
                List.of(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))));
  }

  private List<MaterialExchangeOffer> seed(int offers) {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    User owner = new User();
    owner.setId(UUID.randomUUID());
    owner.setUsername("board-" + tag);
    owner = userRepository.save(owner);
    created.add(owner);
    Location location = new Location();
    location.setName("Board Depot " + tag);
    location = locationRepository.save(location);
    created.add(location);
    List<MaterialExchangeOffer> seeded = new ArrayList<>();
    for (int i = 0; i < offers; i++) {
      Material material = new Material();
      material.setName("Board Ore " + tag + " " + i);
      material.setType(MaterialType.REFINED);
      material.setQuantityType(QuantityType.SCU);
      material = materialRepository.save(material);
      created.add(material);
      InventoryItem item = new InventoryItem();
      item.setUser(owner);
      item.setMaterial(material);
      item.setLocation(location);
      item.setQuality(500);
      item.setAmount(10.0);
      item.setPersonal(false);
      item.setStolen(false);
      item = inventoryItemRepository.save(item);
      created.add(item);
      MaterialExchangeOffer offer = new MaterialExchangeOffer();
      offer.setKind(MaterialExchangeOfferKind.MATERIAL);
      offer.setInventoryItemId(item.getId());
      offer.setOwner(owner);
      offer.setOfferedAmount(4.0);
      offer.setStatus(MaterialExchangeOfferStatus.ACTIVE);
      offer.setReleasedAt(Instant.now());
      offer = offerRepository.save(offer);
      created.add(offer);
      seeded.add(offer);
    }
    return seeded;
  }
}
