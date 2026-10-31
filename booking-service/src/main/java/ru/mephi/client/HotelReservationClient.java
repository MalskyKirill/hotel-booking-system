package ru.mephi.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.EnumSet;
import java.util.function.Supplier;
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
        return invoke(request, () -> restClient.post().uri("/internal/reservations")
            .body(request).retrieve().body(ReservationResponse.class),
            Set.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED));
    }

    public ReservationResponse confirm(CreateReservationRequest request) {
        return invoke(request, () -> restClient.post()
            .uri("/internal/reservations/{bookingId}/confirm", request.bookingId())
            .retrieve().body(ReservationResponse.class), Set.of(ReservationStatus.CONFIRMED));
    }

    public ReservationResponse release(CreateReservationRequest request) {
        return invoke(request, () -> restClient.post()
            .uri("/internal/reservations/{bookingId}/release", request.bookingId())
            .body(request).retrieve().body(ReservationResponse.class), Set.of(ReservationStatus.RELEASED));
    }

    public ReservationResponse getStatus(CreateReservationRequest request) {
        return invoke(request, () -> restClient.get()
            .uri("/internal/reservations/{bookingId}", request.bookingId())
            .retrieve().body(ReservationResponse.class), EnumSet.allOf(ReservationStatus.class));
    }

    private ReservationResponse invoke(CreateReservationRequest request,
                                       Supplier<ReservationResponse> action,
                                       Set<ReservationStatus> expectedStatuses) {
        ReservationResponse response;
        try {
            response = action.get();
        } catch (HttpClientErrorException.Conflict ex) {
            log.warn("Remote business conflict for bookingId={}", request.bookingId());
            throw new RemoteBusinessConflictException(ex.getMessage());
        } catch (HttpClientErrorException.NotFound ex) {
            throw new RemoteRoomNotFoundException(request.roomId());
        } catch (HttpServerErrorException | ResourceAccessException ex) {
            log.error("Hotel service result uncertain for bookingId={}", request.bookingId());
            throw new RemoteServiceUncertainException("Hotel service result is uncertain", ex);
        }
        validateResponse(response, request, expectedStatuses);
        return response;
    }

    private void validateResponse(ReservationResponse response,
                                         CreateReservationRequest request, Set<ReservationStatus> expectedStatuses) {
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
        if (!expectedStatuses.contains(response.status())) {
            throw new RemoteServiceUncertainException(
                "Unexpected reservation status from hotel service: " + response.status());
        }
    }
}
