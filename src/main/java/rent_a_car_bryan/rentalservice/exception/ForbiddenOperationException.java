package rent_a_car_bryan.rentalservice.exception;

// El mapeo a 403 Forbidden vive en GlobalExceptionHandler (handleForbiddenOperation),
// junto a las demás excepciones propias del servicio.
public class ForbiddenOperationException extends RuntimeException {
    public ForbiddenOperationException(String message) {
        super(message);
    }
}