package com.eve.diagnostics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * "Centre X offers test Y for price Z."
 *
 * <p>This is a many-to-many relationship that carries data, so it gets its own
 * table instead of a plain {@code @ManyToMany}, which cannot hold a price.
 * UNIQUE (centre_id, test_id) in the schema means the same test cannot be
 * offered twice at two different prices.
 */
@Entity
@Table(name = "centre_tests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CentreTest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "centre_id", nullable = false)
    private DiagnosticCentre centre;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "test_id", nullable = false)
    private DiagnosticTest test;

    /** Always scale 2, never a double - 0.10 is not representable as a double. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;
}