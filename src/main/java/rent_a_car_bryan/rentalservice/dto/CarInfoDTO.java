package rent_a_car_bryan.rentalservice.dto;

import lombok.Data;

@Data
public class CarInfoDTO {
    private Long id;
    private String licensePlate;
    private String brand;
    private String model;
    private Long dailyRate;
}
