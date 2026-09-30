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

    // Estados que ocupan el auto. FINALIZADO y CANCELADO liberan las fechas.
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

        // El auto ya no se marca como no disponible: la disponibilidad depende de las
        // fechas, asi que se valida que no haya otro arriendo solapado en ese periodo.
        if (rentalRepository.existsOverlapping(
                dto.getCarId(), dto.getStartDate(), dto.getEndDate(), BLOCKING_STATES)) {
            throw new InvalidRentalException("El auto ya está arrendado entre esas fechas");
        }

        // Acá sí, estricto: no se puede crear un arriendo sobre un auto o un cliente
        // que no existe (o que fue dado de baja).
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

    // Consulta puntual: sirve para el detalle de un auto y para validar el formulario
    // antes de enviarlo.
    public boolean isCarAvailable(Long carId, LocalDate startDate, LocalDate endDate) {
        validateDates(startDate, endDate);
        return !rentalRepository.existsOverlapping(carId, startDate, endDate, BLOCKING_STATES);
    }

    // Ids ocupados en el rango, para que el catalogo filtre de una sola llamada.
    public List<Long> getOccupiedCarIds(LocalDate startDate, LocalDate endDate) {
        validateDates(startDate, endDate);
        return rentalRepository.findOccupiedCarIds(startDate, endDate, BLOCKING_STATES);
    }

    public RentalResponseDTO getRentalById(Long id) {
        RentalEntity entity = findEntityById(id);
        return toResponseDTO(entity);
    }

    public List<RentalResponseDTO> getAllRentals() {
        return rentalRepository.findAllByOrderByIdAsc().stream()
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

        // Reactivar un arriendo liberado (cancelado o finalizado) vuelve a ocupar sus
        // fechas, asi que hay que revisar que en el intertanto no se hayan tomado.
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

    // Borrado logico: @SQLDelete en RentalEntity convierte esto en un UPDATE.
    // La fila queda en la base y desaparece de todas las consultas.
    public void deleteRental(Long id) {
        RentalEntity entity = findEntityById(id);
        rentalRepository.deleteById(entity.getId());
    }

    private void validateDates(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new InvalidRentalException("Debes indicar la fecha de inicio y la de término");
        }
        if (!endDate.isAfter(startDate)) {
            throw new InvalidRentalException("La fecha de término debe ser posterior a la de inicio");
        }
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

    // ---- Llamadas estrictas: 404 del otro servicio = error para quien pidió ----

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

    // ---- Llamadas tolerantes: para MOSTRAR historial ----
    // Un auto o un cliente dado de baja no puede hacer caer el listado completo de
    // arriendos. Las fechas, el estado y el total viven en RentalEntity, asi que el
    // historial sigue siendo correcto aunque el auto ya no exista.

    private CarInfoDTO fetchCarOrPlaceholder(Long carId) {
        try {
            return fetchCar(carId);
        } catch (ResourceNotFoundException e) {
            CarInfoDTO placeholder = new CarInfoDTO();
            placeholder.setId(carId);
            placeholder.setBrand("Auto dado de baja");
            placeholder.setModel("");
            placeholder.setLicensePlate("—");
            placeholder.setDailyRate(0L);
            return placeholder;
        }
    }

    private UserInfoDTO fetchUserOrPlaceholder(Long userId) {
        try {
            return fetchUser(userId);
        } catch (ResourceNotFoundException e) {
            UserInfoDTO placeholder = new UserInfoDTO();
            placeholder.setId(userId);
            placeholder.setFirstName("Cliente dado de baja");
            placeholder.setLastName("");
            placeholder.setEmail("—");
            return placeholder;
        }
    }

    // Las llamadas internas a otros servicios no llevan el token del usuario: rental-service
    // se identifica a sí mismo con un token de servicio de corta duración (rol SERVICE).
    private HttpEntity<Void> serviceRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(serviceTokenProvider.generateServiceToken());
        return new HttpEntity<>(headers);
    }

    // Version para mostrar: resuelve auto y cliente tolerando que ya no existan
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
        return dto;
    }
}