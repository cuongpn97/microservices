package com.webshop.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "products")
public class Product {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    /** Giá theo VND, kiểu long (số nguyên). Không bao giờ dùng double/float cho tiền. */
    @Column(name = "price_vnd", nullable = false)
    private long priceVnd;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Product() {
    }

    public Product(UUID id, String name, String description, long priceVnd, boolean active, Instant now) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.priceVnd = priceVnd;
        this.active = active;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, String description, long priceVnd, boolean active, Instant now) {
        this.name = name;
        this.description = description;
        this.priceVnd = priceVnd;
        this.active = active;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public long getPriceVnd() {
        return priceVnd;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
