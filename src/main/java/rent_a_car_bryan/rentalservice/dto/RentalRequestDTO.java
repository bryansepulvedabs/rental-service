package rent_a_car_bryan.rentalservice.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class RentalRequestDTO {
    private Long carId;
    private Long userId;
    private LocalDate startDate;
    private LocalDate endDate;
}
