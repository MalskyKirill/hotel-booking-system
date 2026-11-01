package ru.mephi.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ru.mephi.dto.CreateReservationRequest;
import ru.mephi.dto.AutoReservationRequest;
import ru.mephi.dto.ReservationResponse;
import ru.mephi.dto.ReservationStatus;
import ru.mephi.exception.RemoteBusinessConflictException;
import ru.mephi.exception.RemoteRoomNotFoundException;
import ru.mephi.exception.RemoteServiceUncertainException;
import ru.mephi.exception.RemoteHotelRequestException;
import ru.mephi.exception.RemoteReservationNotFoundException;

import java.util.Set;
import java.util.EnumSet;
import java.util.function.Supplier;

@Component
public class HotelReservationClient {

    private static final Logger log = LoggerFactory.getLogger(HotelReservationClient.class);

    private final RestClient restClient;

    public HotelReservationClient(
        @Qualifier("hotelServiceRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public ReservationResponse reserve(CreateReservationRequest request) {
        return invoke("reserve", request, () -> restClient.post().uri("/internal/reservations")
            .body(request).retrieve().body(ReservationResponse.class),
            Set.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED));
    }

    public ReservationResponse confirm(CreateReservationRequest request) {
        return invoke("confirm", request, () -> restClient.post()
            .uri("/internal/reservations/{bookingId}/confirm", request.bookingId())
            .retrieve().body(ReservationResponse.class), Set.of(ReservationStatus.CONFIRMED));
    }

    public ReservationResponse release(CreateReservationRequest request) {
        return invoke("release", request, () -> restClient.post()
            .uri("/internal/reservations/{bookingId}/release", request.bookingId())
            .body(request).retrieve().body(ReservationResponse.class), Set.of(ReservationStatus.RELEASED));
    }

    public ReservationResponse getStatus(CreateReservationRequest request) {
        return invoke("getStatus", request, () -> restClient.get()
            .uri("/internal/reservations/{bookingId}", request.bookingId())
            .retrieve().body(ReservationResponse.class), EnumSet.allOf(ReservationStatus.class));
    }

    public ReservationResponse reserveAuto(AutoReservationRequest request) { return invokeAuto("reserve", request); }
    public ReservationResponse confirmAuto(AutoReservationRequest request) { return invokeAuto("confirm", request); }
    public ReservationResponse releaseAuto(AutoReservationRequest request) { return invokeAuto("release", request); }
    public ReservationResponse getAutoStatus(AutoReservationRequest request) { return invokeAuto("status", request); }

    private ReservationResponse invokeAuto(String operation, AutoReservationRequest request) {
        ReservationResponse response;
        try {
            response = restClient.post().uri("/internal/auto-reservations" + (operation.equals("reserve") ? "" : "/" + operation))
                .body(request).retrieve().body(ReservationResponse.class);
        } catch (HttpClientErrorException.Conflict ex) {
            throw new RemoteBusinessConflictException(errorCode(ex), "Automatic selection rejected");
        } catch (HttpClientErrorException.NotFound ex) {
            throw new RemoteReservationNotFoundException(request.bookingId());
        } catch (HttpClientErrorException ex) {
            throw new RemoteHotelRequestException(ex.getStatusCode().value(), errorCode(ex));
        } catch (RestClientException ex) {
            throw new RemoteServiceUncertainException("Automatic selection result is uncertain", ex);
        }
        if (response == null || !request.bookingId().equals(response.bookingId())
            || !request.startDate().equals(response.startDate()) || !request.endDate().equals(response.endDate())
            || response.status() == null || (response.status() != ReservationStatus.RELEASED
                && (response.roomId() == null || response.roomId() <= 0))) {
            throw new RemoteServiceUncertainException("Invalid automatic selection response");
        }
        if (operation.equals("reserve") && response.status() == ReservationStatus.RELEASED) {
            throw new RemoteBusinessConflictException("RESERVATION_RELEASED", "Selection was already cancelled");
        }
        if ((operation.equals("confirm") && response.status() != ReservationStatus.CONFIRMED)
            || (operation.equals("release") && response.status() != ReservationStatus.RELEASED)) {
            throw new RemoteServiceUncertainException("Unexpected automatic selection state");
        }
        return response;
    }

    private ReservationResponse invoke(String operation, CreateReservationRequest request,
                                       Supplier<ReservationResponse> action,
                                       Set<ReservationStatus> expectedStatuses) {
        ReservationResponse response;
        try {
            response = action.get();
        } catch (HttpClientErrorException.Conflict ex) {
            log.warn("Remote business conflict for bookingId={}", request.bookingId());
            throw new RemoteBusinessConflictException(errorCode(ex), "Hotel service rejected conflicting operation");
        } catch (HttpClientErrorException.NotFound ex) {
            if ("ROOM_NOT_FOUND".equals(errorCode(ex)) || "reserve".equals(operation)) {
                throw new RemoteRoomNotFoundException(request.roomId());
            }
            throw new RemoteReservationNotFoundException(request.bookingId());
        } catch (HttpClientErrorException ex) {
            throw new RemoteHotelRequestException(ex.getStatusCode().value(), errorCode(ex));
        } catch (HttpServerErrorException | ResourceAccessException ex) {
            log.error("Hotel service result uncertain for bookingId={}", request.bookingId());
            throw new RemoteServiceUncertainException("Hotel service result is uncertain", ex);
        } catch (RestClientException ex) {
            throw new RemoteServiceUncertainException("Hotel service returned an unreadable response", ex);
        }
        validateResponse(response, request);
        if ("reserve".equals(operation) && response.status() == ReservationStatus.RELEASED) {
            throw new RemoteBusinessConflictException("RESERVATION_RELEASED", "Reservation was already released");
        }
        if (!expectedStatuses.contains(response.status())) {
            throw new RemoteServiceUncertainException("Unexpected reservation status: " + response.status());
        }
        return response;
    }

    private void validateResponse(ReservationResponse response,
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

    }

    private String errorCode(RestClientResponseException ex) {
        try {
            RemoteError error = ex.getResponseBodyAs(RemoteError.class);
            if (error != null && error.code() != null && !error.code().isBlank()) {
                return error.code();
            }
        } catch (RuntimeException ignored) {
            // Неизвестное тело ошибки не меняет наблюдаемый HTTP-статус.
        }
        return "HTTP_" + ex.getStatusCode().value();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RemoteError(String code) { }
}
