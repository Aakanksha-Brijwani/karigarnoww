package com.karigarnow.service;

import com.karigarnow.dto.request.AddressRequest;
import com.karigarnow.exception.BadRequestException;
import com.karigarnow.exception.ForbiddenException;
import com.karigarnow.exception.ResourceNotFoundException;
import com.karigarnow.model.Address;
import com.karigarnow.model.User;
import com.karigarnow.repository.AddressRepository;
import com.karigarnow.repository.BookingRepository;
import com.karigarnow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AddressService {

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;

    public List<Address> getAddressesByUserId(UUID userId) {
        return addressRepository.findByUserId(userId);
    }

    @Transactional
    public Address createAddress(AddressRequest request, UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        List<Address> existingAddresses = addressRepository.findByUserId(userId);
        
        boolean shouldBePrimary = Boolean.TRUE.equals(request.getIsPrimary()) || existingAddresses.isEmpty();

        if (shouldBePrimary) {
            for (Address addr : existingAddresses) {
                addr.setIsPrimary(false);
            }
            if (!existingAddresses.isEmpty()) {
                addressRepository.saveAll(existingAddresses);
            }
        }

        Address address = Address.builder()
                .addressLine1(request.getAddressLine1())
                .addressLine2(request.getAddressLine2())
                .city(request.getCity())
                .state(request.getState())
                .country(request.getCountry())
                .postalCode(request.getPostalCode())
                .isPrimary(shouldBePrimary)
                .user(user)
                .build();

        return addressRepository.save(address);
    }

    @Transactional
    public Address updateAddress(UUID addressId, AddressRequest request, UUID userId) {
        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        if (!address.getUser().getId().equals(userId)) {
            throw new ForbiddenException("You are not authorized to modify this address");
        }

        address.setAddressLine1(request.getAddressLine1());
        address.setAddressLine2(request.getAddressLine2());
        address.setCity(request.getCity());
        address.setState(request.getState());
        address.setCountry(request.getCountry());
        address.setPostalCode(request.getPostalCode());

        if (Boolean.TRUE.equals(request.getIsPrimary()) && !Boolean.TRUE.equals(address.getIsPrimary())) {
            List<Address> userAddresses = addressRepository.findByUserId(userId);
            for (Address addr : userAddresses) {
                if (addr.getIsPrimary()) {
                    addr.setIsPrimary(false);
                }
            }
            addressRepository.saveAll(userAddresses);
            address.setIsPrimary(true);
        }

        return addressRepository.save(address);
    }

    @Transactional
    public void deleteAddress(UUID addressId, UUID userId) {
        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        if (!address.getUser().getId().equals(userId)) {
            throw new ForbiddenException("You are not authorized to delete this address");
        }

        if (Boolean.TRUE.equals(address.getIsPrimary())) {
            throw new BadRequestException("Cannot delete primary address. Please set another address as primary first.");
        }

        if (bookingRepository.existsByAddressId(addressId)) {
            throw new BadRequestException("Cannot delete address as it is linked to one or more bookings.");
        }

        addressRepository.delete(address);
    }
}