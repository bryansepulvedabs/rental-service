package rent_a_car_bryan.rentalservice.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

// Cuerpo de PATCH /api/rentals/{id}/finish: la devolucion del auto
@Data
public class FinishRentalRequestDTO {

    @NotNull(message = "Debes indicar el kilometraje final")
    @Min(value = 0, message = "El kilometraje final no puede ser negativo")
    private Integer finalMileage;
}