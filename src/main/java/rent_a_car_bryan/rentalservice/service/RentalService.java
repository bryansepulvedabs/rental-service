package rent_a_car_bryan.rentalservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import rent_a_car_bryan.rentalservice.dto.CarInfoDTO;
import rent_a_car_bryan.rentalservice.dto.RentalRequestDTO;
import rent_a_car_bryan.rentalservice.dto.RentalResponseDTO;
import rent_a_car_bryan.rentalservice.dto.UserInfoDTO;
import rent_a_car_bryan.rentalservice.entity.RentalEntity;
import rent_a_car_bryan.rentalservice.entity.RentalState;
import rent_a_car_bryan.rentalservice.exception.ForbiddenOperationException;
import rent_a_car_bryan.rentalservice.exception.InvalidRentalException;
import rent_a_car_bryan.rentalservice.exception.ResourceNotFoundException;
import rent_a_car_bryan.rentalservice.exception.ServiceCommunicationException;
import rent_a_car_bryan.rentalservice.repository.RentalRepository;
import rent_a_car_bryan.rentalservice.security.ServiceTokenProvider;

import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RentalService {

    private final RentalRepository rentalRepository;
    private final RestTemplate restTemplate;
    private final ServiceTokenProvider serviceTokenProvider;

    @Value("${services.car-service.url}")
    private String carServiceUrl;

    @Value("${services.user-service.url}")
    private String userServiceUrl;

    public RentalResponseDTO createRental(RentalRequestDTO dto) {
        resolveUserId(dto);

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
        return rentalRepository.findAllByOrderByIdAsc().stream()
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
        enforceStatusChangeAllowed(entity, newStatus);

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

    // Si quien crea el arriendo es un CLIENT, el arriendo es para sí mismo: se ignora
    // cualquier userId que haya llegado en el body, y se usa el id del token (evita que
    // alguien reserve a nombre de otra persona). Si es personal (ADMIN/EMPLOYEE), debe
    // indicar explícitamente el cliente — por ejemplo, cuando llega al mostrador.
    private void resolveUserId(RentalRequestDTO dto) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isStaff = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_EMPLOYEE"));

        if (isStaff) {
            if (dto.getUserId() == null) {
                throw new InvalidRentalException("Debes indicar el cliente para crear el arriendo");
            }
        } else {
            dto.setUserId(Long.valueOf(auth.getName()));
        }
    }

    // El personal (ADMIN/EMPLOYEE) puede cambiar a cualquier estado. Un cliente solo puede
    // cancelar SU PROPIO arriendo, y solo mientras esté PENDIENTE — una vez retirado el auto
    // (ACTIVO), la cancelación la gestiona el mostrador, no el propio cliente.
    private void enforceStatusChangeAllowed(RentalEntity entity, RentalState newStatus) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isStaff = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_EMPLOYEE"));
        if (isStaff) {
            return;
        }

        boolean isOwner = auth.getName().equals(String.valueOf(entity.getUserId()));
        boolean isSelfCancelOfPending = newStatus == RentalState.CANCELADO
                && entity.getStatus() == RentalState.PENDIENTE;

        if (!isOwner || !isSelfCancelOfPending) {
            throw new ForbiddenOperationException("No puedes modificar este arriendo");
        }
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
            UserInfoDTO user = restTemplate.exchange(
                    userServiceUrl + "/api/users/{id}",
                    HttpMethod.GET,
                    serviceRequest(),
                    UserInfoDTO.class,
                    userId).getBody();
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
            restTemplate.exchange(
                    carServiceUrl + "/api/cars/{id}/availability?available={available}",
                    HttpMethod.PATCH,
                    serviceRequest(),
                    Void.class,
                    carId, available);
        } catch (RestClientException e) {
            throw new ServiceCommunicationException(
                    "Error al actualizar disponibilidad del auto " + carId + ": " + e.getMessage());
        }
    }

    // Las llamadas internas a otros servicios no llevan el token del usuario: rental-service
    // se identifica a sí mismo con un token de servicio de corta duración (rol SERVICE).
    private HttpEntity<Void> serviceRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(serviceTokenProvider.generateServiceToken());
        return new HttpEntity<>(headers);
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