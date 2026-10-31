package ru.mephi.exception;

public class RemoteHotelRequestException extends RuntimeException {
    private final int status;
    private final String code;

    public RemoteHotelRequestException(int status, String code) {
        super("Hotel service rejected request with HTTP " + status);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
