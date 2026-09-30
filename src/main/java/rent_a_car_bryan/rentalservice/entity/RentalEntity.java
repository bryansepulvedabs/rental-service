package rent_a_car_bryan.rentalservice.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;

@Entity
@Table(name = "rentals")
@Data
// Borrado logico: cualquier delete (incluido rentalRepository.deleteById) se traduce
// a un UPDATE. No queda ninguna ruta desde la aplicacion que borre la fila de verdad.
@SQLDelete(sql = "UPDATE rentals SET deleted = true WHERE id = ?")
// Y toda consulta sobre la entidad -incluidas las @Query del repositorio- filtra los
// borrados, asi que un arriendo eliminado tampoco bloquea fechas por solapamiento.
@SQLRestriction("deleted = false")
public class RentalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long carId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RentalState status;

    @Column(nullable = false)
    private Long totalPrice;

    // columnDefinition con DEFAULT: sin el, ddl-auto=update falla al agregar una
    // columna NOT NULL sobre una tabla que ya tiene filas.
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private Boolean deleted = false;

}