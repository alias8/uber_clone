package org.example.config

import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.AbstractPlatformTransactionManager

@Configuration
class TransactionConfig {

    /**
     * Fail loudly when a method joins a transaction that doesn't match what it asked for.
     *
     * The case this catches: a @Transactional(readOnly = true) method calls, somewhere down the
     * stack, a @Transactional(readOnly = false) method. By default the inner method just joins the
     * read-only transaction, and then either:
     *  - its save() is silently never flushed (Hibernate's flush mode is MANUAL when read-only), or
     *  - its SQL hits Postgres / the replica and fails with "cannot execute UPDATE in a read-only
     *    transaction", far from the real mistake.
     * With this on, Spring throws IllegalTransactionStateException at the moment the write method
     * is called, naming both methods.
     *
     * Also catches joining with a stricter isolation level than the running transaction has.
     * Spring Boot leaves it off by default.
     */
    @Bean
    fun validateExistingTransactions() = TransactionManagerCustomizer<AbstractPlatformTransactionManager> {
        it.isValidateExistingTransaction = true
    }
}
