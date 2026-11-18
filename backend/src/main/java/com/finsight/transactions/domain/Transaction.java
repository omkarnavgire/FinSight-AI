package com.finsight.transactions.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * JPA entity for a financial transaction ({@code transactions}, V4).
 *
 * <p>Ownership is indirect: {@code accountId} → {@code accounts.user_id}. {@code accountId} is a
 * plain ID because {@code Account} belongs to the accounts module (no cross-module entity reference,
 * no database foreign key — design.md FK policy).
 *
 * <p>Fields that FR-03-4 does not allow a user to edit ({@code accountId}, {@code countryCode},
 * {@code manual}, {@code createdAt}) are {@code updatable = false}.
 *
 * <p>{@code countryCode} is untrusted client metadata (design.md). It is validated against the
 * ISO allowlist and stored; it is never used for access decisions.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Category category;

    @Column(length = 255)
    private String description;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country_code", nullable = false, updatable = false, length = 2)
    private String countryCode;

    @Column(nullable = false, updatable = false)
    private boolean manual;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Transaction() {}

    /**
     * Creates a new user-entered (manual) transaction.
     *
     * @param accountId       owning account; ownership must be checked by the caller
     * @param amount          signed amount; negative = debit
     * @param category        resolved or user-supplied category
     * @param description     optional free text
     * @param transactionDate business date
     * @param countryCode     already-validated ISO 3166-1 alpha-2 code
     * @return a new, unsaved transaction
     */
    public static Transaction manual(Long accountId, BigDecimal amount, Category category,
                                     String description, LocalDate transactionDate, String countryCode) {
        var t = new Transaction();
        t.accountId = accountId;
        t.amount = amount;
        t.category = category;
        t.description = description;
        t.transactionDate = transactionDate;
        t.countryCode = countryCode;
        t.manual = true;
        t.createdAt = LocalDateTime.now();
        return t;
    }

    /** Applies the FR-03-4 editable fields. The balance delta is the caller's job. */
    public void edit(BigDecimal newAmount, LocalDate newDate, Category newCategory, String newDescription) {
        this.amount = newAmount;
        this.transactionDate = newDate;
        this.category = newCategory;
        this.description = newDescription;
    }

    /** Overrides the category (FR-03-8). Has no balance effect. */
    public void overrideCategory(Category newCategory) {
        this.category = newCategory;
    }

    public Long getId() { return id; }

    public Long getAccountId() { return accountId; }

    public BigDecimal getAmount() { return amount; }

    public Category getCategory() { return category; }

    public String getDescription() { return description; }

    public LocalDate getTransactionDate() { return transactionDate; }

    public String getCountryCode() { return countryCode; }

    public boolean isManual() { return manual; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
