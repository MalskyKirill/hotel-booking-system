package ru.mephi.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.model.*;
import ru.mephi.repository.*;
import java.math.BigDecimal;

@Component
@Profile("demo")
public class DemoHotelData implements ApplicationRunner {
    private final HotelRepository hotels;
    private final RoomRepository rooms;
    public DemoHotelData(HotelRepository hotels, RoomRepository rooms) { this.hotels = hotels; this.rooms = rooms; }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (hotels.count() != 0 || rooms.count() != 0) return;
        for (String name : new String[]{"Демо Отель Центр", "Демо Отель Парк"}) {
            Hotel hotel = new Hotel(); hotel.setName(name); hotel.setAddress("Демонстрационный адрес"); hotels.save(hotel);
            for (int number = 1; number <= 3; number++) {
                Room room = new Room(); room.setHotel(hotel); room.setRoomNumber("10" + number);
                room.setCapacity(number); room.setPrice(BigDecimal.valueOf(1000L * number));
                room.setAvailable(number != 3); room.setTimesBooked(0); rooms.save(room);
            }
        }
    }
}
