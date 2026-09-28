package rent_a_car_bryan.rentalservice.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

// Genera un JWT de muy corta duración para que rental-service se autentique
// ante car-service en llamadas internas (ej: actualizar disponibilidad de un auto).
// No hay un usuario detrás: es el servicio identificándose a sí mismo con rol ADMIN,
// firmado con el mismo JWT_SECRET compartido que valida car-service.
@Component
public class ServiceTokenProvider {

    private static final long EXPIRATION_MS = 60_000; // 1 minuto: sobra para una llamada interna

    private final SecretKey key;

    public ServiceTokenProvider(@Value("${jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateServiceToken() {
        Date now = new Date();
        return Jwts.builder()
                .subject("rental-service")
                .claim("role", "ADMIN")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + EXPIRATION_MS))
                .signWith(key)
                .compact();
    }
}