package rent_a_car_bryan.rentalservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import rent_a_car_bryan.rentalservice.dto.CarInfoDTO;
import rent_a_car_bryan.rentalservice.dto.RentalRequestDTO;
import rent_a_car_bryan.rentalservice.dto.RentalResponseDTO;
import rent_a_car_bryan.rentalservice.dto.UserInfoDTO;
import rent_a_car_bryan.rentalservice.entity.RentalEntity;
import rent_a_car_bryan.rentalservice.entity.RentalState;
import rent_a_car_bryan.rentalservice.exception.InvalidRentalException;
import rent_a_car_bryan.rentalservice.exception.ResourceNotFoundException;
import rent_a_car_bryan.rentalservice.exception.ServiceCommunicationException;
import rent_a_car_bryan.rentalservice.repository.RentalRepository;

import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RentalService {

    private final RentalRepository rentalRepository;
    private final RestTemplate restTemplate;

    @Value("${services.car-service.url}")
    private String carServiceUrl;

    @Value("${services.user-service.url}")
    private String userServiceUrl;

    public RentalResponseDTO createRental(RentalRequestDTO dto) {
        CarInfoDTO car = fetchCar(dto.getCarId());
        UserInfoDTO user = fetchUser(dto.getUserId());

        long days = ChronoUnit.DAYS.between(dto.getStartDate(), dto.getEndDate());
        if (days <= 0) {
            throw new InvalidRentalException("La fecha de término debe ser posterior a la de inicio");
        }

        RentalEntity entity = new RentalEntity();
        entity.setCarId(dto.getCarId());
        entity.setUserId(dto.getUserId());
        entity.setStartDate(dto.getStartDate());
        entity.setEndDate(dto.getEndDate());
        entity.setStatus(RentalState.PENDIENTE);
        entity.setTotalPrice(car.getDailyRate() * days);

        RentalEntity saved = rentalRepository.save(entity);

        updateCarAvailability(dto.getCarId(), false);

        return toResponseDTO(saved, car, user);
    }

    public RentalResponseDTO getRentalById(Long id) {
        RentalEntity entity = findEntityById(id);
        return toResponseDTO(entity, fetchCar(entity.getCarId()), fetchUser(entity.getUserId()));
    }

    public List<RentalResponseDTO> getAllRentals() {
        return rentalRepository.findAll().stream()
                .map(entity -> toResponseDTO(entity, fetchCar(entity.getCarId()), fetchUser(entity.getUserId())))
                .toList();
    }

    public List<RentalResponseDTO> getRentalsByCarId(Long carId) {
        return rentalRepository.findByCarId(carId).stream()
                .map(entity -> toResponseDTO(entity, fetchCar(entity.getCarId()), fetchUser(entity.getUserId())))
                .toList();
    }

    public List<RentalResponseDTO> getRentalsByUserId(Long userId) {
        return rentalRepository.findByUserId(userId).stream()
                .map(entity -> toResponseDTO(entity, fetchCar(entity.getCarId()), fetchUser(entity.getUserId())))
                .toList();
    }

    public RentalResponseDTO updateRentalStatus(Long id, RentalState newStatus) {
        RentalEntity entity = findEntityById(id);
        entity.setStatus(newStatus);
        RentalEntity updated = rentalRepository.save(entity);

        if (newStatus == RentalState.FINALIZADO || newStatus == RentalState.CANCELADO) {
            updateCarAvailability(updated.getCarId(), true);
        }

        return toResponseDTO(updated, fetchCar(updated.getCarId()), fetchUser(updated.getUserId()));
    }

    public void deleteRental(Long id) {
        RentalEntity entity = findEntityById(id);
        rentalRepository.deleteById(entity.getId());
    }

    private RentalEntity findEntityById(Long id) {
        return rentalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Arriendo no encontrado con id : " + id));
    }

    private CarInfoDTO fetchCar(Long carId) {
        try {
            CarInfoDTO car = restTemplate.getForObject(
                    carServiceUrl + "/api/cars/{id}", CarInfoDTO.class, carId);
            if (car == null) {
                throw new ResourceNotFoundException("Auto no encontrado con id: " + carId);
            }
            return car;
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (RestClientException e) {
            throw new ServiceCommunicationException("Error al comunicarse con car-service: " + e.getMessage());
        }
    }

    private UserInfoDTO fetchUser(Long userId) {
        try {
            UserInfoDTO user = restTemplate.getForObject(
                    userServiceUrl + "/api/users/{id}", UserInfoDTO.class, userId);
            if (user == null) {
                throw new ResourceNotFoundException("Usuario no encontrado con id: " + userId);
            }
            return user;
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (RestClientException e) {
            throw new ServiceCommunicationException("Error al comunicarse con user-service: " + e.getMessage());
        }
    }

    private void updateCarAvailability(Long carId, boolean available) {
        try {
            restTemplate.patchForObject(
                    carServiceUrl + "/api/cars/{id}/availability?available={available}",
                    null, Void.class, carId, available);
        } catch (RestClientException e) {
            throw new ServiceCommunicationException(
                    "Error al actualizar disponibilidad del auto " + carId + ": " + e.getMessage());
        }
    }

    private RentalResponseDTO toResponseDTO(RentalEntity entity, CarInfoDTO car, UserInfoDTO user) {
        RentalResponseDTO dto = new RentalResponseDTO();
        dto.setId(entity.getId());
        dto.setCar(car);
        dto.setUser(user);
        dto.setStartDate(entity.getStartDate());
        dto.setEndDate(entity.getEndDate());
        dto.setStatus(entity.getStatus());
        dto.setTotalPrice(entity.getTotalPrice());
        return dto;
    }
}