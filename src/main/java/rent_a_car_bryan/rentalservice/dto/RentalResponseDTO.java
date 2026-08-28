package rent_a_car_bryan.rentalservice.dto;

import lombok.Data;
import rent_a_car_bryan.rentalservice.entity.RentalState;

import java.time.LocalDate;

@Data
public class RentalResponseDTO {
    private Long id;
    private CarInfoDTO car;
    private UserInfoDTO user;
    private LocalDate startDate;
    private LocalDate endDate;
    private RentalState status;
    private Long totalPrice;
}
