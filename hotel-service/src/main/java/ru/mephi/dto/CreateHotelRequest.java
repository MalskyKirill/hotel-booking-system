package ru.mephi.dto;


import jakarta.validation.constraints.NotBlank;

public class CreateHotelRequest {
    @NotBlank(message = "Hotel name must not be blank")
    private String name;
    @NotBlank(message = "Hotel address must not be blank")
    private String address;

    public CreateHotelRequest() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }
}
