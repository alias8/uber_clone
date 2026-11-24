package org.example.config

import org.example.security.JwtFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/**
 * Every servlet filter the app adds, in one place, so the order is visible.
 *
 * Spring Boot auto-registers ANY bean that is a servlet Filter into Tomcat's chain. That's easy to
 * miss (nothing references the class) and easy to get wrong (a filter that's also added inside
 * Spring Security ends up registered twice). So filters are registered here explicitly instead.
 *
 * Resulting order:
 *   1. LoadSheddingFilter          (HIGHEST_PRECEDENCE: reject the excess before any work)
 *   2. springSecurityFilterChain   (order -100, Spring Security's own single filter)
 *        └─ JwtFilter              (added inside it by SecurityConfig.addFilterBefore)
 *   3. DispatcherServlet → controllers
 */
@Configuration
class FilterConfig {

    @Bean
    fun loadSheddingFilter(
        @Value("\${app.load-shedding.max-in-flight:150}") maxInFlight: Int,
        @Value("\${app.load-shedding.reserved-for-critical:30}") reservedForCritical: Int
    ) = FilterRegistrationBean(LoadSheddingFilter(maxInFlight, reservedForCritical)).apply {
        order = Ordered.HIGHEST_PRECEDENCE
        addUrlPatterns("/*")
    }

    /**
     * JwtFilter stays a @Component so SecurityConfig can inject it and add it to the security chain.
     * Being a Filter bean, Boot would ALSO register it at the servlet level, so it was registered
     * twice (harmless only because OncePerRequestFilter skips the second run). Disabling the
     * servlet-level registration leaves just the one inside Spring Security.
     */
    @Bean
    fun jwtFilterServletRegistration(jwtFilter: JwtFilter) = FilterRegistrationBean(jwtFilter).apply {
        isEnabled = false
    }
}
