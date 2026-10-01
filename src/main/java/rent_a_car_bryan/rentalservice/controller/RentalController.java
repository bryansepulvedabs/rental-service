package rent_a_car_bryan.rentalservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rent_a_car_bryan.rentalservice.dto.RentalDatesRequestDTO;
import rent_a_car_bryan.rentalservice.dto.RentalRequestDTO;
import rent_a_car_bryan.rentalservice.dto.RentalResponseDTO;
import rent_a_car_bryan.rentalservice.entity.RentalState;
import rent_a_car_bryan.rentalservice.service.RentalService;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/rentals")
@RequiredArgsConstructor
public class RentalController {

    private final RentalService rentalService;

    @PostMapping
    public ResponseEntity<RentalResponseDTO> createRental(@Valid @RequestBody RentalRequestDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rentalService.createRental(dto));
    }

    @GetMapping("/availability")
    public ResponseEntity<Boolean> isCarAvailable(
            @RequestParam Long carId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(rentalService.isCarAvailable(carId, startDate, endDate));
    }

    @GetMapping("/occupied")
    public ResponseEntity<List<Long>> getOccupiedCarIds(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(rentalService.getOccupiedCarIds(startDate, endDate));
    }

    @GetMapping
    public ResponseEntity<List<RentalResponseDTO>> getAllRentals() {
        return ResponseEntity.ok(rentalService.getAllRentals());
    }

    // Arriendos dados de baja: solo ADMIN
    @GetMapping("/deleted")
    public ResponseEntity<List<RentalResponseDTO>> getAllDeletedRentals() {
        return ResponseEntity.ok(rentalService.getAllDeletedRentals());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RentalResponseDTO> getRentalById(@PathVariable Long id) {
        return ResponseEntity.ok(rentalService.getRentalById(id));
    }

    // Ficha incluyendo eliminados, para revisar su historial: solo ADMIN
    @GetMapping("/admin/{id}")
    public ResponseEntity<RentalResponseDTO> getRentalByIdIncludingDeleted(@PathVariable Long id) {
        return ResponseEntity.ok(rentalService.getRentalByIdIncludingDeleted(id));
    }

    @GetMapping("/car/{carId}")
    public ResponseEntity<List<RentalResponseDTO>> getRentalsByCarId(@PathVariable Long carId) {
        return ResponseEntity.ok(rentalService.getRentalsByCarId(carId));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<RentalResponseDTO>> getRentalsByUserId(@PathVariable Long userId) {
        return ResponseEntity.ok(rentalService.getRentalsByUserId(userId));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<RentalResponseDTO> updateRentalStatus(
            @PathVariable Long id,
            @RequestParam RentalState newStatus) {
        return ResponseEntity.ok(rentalService.updateRentalStatus(id, newStatus));
    }

    // Editar las fechas de un arriendo vigente: solo personal (ADMIN o EMPLOYEE),
    // cae en la regla anyRequest() del SecurityConfig.
    @PatchMapping("/{id}/dates")
    public ResponseEntity<RentalResponseDTO> updateRentalDates(
            @PathVariable Long id,
            @Valid @RequestBody RentalDatesRequestDTO dto) {
        return ResponseEntity.ok(rentalService.updateRentalDates(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRental(@PathVariable Long id) {
        rentalService.deleteRental(id);
        return ResponseEntity.noContent().build();
    }

    // Reactivar un arriendo dado de baja: solo ADMIN
    @PatchMapping("/{id}/restore")
    public ResponseEntity<RentalResponseDTO> restore(@PathVariable Long id){
        return ResponseEntity.ok(rentalService.restoreRental(id));
    }
}