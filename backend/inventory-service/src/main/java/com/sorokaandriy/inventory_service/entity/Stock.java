package com.sorokaandriy.inventory_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "stocks")
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    @Column(name = "on_hand", nullable = false)
    @Builder.Default
    private Long onHand = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Long reserved = 0L;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
