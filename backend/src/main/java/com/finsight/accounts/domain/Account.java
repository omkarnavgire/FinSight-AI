package com.finsight.accounts.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA entity for a user's synthetic financial account ({@code accounts}, V3).
 *
 * <h2>Balance rules (FR-02-1, FR-02-2, AC-BAL-1, AC-BAL-7)</h2>
 * <ul>
 *   <li>{@code openingBalance} is set once at creation and is never updated.</li>
 *   <li>{@code balance} starts equal to {@code openingBalance}. After that it is changed only by
 *       the guarded SQL in {@code AccountRepository.applyDelta}. Both columns are
 *       {@code updatable = false}, so saving this entity can never overwrite them.</li>
 * </ul>
 *
 * <p>{@code userId} is a plain ID: {@code users} belongs to the auth module, so there is no
 * entity relationship and no database foreign key (design.md FK policy).
 *
 * <p>No Jackson annotations — the API uses {@code AccountDto}.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private AccountType type;

    @Column(name = "opening_balance", nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal openingBalance;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal balance;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Account() {}

    /**
     * Opens a new active account whose balance equals its opening balance (AC-BAL-1).
     *
     * @param userId         owner, taken from the security context by the caller
     * @param name           display name
     * @param type           account type
     * @param openingBalance starting value; may be negative (e.g. CREDIT)
     * @param currency       ISO-style three-letter code
     * @return a new, unsaved account
     */
    public static Account open(Long userId, String name, AccountType type,
                               BigDecimal openingBalance, String currency) {
        var account = new Account();
        account.userId = userId;
        account.name = name;
        account.type = type;
        account.openingBalance = openingBalance;
        account.balance = openingBalance;
        account.currency = currency;
        account.active = true;
        account.createdAt = LocalDateTime.now();
        return account;
    }

    /** Soft-deletes the account (FR-02-3). Idempotent; balances are untouched. */
    public void deactivate() {
        this.active = false;
    }

    public Long getId() { return id; }

    public Long getUserId() { return userId; }

    public String getName() { return name; }

    public AccountType getType() { return type; }

    public BigDecimal getOpeningBalance() { return openingBalance; }

    public BigDecimal getBalance() { return balance; }

    public String getCurrency() { return currency; }

    public boolean isActive() { return active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
