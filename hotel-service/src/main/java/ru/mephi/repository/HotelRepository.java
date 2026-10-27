package ru.mephi.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mephi.model.Hotel;

public interface HotelRepository extends JpaRepository<Hotel, Long> {

}
