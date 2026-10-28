package ru.mephi.exception;

public class UsernameAlreadyExistsException extends RuntimeException {

    private final String username;

    public UsernameAlreadyExistsException(String username) {
        super("Username '" + username + "' is already taken");
        this.username = username;
    }

    public String getUsername() {
        return username;
    }
}
