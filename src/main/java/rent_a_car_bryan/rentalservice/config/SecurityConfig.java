package rent_a_car_bryan.rentalservice.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import rent_a_car_bryan.rentalservice.security.JwtAuthFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Lecturas públicas
                        .requestMatchers(HttpMethod.GET, "/api/rentals/**").permitAll()
                        // El cliente crea arriendos sin login todavía (usa un userId fijo en el frontend)
                        .requestMatchers(HttpMethod.POST, "/api/rentals").permitAll()
                        // Finalizar/cancelar/eliminar arriendos: ADMIN o EMPLOYEE
                        .anyRequest().hasAnyRole("ADMIN", "EMPLOYEE")
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}