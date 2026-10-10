package com.demo.upimesh.model;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    /** SELECT ... FOR UPDATE: serialises concurrent settlements touching the same account. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.vpa = :vpa")
    Optional<Account> findForUpdate(@Param("vpa") String vpa);

    List<Account> findByVpaNotLikeOrderByVpa(String pattern);
}
