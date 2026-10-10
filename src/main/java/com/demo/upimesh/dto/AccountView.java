package com.demo.upimesh.dto;

import com.demo.upimesh.model.Account;

import java.math.BigDecimal;

public record AccountView(String vpa, String holderName, BigDecimal balance) {
    public static AccountView of(Account a) {
        return new AccountView(a.getVpa(), a.getHolderName(), a.getBalance());
    }
}
