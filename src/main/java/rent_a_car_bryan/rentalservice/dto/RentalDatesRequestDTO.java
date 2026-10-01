package rent_a_car_bryan.rentalservice.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

// Cuerpo de PATCH /api/rentals/{id}/dates
@Data
public class RentalDatesRequestDTO {

    @NotNull(message = "Debes indicar la fecha de inicio")
    private LocalDate startDate;

    @NotNull(message = "Debes indicar la fecha de término")
    private LocalDate endDate;
}