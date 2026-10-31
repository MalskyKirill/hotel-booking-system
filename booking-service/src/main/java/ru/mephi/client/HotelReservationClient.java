package ru.mephi.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import ru.mephi.dto.CreateReservationRequest;
import ru.mephi.dto.ReservationResponse;
import ru.mephi.dto.ReservationStatus;
import ru.mephi.exception.RemoteBusinessConflictException;
import ru.mephi.exception.RemoteRoomNotFoundException;
import ru.mephi.exception.RemoteServiceUncertainException;

@Component
public class HotelReservationClient {

    private static final Logger log = LoggerFactory.getLogger(HotelReservationClient.class);

    private final RestClient restClient;

    public HotelReservationClient(
        @Qualifier("hotelServiceRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public ReservationResponse reserve(CreateReservationRequest request) {
        ReservationResponse response;
        try {
            response = restClient.post()
                .uri("/internal/reservations")
                .body(request)
                .retrieve()
                .body(ReservationResponse.class);
        } catch (HttpClientErrorException.Conflict ex) {
            log.warn("Remote business conflict for bookingId={}: {}",
                request.bookingId(), ex.getMessage());
            throw new RemoteBusinessConflictException(ex.getMessage());
        } catch (HttpClientErrorException.NotFound ex) {
            log.warn("Room not found in hotel service: roomId={}", request.roomId());
            throw new RemoteRoomNotFoundException(request.roomId());
        } catch (HttpServerErrorException ex) {
            log.error("Hotel service 5xx for bookingId={}: {}",
                request.bookingId(), ex.getMessage());
            throw new RemoteServiceUncertainException(
                "Hotel service failed with " + ex.getStatusCode(), ex);
        } catch (ResourceAccessException ex) {
            log.error("Timeout/network for bookingId={}: {}",
                request.bookingId(), ex.getMessage());
            throw new RemoteServiceUncertainException(
                "Hotel service unreachable: " + ex.getMessage(), ex);
        }

        validateReserveResponse(response, request);
        return response;
    }

    private void validateReserveResponse(ReservationResponse response,
                                         CreateReservationRequest request) {
        //  null body
        if (response == null) {
            throw new RemoteServiceUncertainException("Hotel service returned empty body");
        }

        //  null в любом поле — контролируемая ошибка, а не NPE
        if (response.bookingId() == null || response.roomId() == null
            || response.startDate() == null || response.endDate() == null
            || response.status() == null) {
            throw new RemoteServiceUncertainException(
                "Hotel service returned incomplete reservation response");
        }

        //  параметры должны совпадать с запросом
        if (!response.bookingId().equals(request.bookingId())
            || !response.roomId().equals(request.roomId())
            || !response.startDate().equals(request.startDate())
            || !response.endDate().equals(request.endDate())) {
            throw new RemoteServiceUncertainException(
                "Hotel service returned mismatched reservation data");
        }

        //  положительная проверка статуса (а не отрицание RELEASED)
        if (response.status() != ReservationStatus.HELD
            && response.status() != ReservationStatus.CONFIRMED) {
            throw new RemoteServiceUncertainException(
                "Unexpected reservation status from hotel service: " + response.status());
        }
    }
}
