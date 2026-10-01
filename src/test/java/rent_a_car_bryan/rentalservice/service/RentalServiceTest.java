package rent_a_car_bryan.rentalservice.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import rent_a_car_bryan.rentalservice.dto.CarInfoDTO;
import rent_a_car_bryan.rentalservice.dto.FinishRentalRequestDTO;
import rent_a_car_bryan.rentalservice.dto.RentalDatesRequestDTO;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests unitarios de la lógica de negocio de RentalService.
 *
 * Qué NO cubren (a propósito): la semántica de los bordes del solape de fechas (por ejemplo, que
 * el día de devolución quede libre) vive en la consulta JPQL de RentalRepository, no en el
 * servicio. Aquí solo se comprueba que el servicio la consulta con los estados correctos y que
 * reacciona bien a su respuesta. Los bordes se prueban contra PostgreSQL real en la segunda ronda.
 */
@ExtendWith(MockitoExtension.class)
class RentalServiceTest {

    private static final String CAR_URL = "http://car-service:8091";
    private static final String USER_URL = "http://user-service:8092";
    private static final long CAR_ID = 5L;
    private static final long USER_ID = 7L;
    private static final long RENTAL_ID = 100L;
    private static final long DAILY_RATE = 30_000L;

    @Mock private RentalRepository rentalRepository;
    @Mock private RestTemplate restTemplate;
    @Mock private ServiceTokenProvider serviceTokenProvider;

