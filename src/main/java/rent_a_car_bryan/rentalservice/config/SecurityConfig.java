package rent_a_car_bryan.rentalservice.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
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
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // Consultas de disponibilidad: publicas, igual que el catalogo de autos.
                        .requestMatchers(HttpMethod.GET, "/api/rentals/availability").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/rentals/occupied").permitAll()
                        // Eliminados: lista, ficha y reactivar, solo ADMIN. Van ANTES que las reglas
                        // generales, si no matchea primero la que deja pasar a EMPLOYEE.
                        .requestMatchers(HttpMethod.GET, "/api/rentals/deleted").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/rentals/admin/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/rentals/*/restore").hasRole("ADMIN")
                        // Crear un arriendo exige sesión
                        .requestMatchers(HttpMethod.POST, "/api/rentals").authenticated()
                        // Arriendos de un usuario: el propio usuario, o el personal (ADMIN/EMPLOYEE)
                        .requestMatchers(HttpMethod.GET, "/api/rentals/user/{userId}")
                        .access((authentication, context) -> new AuthorizationDecision(
                                isStaffOrOwner(authentication.get(), context.getVariables().get("userId"))))
                        // Cambiar estado: cualquier sesión puede intentarlo; el service decide si corresponde
                        .requestMatchers(HttpMethod.PATCH, "/api/rentals/*/status").authenticated()
                        // Todo lo demás (listado, por id, por auto, eliminar): ADMIN o EMPLOYEE
                        .anyRequest().hasAnyRole("ADMIN", "EMPLOYEE")
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static boolean isStaffOrOwner(Authentication auth, String userId) {
        if (auth == null) {
            return false;
        }
        boolean staff = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_EMPLOYEE"));
        return staff || auth.getName().equals(userId);
    }
}