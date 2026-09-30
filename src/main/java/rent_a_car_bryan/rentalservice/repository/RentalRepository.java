package rent_a_car_bryan.rentalservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import rent_a_car_bryan.rentalservice.entity.RentalEntity;
import rent_a_car_bryan.rentalservice.entity.RentalState;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RentalRepository extends JpaRepository<RentalEntity, Long> {

    List<RentalEntity> findByCarId(Long carId);

    List<RentalEntity> findByUserId(Long userId);

    List<RentalEntity> findAllByOrderByIdAsc();

    // Dos periodos se solapan si cada uno empieza antes de que termine el otro.
    // Usamos desigualdad estricta porque el dia de termino es el de entrega del auto:
    // un arriendo 10->18 deja libre el 18. Si se quiere un dia de colchon entre arriendos,
    // cambiar < y > por <= y >=.
    @Query("""
            SELECT COUNT(r) > 0 FROM RentalEntity r
            WHERE r.carId = :carId
              AND r.status IN :statuses
              AND r.startDate < :endDate
              AND r.endDate > :startDate
            """)
    boolean existsOverlapping(@Param("carId") Long carId,
                              @Param("startDate") LocalDate startDate,
                              @Param("endDate") LocalDate endDate,
                              @Param("statuses") Collection<RentalState> statuses);

    // Igual que la anterior pero ignorando un arriendo concreto: sirve para validar
    // una edicion de fechas sin que el propio arriendo se bloquee a si mismo.
    @Query("""
            SELECT COUNT(r) > 0 FROM RentalEntity r
            WHERE r.carId = :carId
              AND r.id <> :rentalId
              AND r.status IN :statuses
              AND r.startDate < :endDate
              AND r.endDate > :startDate
            """)
    boolean existsOverlappingExcluding(@Param("carId") Long carId,
                                       @Param("startDate") LocalDate startDate,
                                       @Param("endDate") LocalDate endDate,
                                       @Param("statuses") Collection<RentalState> statuses,
                                       @Param("rentalId") Long rentalId);

    // Ids de los autos ocupados en un rango, en una sola consulta: el catalogo pide
    // esta lista una vez y filtra, en vez de preguntar auto por auto.
    @Query("""
            SELECT DISTINCT r.carId FROM RentalEntity r
            WHERE r.status IN :statuses
              AND r.startDate < :endDate
              AND r.endDate > :startDate
            """)
    List<Long> findOccupiedCarIds(@Param("startDate") LocalDate startDate,
                                  @Param("endDate") LocalDate endDate,
                                  @Param("statuses") Collection<RentalState> statuses);

    // Consultas nativas: saltan @SQLRestriction, es la unica forma de ver los borrados.
    @Query(value = "SELECT * FROM rentals WHERE deleted = true ORDER BY id", nativeQuery = true)
    List<RentalEntity> findAllDeleted();

    @Query(value = "SELECT * FROM rentals WHERE id = :id AND deleted = true", nativeQuery = true)
    Optional<RentalEntity> findDeletedById(Long id);

    // Ficha del admin: el arriendo exista vigente o dado de baja.
    @Query(value = "SELECT * FROM rentals WHERE id = :id", nativeQuery = true)
    Optional<RentalEntity> findAnyById(Long id);

    @Modifying
    @Query(value = "UPDATE rentals SET deleted = false WHERE id = :id AND deleted = true", nativeQuery = true)
    int restoreById(Long id);
}