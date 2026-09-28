package rent_a_car_bryan.rentalservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import rent_a_car_bryan.rentalservice.entity.RentalEntity;

import java.util.List;

@Repository
public interface RentalRepository extends JpaRepository<RentalEntity, Long> {

    List<RentalEntity> findByCarId(Long carId);

    List<RentalEntity> findByUserId(Long userId);

    List<RentalEntity> findAllByOrderByIdAsc();

}
