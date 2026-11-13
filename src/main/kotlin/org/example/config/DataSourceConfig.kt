package org.example.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import javax.sql.DataSource

/**
 * Two connection pools (primary and replica) behind one routing DataSource.
 *
 * Defining our own DataSource bean makes Spring Boot's DataSourceAutoConfiguration back off, so
 * the old spring.datasource.* properties are replaced by app.datasource.primary.* and
 * app.datasource.replica.* in application.properties.
 *
 * JPA/Hibernate and Flyway both use the @Primary bean below. Flyway runs outside any read-only
 * transaction, so migrations always go to the primary.
 */
@Configuration
class DataSourceConfig {

    @Bean
    @ConfigurationProperties("app.datasource.primary")
    fun primaryDataSource(): HikariDataSource = HikariDataSource().apply { poolName = "primary" }

    // Its own pool, so its own share of connections to size (see "Too many DB connections"):
    // each replica has its own max_connections, and every app instance opens this pool too.
    @Bean
    @ConfigurationProperties("app.datasource.replica")
    fun replicaDataSource(): HikariDataSource = HikariDataSource().apply {
        poolName = "replica"
        isReadOnly = true // belt and braces: the replica rejects writes anyway
    }

    @Bean
    @Primary
    fun dataSource(
        @Qualifier("primaryDataSource") primary: DataSource,
        @Qualifier("replicaDataSource") replica: DataSource
    ): DataSource {
        val routing = ReadWriteRoutingDataSource().apply {
            setTargetDataSources(mapOf<Any, Any>(DbTarget.PRIMARY to primary, DbTarget.REPLICA to replica))
            setDefaultTargetDataSource(primary)
            afterPropertiesSet()
        }
        // The important line. When a @Transactional method starts, the transaction manager asks
        // for a connection *before* it marks the transaction read-only, so without this proxy the
        // router always sees readOnly = false and everything silently goes to the primary.
        // The proxy hands back a placeholder and only picks a real connection on the first SQL
        // statement, by which time the read-only flag is set.
        return LazyConnectionDataSourceProxy(routing)
    }
}
