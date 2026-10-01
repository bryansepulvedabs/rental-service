package rent_a_car_bryan.rentalservice.dto;

import lombok.Data;

@Data
public class CarInfoDTO {
    private Long id;
    private String licensePlate;
    private String brand;
    private String model;
    private Long dailyRate;
    // Kilometraje actual del auto: lo necesita la devolucion para validar que el
    // kilometraje final no sea menor
    private Integer mileage;
}