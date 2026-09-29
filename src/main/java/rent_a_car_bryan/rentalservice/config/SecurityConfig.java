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
                // Sin token (o vencido) responde 401; con token pero sin permiso, 403
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // Crear un arriendo exige sesión: si es CLIENT, se arrienda a sí mismo (el service
                        // ignora el userId del body); si es personal, indica para qué cliente es
                        .requestMatchers(HttpMethod.POST, "/api/rentals").authenticated()
                        // Arriendos de un usuario: el propio usuario, o el personal (ADMIN/EMPLOYEE)
                        .requestMatchers(HttpMethod.GET, "/api/rentals/user/{userId}")
                        .access((authentication, context) -> new AuthorizationDecision(
                                isStaffOrOwner(authentication.get(), context.getVariables().get("userId"))))
                        // Cambiar estado: cualquier sesión puede intentarlo; el service decide si corresponde
                        // (el personal puede cualquier cambio; un cliente solo puede cancelar SU propio
                        // arriendo mientras esté PENDIENTE — ver RentalService.enforceStatusChangeAllowed)
                        .requestMatchers(HttpMethod.PATCH, "/api/rentals/*/status").authenticated()
                        // Todo lo demás (listado, por id, por auto, eliminar): ADMIN o EMPLOYEE
                        .anyRequest().hasAnyRole("ADMIN", "EMPLOYEE")
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    // El "name" de la autenticación es el subject del JWT, es decir, el id del usuario (ver JwtService en user-service)
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