    @InjectMocks private RentalService service;

    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        // Los @Value no se inyectan en un test unitario: se fijan a mano
        ReflectionTestUtils.setField(service, "carServiceUrl", CAR_URL);
        ReflectionTestUtils.setField(service, "userServiceUrl", USER_URL);
        lenient().when(serviceTokenProvider.generateServiceToken()).thenReturn("service-token");
        // save() devuelve lo mismo que recibe, como haría JPA
        lenient().when(rentalRepository.save(any(RentalEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // =====================================================================================
    // Crear arriendo
    // =====================================================================================
    @Nested
    @DisplayName("Crear arriendo")
    class CreateRental {

        @Test
        @DisplayName("el personal debe indicar el cliente")
        void staffMustIndicateClient() {
            authenticateAs("1", "ROLE_EMPLOYEE");

            assertThatThrownBy(() -> service.createRental(request(null, today.plusDays(1), today.plusDays(4))))
                    .isInstanceOf(InvalidRentalException.class)
                    .hasMessageContaining("indicar el cliente");

            verifyNoInteractions(rentalRepository, restTemplate);
        }

        @Test
        @DisplayName("un CLIENT arrienda para sí mismo: se ignora el userId del body y se usa el del token")
        void clientUsesTokenIdAndIgnoresBody() {
            authenticateAs(String.valueOf(USER_ID), "ROLE_CLIENT");
            givenCarAndUserFound();

            service.createRental(request(99L, today.plusDays(1), today.plusDays(4)));

            ArgumentCaptor<RentalEntity> saved = ArgumentCaptor.forClass(RentalEntity.class);
            verify(rentalRepository).save(saved.capture());
            assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
            // y tampoco se consultó al usuario 99
            verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                    eq(UserInfoDTO.class), eq(99L));
        }

        @Test
        @DisplayName("el personal crea el arriendo a nombre del cliente indicado")
        void staffCreatesForIndicatedClient() {
            authenticateAs("1", "ROLE_ADMIN");
            givenCarAndUserFound();

            service.createRental(request(42L, today.plusDays(1), today.plusDays(4)));

            ArgumentCaptor<RentalEntity> saved = ArgumentCaptor.forClass(RentalEntity.class);
            verify(rentalRepository).save(saved.capture());
            assertThat(saved.getValue().getUserId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("calcula el total como tarifa diaria × días y queda PENDIENTE")
        void computesTotalAndStartsPending() {
            authenticateAs(String.valueOf(USER_ID), "ROLE_CLIENT");
            givenCarAndUserFound();

            RentalResponseDTO result = service.createRental(request(null, today.plusDays(1), today.plusDays(4)));

            ArgumentCaptor<RentalEntity> saved = ArgumentCaptor.forClass(RentalEntity.class);
            verify(rentalRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(RentalState.PENDIENTE);
            assertThat(saved.getValue().getTotalPrice()).isEqualTo(3 * DAILY_RATE);
            assertThat(result.getTotalPrice()).isEqualTo(3 * DAILY_RATE);
            assertThat(result.getCar().getBrand()).isEqualTo("Toyota");
        }

        @ParameterizedTest(name = "devolución {0} día(s) después del retiro")
        @ValueSource(ints = {0, -2})
        @DisplayName("rechaza fechas donde la devolución no es posterior al retiro")
        void rejectsReturnNotAfterStart(int offsetDays) {
            authenticateAs(String.valueOf(USER_ID), "ROLE_CLIENT");
            LocalDate start = today.plusDays(5);

            assertThatThrownBy(() -> service.createRental(request(null, start, start.plusDays(offsetDays))))
                    .isInstanceOf(InvalidRentalException.class)
                    .hasMessageContaining("posterior");

            verifyNoInteractions(rentalRepository, restTemplate);
        }

        @Test
        @DisplayName("rechaza si el auto ya está arrendado en esas fechas, sin llamar a otros servicios")
        void rejectsWhenCarAlreadyBooked() {
            authenticateAs(String.valueOf(USER_ID), "ROLE_CLIENT");
            LocalDate start = today.plusDays(1);
            LocalDate end = today.plusDays(4);
            when(rentalRepository.existsOverlapping(eq(CAR_ID), eq(start), eq(end), blocking())).thenReturn(true);

            assertThatThrownBy(() -> service.createRental(request(null, start, end)))
                    .isInstanceOf(InvalidRentalException.class)
                    .hasMessageContaining("ya está arrendado");

            verify(rentalRepository, never()).save(any());
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("un auto que no existe (o fue dado de baja) responde 404 y no se guarda nada")
        void carNotFound() {
            authenticateAs(String.valueOf(USER_ID), "ROLE_CLIENT");
            stubGetFailure(CAR_URL + "/api/cars/{id}", CarInfoDTO.class, notFound());

            assertThatThrownBy(() -> service.createRental(request(null, today.plusDays(1), today.plusDays(4))))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Auto no encontrado");

            verify(rentalRepository, never()).save(any());
        }

        @Test
        @DisplayName("si car-service no responde, se informa como error de comunicación")
        void carServiceDown() {
            authenticateAs(String.valueOf(USER_ID), "ROLE_CLIENT");
            stubGetFailure(CAR_URL + "/api/cars/{id}", CarInfoDTO.class, new ResourceAccessException("down"));

            assertThatThrownBy(() -> service.createRental(request(null, today.plusDays(1), today.plusDays(4))))
                    .isInstanceOf(ServiceCommunicationException.class)
                    .hasMessageContaining("car-service");

            verify(rentalRepository, never()).save(any());
        }
    }

    // =====================================================================================
    // Disponibilidad
    // =====================================================================================
    @Nested
    @DisplayName("Disponibilidad por fechas")
    class Availability {

        @Test
        @DisplayName("solo PENDIENTE y ACTIVO bloquean el auto; el auto está libre si no hay solape")
        void freeWhenNoOverlap() {
            LocalDate start = today.plusDays(1);
            LocalDate end = today.plusDays(3);

            assertThat(service.isCarAvailable(CAR_ID, start, end)).isTrue();

            verify(rentalRepository).existsOverlapping(eq(CAR_ID), eq(start), eq(end), blocking());
        }

        @Test
        @DisplayName("el auto no está disponible si hay solape")
        void notAvailableWhenOverlap() {
            LocalDate start = today.plusDays(1);
            LocalDate end = today.plusDays(3);
            when(rentalRepository.existsOverlapping(eq(CAR_ID), eq(start), eq(end), blocking())).thenReturn(true);

            assertThat(service.isCarAvailable(CAR_ID, start, end)).isFalse();
        }

        @Test
        @DisplayName("rechaza un rango inválido")
        void invalidRange() {
            assertThatThrownBy(() -> service.isCarAvailable(CAR_ID, today.plusDays(3), today.plusDays(3)))
                    .isInstanceOf(InvalidRentalException.class);
        }

        @Test
        @DisplayName("los autos ocupados se consultan con los mismos estados bloqueantes")
        void occupiedCarIds() {
            LocalDate start = today.plusDays(1);
            LocalDate end = today.plusDays(3);
            when(rentalRepository.findOccupiedCarIds(eq(start), eq(end), blocking())).thenReturn(List.of(5L, 9L));

            assertThat(service.getOccupiedCarIds(start, end)).containsExactly(5L, 9L);
        }
    }

    // =====================================================================================
    // Cambio de estado
    // =====================================================================================
    @Nested
    @DisplayName("Cambio de estado")
    class StatusChange {

        @Test
        @DisplayName("un CLIENT puede cancelar su propio arriendo mientras está PENDIENTE")
        void clientCancelsOwnPending() {
            authenticateAs(String.valueOf(USER_ID), "ROLE_CLIENT");
            givenRental(RentalState.PENDIENTE, today.plusDays(5), today.plusDays(8));
            givenRelatedDataForResponse(50_000);

            RentalResponseDTO result = service.updateRentalStatus(RENTAL_ID, RentalState.CANCELADO);

            assertThat(result.getStatus()).isEqualTo(RentalState.CANCELADO);
        }

        @Test
        @DisplayName("un CLIENT no puede tocar el arriendo de otra persona")
        void clientCannotTouchSomeoneElsesRental() {
            authenticateAs("8", "ROLE_CLIENT");
            givenRental(RentalState.PENDIENTE, today.plusDays(5), today.plusDays(8));

            assertThatThrownBy(() -> service.updateRentalStatus(RENTAL_ID, RentalState.CANCELADO))
                    .isInstanceOf(ForbiddenOperationException.class);

            verify(rentalRepository, never()).save(any());
        }

        @Test
        @DisplayName("un CLIENT no puede cancelar un arriendo que ya está ACTIVO (lo gestiona el mostrador)")
        void clientCannotCancelActive() {
            authenticateAs(String.valueOf(USER_ID), "ROLE_CLIENT");
            givenRental(RentalState.ACTIVO, today.minusDays(1), today.plusDays(2));

            assertThatThrownBy(() -> service.updateRentalStatus(RENTAL_ID, RentalState.CANCELADO))
                    .isInstanceOf(ForbiddenOperationException.class);
        }

        @Test
        @DisplayName("un CLIENT no puede pasar su arriendo a ACTIVO")
        void clientCannotActivate() {
            authenticateAs(String.valueOf(USER_ID), "ROLE_CLIENT");
            givenRental(RentalState.PENDIENTE, today.plusDays(5), today.plusDays(8));

            assertThatThrownBy(() -> service.updateRentalStatus(RENTAL_ID, RentalState.ACTIVO))
                    .isInstanceOf(ForbiddenOperationException.class);
        }

        @Test
        @DisplayName("primero se evalúan los permisos (403) y después la transición (400)")
        void permissionsAreCheckedBeforeTransition() {
            authenticateAs("8", "ROLE_CLIENT");
            givenRental(RentalState.FINALIZADO, today.minusDays(5), today.minusDays(2));

            assertThatThrownBy(() -> service.updateRentalStatus(RENTAL_ID, RentalState.ACTIVO))
                    .isInstanceOf(ForbiddenOperationException.class);
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({"PENDIENTE,ACTIVO", "PENDIENTE,CANCELADO", "ACTIVO,CANCELADO"})
        @DisplayName("el personal puede hacer las transiciones permitidas")
        void staffAllowedTransitions(RentalState from, RentalState to) {
            authenticateAs("1", "ROLE_EMPLOYEE");
            givenRental(from, today.plusDays(5), today.plusDays(8));
            givenRelatedDataForResponse(50_000);

            RentalResponseDTO result = service.updateRentalStatus(RENTAL_ID, to);

            assertThat(result.getStatus()).isEqualTo(to);
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                "PENDIENTE,FINALIZADO",
                "ACTIVO,PENDIENTE",
                "FINALIZADO,ACTIVO",
                "FINALIZADO,CANCELADO",
                "CANCELADO,ACTIVO",
                "CANCELADO,PENDIENTE"
        })
        @DisplayName("las transiciones no permitidas se rechazan y no se guarda nada")
        void invalidTransitions(RentalState from, RentalState to) {
            authenticateAs("1", "ROLE_ADMIN");
            givenRental(from, today.plusDays(5), today.plusDays(8));

            assertThatThrownBy(() -> service.updateRentalStatus(RENTAL_ID, to))
                    .isInstanceOf(InvalidRentalException.class)
                    .hasMessageContaining("No se puede pasar");

            verify(rentalRepository, never()).save(any());
        }

        @Test
        @DisplayName("pasar al mismo estado se rechaza")
        void sameStateIsRejected() {
            authenticateAs("1", "ROLE_ADMIN");
            givenRental(RentalState.PENDIENTE, today.plusDays(5), today.plusDays(8));

            assertThatThrownBy(() -> service.updateRentalStatus(RENTAL_ID, RentalState.PENDIENTE))
                    .isInstanceOf(InvalidRentalException.class)
                    .hasMessageContaining("ya está");
        }

        @Test
        @DisplayName("finalizar por /status no se permite: hay que registrar la devolución con el kilometraje")
        void finishingViaStatusIsRejected() {
            authenticateAs("1", "ROLE_ADMIN");
            givenRental(RentalState.ACTIVO, today.minusDays(1), today.plusDays(2));

            assertThatThrownBy(() -> service.updateRentalStatus(RENTAL_ID, RentalState.FINALIZADO))
                    .isInstanceOf(InvalidRentalException.class)
                    .hasMessageContaining("kilometraje final");

            verify(rentalRepository, never()).save(any());
        }

        @Test
        @DisplayName("un arriendo inexistente responde 404")
        void rentalNotFound() {
            authenticateAs("1", "ROLE_ADMIN");
            when(rentalRepository.findById(RENTAL_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateRentalStatus(RENTAL_ID, RentalState.ACTIVO))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // =====================================================================================
    // Editar fechas
    // =====================================================================================
    @Nested
    @DisplayName("Editar fechas")
    class UpdateDates {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = RentalState.class, names = {"FINALIZADO", "CANCELADO"})
        @DisplayName("un arriendo cerrado no se edita")
        void closedRentalsCannotBeEdited(RentalState status) {
            LocalDate start = today.plusDays(10);
            givenRental(status, start, start.plusDays(3));

            assertThatThrownBy(() -> service.updateRentalDates(RENTAL_ID, dates(start, start.plusDays(4))))
                    .isInstanceOf(InvalidRentalException.class)
                    .hasMessageContaining("no se puede editar");
        }

        @Test
        @DisplayName("rechaza fechas donde la devolución no es posterior al retiro")
        void rejectsInvalidRange() {
            LocalDate start = today.plusDays(10);
            givenRental(RentalState.PENDIENTE, start, start.plusDays(3));

            assertThatThrownBy(() -> service.updateRentalDates(RENTAL_ID, dates(start, start)))
                    .isInstanceOf(InvalidRentalException.class)
                    .hasMessageContaining("posterior");
        }

        @Test
        @DisplayName("con el auto ya retirado (ACTIVO) no se puede mover el retiro")
        void activeRentalCannotMoveStart() {
            LocalDate start = today.minusDays(1);
            givenRental(RentalState.ACTIVO, start, today.plusDays(2));

            assertThatThrownBy(() -> service.updateRentalDates(RENTAL_ID, dates(today, today.plusDays(2))))
                    .isInstanceOf(InvalidRentalException.class)
                    .hasMessageContaining("ya fue retirado");
        }

        @Test
        @DisplayName("un arriendo PENDIENTE no puede moverse a un retiro anterior a hoy")
        void pendingCannotMoveStartIntoThePast() {
            LocalDate start = today.plusDays(5);
            givenRental(RentalState.PENDIENTE, start, start.plusDays(3));

            assertThatThrownBy(() -> service.updateRentalDates(RENTAL_ID, dates(today.minusDays(1), start.plusDays(3))))
                    .isInstanceOf(InvalidRentalException.class)
                    .hasMessageContaining("anterior a hoy");
        }

        @Test
        @DisplayName("rechaza si las nuevas fechas chocan con otro arriendo del mismo auto")
        void rejectsOverlapWithAnotherRental() {
            LocalDate start = today.plusDays(10);
            LocalDate newEnd = start.plusDays(10);
            givenRental(RentalState.PENDIENTE, start, start.plusDays(3));
            when(rentalRepository.existsOverlappingExcluding(
                    eq(CAR_ID), eq(start), eq(newEnd), blocking(), eq(RENTAL_ID))).thenReturn(true);

            assertThatThrownBy(() -> service.updateRentalDates(RENTAL_ID, dates(start, newEnd)))
                    .isInstanceOf(InvalidRentalException.class)
                    .hasMessageContaining("ya está arrendado");

            verify(rentalRepository, never()).save(any());
        }

        @ParameterizedTest(name = "{0} días → total {1}")
        @CsvSource({"4,120000", "2,60000"})
        @DisplayName("el total se recalcula con la tarifa diaria del arriendo original (extender o acortar)")
        void recalculatesWithOriginalDailyRate(int newDays, long expectedTotal) {
            LocalDate start = today.plusDays(10);
            givenRental(RentalState.PENDIENTE, start, start.plusDays(3)); // total original: 3 × 30.000
            givenRelatedDataForResponse(50_000);

            RentalResponseDTO result = service.updateRentalDates(RENTAL_ID, dates(start, start.plusDays(newDays)));

            assertThat(result.getEndDate()).isEqualTo(start.plusDays(newDays));
            assertThat(result.getTotalPrice()).isEqualTo(expectedTotal);
        }

        @Test
        @DisplayName("un arriendo ACTIVO puede extender la devolución")
        void activeRentalCanExtendReturn() {
            LocalDate start = today.minusDays(1);
            givenRental(RentalState.ACTIVO, start, today.plusDays(2)); // 3 días
            givenRelatedDataForResponse(50_000);

            RentalResponseDTO result = service.updateRentalDates(RENTAL_ID, dates(start, today.plusDays(4)));

            assertThat(result.getTotalPrice()).isEqualTo(5 * DAILY_RATE);
        }
    }

    // =====================================================================================
    // Devolución del auto
    // =====================================================================================
    @Nested
    @DisplayName("Devolución (finish)")
    class Finish {

        private static final String MILEAGE_URL = CAR_URL + "/api/cars/{id}/mileage?value={value}";

        @Test
        @DisplayName("finaliza el arriendo, guarda el kilometraje final y actualiza el del auto")
        void finishesAndUpdatesCarMileage() {
            givenActiveRentalWithCarMileage(50_000);

            RentalResponseDTO result = service.finishRental(RENTAL_ID, finish(50_500));

            assertThat(result.getStatus()).isEqualTo(RentalState.FINALIZADO);
            assertThat(result.getFinalMileage()).isEqualTo(50_500);
            verify(restTemplate).exchange(eq(MILEAGE_URL), eq(HttpMethod.PATCH), any(HttpEntity.class),
                    eq(Void.class), eq(CAR_ID), eq(50_500));
        }

        @Test
        @DisplayName("acepta un kilometraje final igual al actual (el auto no se movió)")
        void acceptsMileageEqualToCurrent() {
            givenActiveRentalWithCarMileage(50_000);

            RentalResponseDTO result = service.finishRental(RENTAL_ID, finish(50_000));

            assertThat(result.getStatus()).isEqualTo(RentalState.FINALIZADO);
        }

        @Test
        @DisplayName("rechaza un kilometraje final menor al actual y no toca el auto")
        void rejectsMileageBelowCurrent() {
            givenActiveRentalWithCarMileage(50_000);

            assertThatThrownBy(() -> service.finishRental(RENTAL_ID, finish(49_999)))
                    .isInstanceOf(InvalidRentalException.class)
                    .hasMessageContaining("no puede ser menor");

            verify(rentalRepository, never()).save(any());
            verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.PATCH), any(HttpEntity.class),
                    eq(Void.class), anyLong(), anyInt());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = RentalState.class, names = {"PENDIENTE", "FINALIZADO", "CANCELADO"})
        @DisplayName("solo se puede devolver un arriendo ACTIVO")
        void onlyActiveRentalsCanBeReturned(RentalState status) {
            givenRental(status, today.minusDays(3), today.plusDays(1));

            assertThatThrownBy(() -> service.finishRental(RENTAL_ID, finish(60_000)))
                    .isInstanceOf(InvalidRentalException.class);

            verifyNoInteractions(restTemplate);
            verify(rentalRepository, never()).save(any());
        }

        @Test
        @DisplayName("si no se puede leer el kilometraje actual del auto, falla en vez de adivinar")
        void failsWhenCurrentMileageIsUnknown() {
            givenRental(RentalState.ACTIVO, today.minusDays(1), today.plusDays(2));
            stubGet(CAR_URL + "/api/cars/admin/{id}", CarInfoDTO.class, car(null));

            assertThatThrownBy(() -> service.finishRental(RENTAL_ID, finish(60_000)))
                    .isInstanceOf(ServiceCommunicationException.class)
                    .hasMessageContaining("kilometraje actual");
        }

        @Test
        @DisplayName("si car-service rechaza el kilometraje (400), se informa como arriendo inválido")
        void carServiceRejectsMileage() {
            givenActiveRentalWithCarMileage(50_000);
            lenient().when(restTemplate.exchange(eq(MILEAGE_URL), eq(HttpMethod.PATCH), any(HttpEntity.class),
                    eq(Void.class), anyLong(), anyInt())).thenThrow(badRequest());

            assertThatThrownBy(() -> service.finishRental(RENTAL_ID, finish(50_500)))
                    .isInstanceOf(InvalidRentalException.class)
                    .hasMessageContaining("no es válido");
        }

        @Test
        @DisplayName("si car-service no responde al actualizar el kilometraje, se informa y no se oculta el error")
        void carServiceDownWhileUpdatingMileage() {
            givenActiveRentalWithCarMileage(50_000);
            lenient().when(restTemplate.exchange(eq(MILEAGE_URL), eq(HttpMethod.PATCH), any(HttpEntity.class),
                    eq(Void.class), anyLong(), anyInt())).thenThrow(new ResourceAccessException("down"));

            assertThatThrownBy(() -> service.finishRental(RENTAL_ID, finish(50_500)))
                    .isInstanceOf(ServiceCommunicationException.class)
                    .hasMessageContaining("kilometraje");
            // El rollback de la transacción (el arriendo vuelve a ACTIVO) lo hace Spring, no se
            // puede comprobar en un test unitario: va en la ronda de integración.
        }

        private void givenActiveRentalWithCarMileage(int mileage) {
            givenRental(RentalState.ACTIVO, today.minusDays(3), today.plusDays(1));
            givenRelatedDataForResponse(mileage);
        }
    }

    // =====================================================================================
    // Eliminar y reactivar
    // =====================================================================================
    @Nested
    @DisplayName("Eliminar y reactivar")
    class DeleteAndRestore {

        @Test
        @DisplayName("eliminar un arriendo inexistente responde 404")
        void deleteNotFound() {
            when(rentalRepository.findById(RENTAL_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteRental(RENTAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(rentalRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("eliminar delega en el repositorio (el borrado lógico lo hace la entidad)")
        void deleteDelegates() {
            givenRental(RentalState.PENDIENTE, today.plusDays(5), today.plusDays(8));

            service.deleteRental(RENTAL_ID);

            verify(rentalRepository).deleteById(RENTAL_ID);
        }

        @Test
        @DisplayName("reactivar un arriendo que no estaba eliminado responde 404")
        void restoreNotFound() {
            when(rentalRepository.findDeletedById(RENTAL_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.restoreRental(RENTAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = RentalState.class, names = {"PENDIENTE", "ACTIVO"})
        @DisplayName("no se reactiva un arriendo vigente si el auto ya se arrendó en esas fechas")
        void restoreBlockedWhenCarWasBookedMeanwhile(RentalState status) {
            LocalDate start = today.plusDays(5);
            RentalEntity deleted = givenDeletedRental(status, start, start.plusDays(3));
            when(rentalRepository.existsOverlappingExcluding(
                    eq(CAR_ID), eq(start), eq(start.plusDays(3)), blocking(), eq(RENTAL_ID))).thenReturn(true);

            assertThatThrownBy(() -> service.restoreRental(RENTAL_ID))
                    .isInstanceOf(InvalidRentalException.class)
                    .hasMessageContaining("No se puede reactivar");

            verify(rentalRepository, never()).restoreById(any());
            assertThat(deleted.getDeleted()).isTrue();
        }

        @Test
        @DisplayName("reactiva un arriendo vigente cuando no hay solape")
        void restoresWhenNoOverlap() {
            LocalDate start = today.plusDays(5);
            givenDeletedRental(RentalState.PENDIENTE, start, start.plusDays(3));
            givenRelatedDataForResponse(50_000);

            RentalResponseDTO result = service.restoreRental(RENTAL_ID);

            verify(rentalRepository).restoreById(RENTAL_ID);
            assertThat(result.getDeleted()).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = RentalState.class, names = {"FINALIZADO", "CANCELADO"})
        @DisplayName("un arriendo cerrado se reactiva sin revisar solapes (ya no bloquea fechas)")
        void closedRentalsSkipOverlapCheck(RentalState status) {
            givenDeletedRental(status, today.minusDays(10), today.minusDays(7));
            givenRelatedDataForResponse(50_000);

            service.restoreRental(RENTAL_ID);

            verify(rentalRepository, never()).existsOverlappingExcluding(any(), any(), any(), any(), any());
            verify(rentalRepository).restoreById(RENTAL_ID);
        }
    }

    // =====================================================================================
    // Lectura: historial y fichas
    // =====================================================================================
    @Nested
    @DisplayName("Lectura de arriendos")
    class Reads {

        @Test
        @DisplayName("el historial no se rompe si el auto ya no existe: muestra un marcador")
        void historyShowsPlaceholderWhenCarIsGone() {
            givenRental(RentalState.FINALIZADO, today.minusDays(10), today.minusDays(7));
            stubGetFailure(CAR_URL + "/api/cars/admin/{id}", CarInfoDTO.class, notFound());
            stubGet(USER_URL + "/api/users/admin/{id}", UserInfoDTO.class, user());

            RentalResponseDTO result = service.getRentalById(RENTAL_ID);

            assertThat(result.getCar().getBrand()).isEqualTo("Auto no disponible");
            assertThat(result.getUser().getFirstName()).isEqualTo("Ana");
        }

        @Test
        @DisplayName("si un servicio no responde al armar la respuesta, se informa el error de comunicación")
        void communicationErrorWhileBuildingResponse() {
            givenRental(RentalState.FINALIZADO, today.minusDays(10), today.minusDays(7));
            stubGetFailure(CAR_URL + "/api/cars/admin/{id}", CarInfoDTO.class, new ResourceAccessException("down"));

            assertThatThrownBy(() -> service.getRentalById(RENTAL_ID))
                    .isInstanceOf(ServiceCommunicationException.class)
                    .hasMessageContaining("car-service");
        }

        @Test
        @DisplayName("la ficha de admin incluye arriendos eliminados y lo marca")
        void adminDetailIncludesDeleted() {
            RentalEntity deleted = rental(RentalState.FINALIZADO, today.minusDays(10), today.minusDays(7));
            deleted.setDeleted(true);
            when(rentalRepository.findAnyById(RENTAL_ID)).thenReturn(Optional.of(deleted));
            givenRelatedDataForResponse(50_000);

            RentalResponseDTO result = service.getRentalByIdIncludingDeleted(RENTAL_ID);

            assertThat(result.getDeleted()).isTrue();
        }

        @Test
        @DisplayName("la ficha de admin responde 404 si el arriendo no existe ni eliminado")
        void adminDetailNotFound() {
            when(rentalRepository.findAnyById(RENTAL_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getRentalByIdIncludingDeleted(RENTAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // =====================================================================================
    // Utilidades
    // =====================================================================================

    // Los estados que bloquean el auto son PENDIENTE y ACTIVO; CANCELADO y FINALIZADO no.
    // Se compara como conjunto para no depender del orden de la lista.
    private static Collection<RentalState> blocking() {
        return argThat(states -> states != null
                && Set.copyOf(states).equals(Set.of(RentalState.PENDIENTE, RentalState.ACTIVO)));
    }

    private void authenticateAs(String name, String... roles) {
        var authorities = Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(name, null, authorities));
    }

    private RentalRequestDTO request(Long userId, LocalDate start, LocalDate end) {
        RentalRequestDTO dto = new RentalRequestDTO();
        dto.setCarId(CAR_ID);
        dto.setUserId(userId);
        dto.setStartDate(start);
        dto.setEndDate(end);
        return dto;
    }

    private RentalDatesRequestDTO dates(LocalDate start, LocalDate end) {
        RentalDatesRequestDTO dto = new RentalDatesRequestDTO();
        dto.setStartDate(start);
        dto.setEndDate(end);
        return dto;
    }

    private FinishRentalRequestDTO finish(int finalMileage) {
        FinishRentalRequestDTO dto = new FinishRentalRequestDTO();
        dto.setFinalMileage(finalMileage);
        return dto;
    }

    private CarInfoDTO car(Integer mileage) {
        CarInfoDTO car = new CarInfoDTO();
        car.setId(CAR_ID);
        car.setBrand("Toyota");
        car.setModel("Yaris");
        car.setLicensePlate("ABCD12");
        car.setDailyRate(DAILY_RATE);
        car.setMileage(mileage);
        return car;
    }

    private UserInfoDTO user() {
        UserInfoDTO user = new UserInfoDTO();
        user.setId(USER_ID);
        user.setFirstName("Ana");
        user.setLastName("Pérez");
        user.setEmail("ana@ejemplo.cl");
        return user;
    }

    // Arriendo con id y total coherentes con la tarifa de prueba (30.000 por día)
    private RentalEntity rental(RentalState status, LocalDate start, LocalDate end) {
        RentalEntity rental = new RentalEntity();
        ReflectionTestUtils.setField(rental, "id", RENTAL_ID);
        rental.setCarId(CAR_ID);
        rental.setUserId(USER_ID);
        rental.setStartDate(start);
        rental.setEndDate(end);
        rental.setStatus(status);
        rental.setTotalPrice(DAILY_RATE * ChronoUnit.DAYS.between(start, end));
        rental.setDeleted(false);
        return rental;
    }

    private RentalEntity givenRental(RentalState status, LocalDate start, LocalDate end) {
        RentalEntity rental = rental(status, start, end);
        when(rentalRepository.findById(RENTAL_ID)).thenReturn(Optional.of(rental));
        return rental;
    }

    private RentalEntity givenDeletedRental(RentalState status, LocalDate start, LocalDate end) {
        RentalEntity rental = rental(status, start, end);
        rental.setDeleted(true);
        when(rentalRepository.findDeletedById(RENTAL_ID)).thenReturn(Optional.of(rental));
        return rental;
    }

    // Llamadas estrictas (crear): auto y cliente vigentes
    private void givenCarAndUserFound() {
        stubGet(CAR_URL + "/api/cars/{id}", CarInfoDTO.class, car(50_000));
        stubGet(USER_URL + "/api/users/{id}", UserInfoDTO.class, user());
    }

    // Llamadas de la ficha de admin: las usa toResponseDTO para armar cualquier respuesta
    private void givenRelatedDataForResponse(Integer carMileage) {
        stubGet(CAR_URL + "/api/cars/admin/{id}", CarInfoDTO.class, car(carMileage));
        stubGet(USER_URL + "/api/users/admin/{id}", UserInfoDTO.class, user());
    }

    private <T> void stubGet(String url, Class<T> type, T body) {
        lenient().when(restTemplate.exchange(eq(url), eq(HttpMethod.GET), any(HttpEntity.class), eq(type), anyLong()))
                .thenReturn(ResponseEntity.ok(body));
    }

    private <T> void stubGetFailure(String url, Class<T> type, RuntimeException failure) {
        lenient().when(restTemplate.exchange(eq(url), eq(HttpMethod.GET), any(HttpEntity.class), eq(type), anyLong()))
                .thenThrow(failure);
    }

    private HttpClientErrorException notFound() {
        return HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", new HttpHeaders(), new byte[0], null);
    }

    private HttpClientErrorException badRequest() {
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(), new byte[0], null);
    }
}