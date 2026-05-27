package com.plum.reader.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableConfigurationProperties(JwtProperties::class)
class SecurityConfig(
    @Value("\${plum.cors.allowed-origins:*}") private val allowedOriginsRaw: String,
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder =
        PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            val origins = allowedOriginsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            // allowCredentials + "*" is forbidden by browsers — use patterns when "*".
            if (origins.singleOrNull() == "*") {
                allowedOriginPatterns = listOf("*")
            } else {
                allowedOrigins = origins
            }
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "Accept", "Origin")
            exposedHeaders = listOf("Location")
            allowCredentials = true
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", config)
        }
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity, jwtFilter: JwtAuthenticationFilter): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { /* uses corsConfigurationSource() bean above */ }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                it.requestMatchers(*PUBLIC_PATHS).permitAll()
                it.anyRequest().authenticated()
            }
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    companion object {
        val PUBLIC_PATHS = arrayOf(
            "/api/v1/health",
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/actuator/health/**",
            "/actuator/info",
        )
    }
}

/**
 * Fail-fast guard: outside of local/test profiles, refuse to start with the
 * baked-in JWT secret. Caught early — better than shipping predictable tokens.
 */
@Configuration
@Profile("!local & !test & !default")
class JwtSecretGuard(props: JwtProperties) {
    init {
        require(props.secret != JwtProperties.DEV_DEFAULT_SECRET) {
            "plum.jwt.secret is set to the dev default. Override PLUM_JWT_SECRET before starting in this profile."
        }
    }
}
