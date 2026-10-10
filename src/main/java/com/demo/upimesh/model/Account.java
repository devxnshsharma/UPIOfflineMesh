package com.demo.upimesh.model;

import jakarta.persistence.*;

import java.math.BigDecimal;

/** A simulated bank account (fictional VPA). The backend owns the ledger in this prototype. */
@Entity
@Table(name = "accounts")
public class Account {

    /** VPAs ending in this suffix belong to networking experiments and are hidden from demo views. */
    public static final String SIM_SUFFIX = "@sim";

    @Id
    private String vpa;

    @Column(nullable = false)
    private String holderName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    /** Optimistic locking: a concurrent update that slipped past the row lock fails instead of overwriting. */
    @Version
    private Long version;

    protected Account() {}

    public Account(String vpa, String holderName, BigDecimal balance) {
        this.vpa = vpa;
        this.holderName = holderName;
        this.balance = balance;
    }

    public String getVpa() { return vpa; }
    public String getHolderName() { return holderName; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public Long getVersion() { return version; }
}
