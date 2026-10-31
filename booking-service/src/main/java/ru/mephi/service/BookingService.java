package ru.mephi.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import ru.mephi.dto.BookingResponse;
import ru.mephi.dto.CreateBookingRequest;

@Service
public class BookingService {

    private final BookingCreationTransactions transactions;

    public BookingService(BookingCreationTransactions transactions) {
        this.transactions = transactions;
    }

    public BookingResponse create(CreateBookingRequest request, String username) {
        try {
            return transactions.create(request, username);
        } catch (DataIntegrityViolationException ex) {
            // Создающая транзакция уже откатилась. Читаем результат победившего
            // запроса в новой транзакции, не продолжая работу в rollback-only.
            return transactions.findExisting(request, username).orElseThrow(() -> ex);
        }
    }
}
