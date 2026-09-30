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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
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

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RentalService {

    private static final List<RentalState> BLOCKING_STATES =
            List.of(RentalState.PENDIENTE, RentalState.ACTIVO);

    private final RentalRepository rentalRepository;
    private final RestTemplate restTemplate;
    private final ServiceTokenProvider serviceTokenProvider;

    @Value("${services.car-service.url}")
    private String carServiceUrl;

    @Value("${services.user-service.url}")
    private String userServiceUrl;

    public RentalResponseDTO createRental(RentalRequestDTO dto) {
        resolveUserId(dto);
        validateDates(dto.getStartDate(), dto.getEndDate());

        if (rentalRepository.existsOverlapping(
                dto.getCarId(), dto.getStartDate(), dto.getEndDate(), BLOCKING_STATES)) {
            throw new InvalidRentalException("El auto ya está arrendado entre esas fechas");
        }

        // Estricto: no se crea un arriendo sobre un auto o cliente que no existe o fue dado de baja
        CarInfoDTO car = fetchCar(dto.getCarId());
        UserInfoDTO user = fetchUser(dto.getUserId());

        long days = ChronoUnit.DAYS.between(dto.getStartDate(), dto.getEndDate());

        RentalEntity entity = new RentalEntity();
        entity.setCarId(dto.getCarId());
        entity.setUserId(dto.getUserId());
        entity.setStartDate(dto.getStartDate());
        entity.setEndDate(dto.getEndDate());
        entity.setStatus(RentalState.PENDIENTE);
        entity.setTotalPrice(car.getDailyRate() * days);

        RentalEntity saved = rentalRepository.save(entity);

        return toResponseDTO(saved, car, user);
    }

    public boolean isCarAvailable(Long carId, LocalDate startDate, LocalDate endDate) {
        validateDates(startDate, endDate);
        return !rentalRepository.existsOverlapping(carId, startDate, endDate, BLOCKING_STATES);
    }

    public List<Long> getOccupiedCarIds(LocalDate startDate, LocalDate endDate) {
        validateDates(startDate, endDate);
        return rentalRepository.findOccupiedCarIds(startDate, endDate, BLOCKING_STATES);
    }

    public RentalResponseDTO getRentalById(Long id) {
        RentalEntity entity = findEntityById(id);
        return toResponseDTO(entity);
    }

    // Ficha para el admin: incluye arriendos dados de baja, para revisar su historial.
    // La respuesta trae deleted = true cuando corresponde.
    public RentalResponseDTO getRentalByIdIncludingDeleted(Long id) {
        RentalEntity entity = rentalRepository.findAnyById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Arriendo no encontrado con id : " + id));
        return toResponseDTO(entity);
    }

    public List<RentalResponseDTO> getAllRentals() {
        return rentalRepository.findAllByOrderByIdAsc().stream()
                .map(this::toResponseDTO)
                .toList();
    }

    // Arriendos dados de baja, para que el admin pueda reactivarlos
    public List<RentalResponseDTO> getAllDeletedRentals() {
        return rentalRepository.findAllDeleted().stream()
                .map(this::toResponseDTO)
                .toList();
    }

    public List<RentalResponseDTO> getRentalsByCarId(Long carId) {
        return rentalRepository.findByCarId(carId).stream()
                .map(this::toResponseDTO)
                .toList();
    }

    public List<RentalResponseDTO> getRentalsByUserId(Long userId) {
        return rentalRepository.findByUserId(userId).stream()
                .map(this::toResponseDTO)
                .toList();
    }

    public RentalResponseDTO updateRentalStatus(Long id, RentalState newStatus) {
        RentalEntity entity = findEntityById(id);
        enforceStatusChangeAllowed(entity, newStatus);

        boolean wasReleased = !BLOCKING_STATES.contains(entity.getStatus());
        if (wasReleased && BLOCKING_STATES.contains(newStatus)
                && rentalRepository.existsOverlappingExcluding(
                entity.getCarId(), entity.getStartDate(), entity.getEndDate(),
                BLOCKING_STATES, entity.getId())) {
            throw new InvalidRentalException(
                    "No se puede reactivar: el auto ya fue arrendado en esas fechas");
        }

        entity.setStatus(newStatus);
        RentalEntity updated = rentalRepository.save(entity);

        return toResponseDTO(updated);
    }

    public void deleteRental(Long id) {
        RentalEntity entity = findEntityById(id);
        rentalRepository.deleteById(entity.getId());
    }

    // Reactivar un arriendo dado de baja. Si en el intertanto se creo otro arriendo
    // sobre el mismo auto en fechas solapadas y este esta en PENDIENTE o ACTIVO, no
    // se permite: quedarian dos reservas simultaneas del mismo auto.
    @Transactional
    public RentalResponseDTO restoreRental(Long id) {
        RentalEntity deleted = rentalRepository.findDeletedById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Arriendo no encontrado o no estaba dado de baja: " + id));

        if (BLOCKING_STATES.contains(deleted.getStatus())
                && rentalRepository.existsOverlappingExcluding(
                deleted.getCarId(), deleted.getStartDate(), deleted.getEndDate(),
                BLOCKING_STATES, deleted.getId())) {
            throw new InvalidRentalException(
                    "No se puede reactivar: el auto ya fue arrendado en esas fechas");
        }

        rentalRepository.restoreById(id);
        deleted.setDeleted(false);
        return toResponseDTO(deleted);
    }

    private void validateDates(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new InvalidRentalException("Debes indicar la fecha de inicio y la de término");
        }
        if (!endDate.isAfter(startDate)) {
            throw new InvalidRentalException("La fecha de término debe ser posterior a la de inicio");
        }
    }

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

    // ---- Llamadas estrictas: para CREAR. Un auto o cliente dado de baja = 404 ----

    private CarInfoDTO fetchCar(Long carId) {
        try {
            CarInfoDTO car = restTemplate.exchange(
                    carServiceUrl + "/api/cars/{id}",
                    HttpMethod.GET,
                    serviceRequest(),
                    CarInfoDTO.class,
                    carId).getBody();
            if (car == null) {
                throw new ResourceNotFoundException("Auto no encontrado con id: " + carId);
            }
            return car;
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResourceNotFoundException("Auto no encontrado con id: " + carId);
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
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResourceNotFoundException("Usuario no encontrado con id: " + userId);
        } catch (RestClientException e) {
            throw new ServiceCommunicationException("Error al comunicarse con user-service: " + e.getMessage());
        }
    }

    // ---- Llamadas para MOSTRAR historial ----
    // Usan la ficha de admin del otro servicio, que incluye autos y clientes dados de
    // baja: asi el historial conserva los datos reales (marca, patente, nombre...) en
    // vez de un marcador. Solo si el registro ya no existe del todo se cae al marcador.
    // Requiere que car-service y user-service permitan el rol SERVICE en esa ruta.

    private CarInfoDTO fetchCarOrPlaceholder(Long carId) {
        try {
            CarInfoDTO car = restTemplate.exchange(
                    carServiceUrl + "/api/cars/admin/{id}",
                    HttpMethod.GET,
                    serviceRequest(),
                    CarInfoDTO.class,
                    carId).getBody();
            return car != null ? car : carPlaceholder(carId);
        } catch (HttpClientErrorException.NotFound e) {
            return carPlaceholder(carId);
        } catch (RestClientException e) {
            throw new ServiceCommunicationException("Error al comunicarse con car-service: " + e.getMessage());
        }
    }

    private UserInfoDTO fetchUserOrPlaceholder(Long userId) {
        try {
            UserInfoDTO user = restTemplate.exchange(
                    userServiceUrl + "/api/users/admin/{id}",
                    HttpMethod.GET,
                    serviceRequest(),
                    UserInfoDTO.class,
                    userId).getBody();
            return user != null ? user : userPlaceholder(userId);
        } catch (HttpClientErrorException.NotFound e) {
            return userPlaceholder(userId);
        } catch (RestClientException e) {
            throw new ServiceCommunicationException("Error al comunicarse con user-service: " + e.getMessage());
        }
    }

    private CarInfoDTO carPlaceholder(Long carId) {
        CarInfoDTO placeholder = new CarInfoDTO();
        placeholder.setId(carId);
        placeholder.setBrand("Auto no disponible");
        placeholder.setModel("");
        placeholder.setLicensePlate("—");
        placeholder.setDailyRate(0L);
        return placeholder;
    }

    private UserInfoDTO userPlaceholder(Long userId) {
        UserInfoDTO placeholder = new UserInfoDTO();
        placeholder.setId(userId);
        placeholder.setFirstName("Cliente no disponible");
        placeholder.setLastName("");
        placeholder.setEmail("—");
        return placeholder;
    }

    private HttpEntity<Void> serviceRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(serviceTokenProvider.generateServiceToken());
        return new HttpEntity<>(headers);
    }

    private RentalResponseDTO toResponseDTO(RentalEntity entity) {
        return toResponseDTO(entity,
                fetchCarOrPlaceholder(entity.getCarId()),
                fetchUserOrPlaceholder(entity.getUserId()));
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
        dto.setDeleted(Boolean.TRUE.equals(entity.getDeleted()));
        return dto;
    }
}