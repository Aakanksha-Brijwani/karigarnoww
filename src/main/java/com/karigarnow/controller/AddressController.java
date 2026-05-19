package com.karigarnow.controller;

import com.karigarnow.dto.request.AddressRequest;
import com.karigarnow.dto.response.AddressResponse;
import com.karigarnow.model.Address;
import com.karigarnow.service.AddressService;
import com.karigarnow.utils.ApiResponse;
import com.karigarnow.utils.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AddressResponse>>> listAddresses(
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID userId = UUID.fromString(principal.getUserId());
        List<Address> addresses = addressService.getAddressesByUserId(userId);
        List<AddressResponse> response = addresses.stream()
                .map(this::toAddressResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(new ApiResponse<>(true, "Addresses fetched successfully", response));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Object>> createAddress(
            @Valid @RequestBody AddressRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID userId = UUID.fromString(principal.getUserId());
        Address address = addressService.createAddress(request, userId);
        
        // Lightweight response as requested
        var data = java.util.Map.of(
            "id", address.getId(),
            "city", address.getCity()
        );
        
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Address created successfully", data));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AddressResponse>> updateAddress(
            @PathVariable UUID id,
            @Valid @RequestBody AddressRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID userId = UUID.fromString(principal.getUserId());
        Address address = addressService.updateAddress(id, request, userId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Address updated successfully", toAddressResponse(address)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteAddress(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        UUID userId = UUID.fromString(principal.getUserId());
        addressService.deleteAddress(id, userId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Address deleted successfully", null));
    }

    private AddressResponse toAddressResponse(Address address) {
        return AddressResponse.builder()
                .id(address.getId())
                .addressLine1(address.getAddressLine1())
                .addressLine2(address.getAddressLine2())
                .city(address.getCity())
                .state(address.getState())
                .country(address.getCountry())
                .postalCode(address.getPostalCode())
                .isPrimary(address.getIsPrimary())
                .build();
    }
}