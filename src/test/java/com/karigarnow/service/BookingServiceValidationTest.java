package com.karigarnow.service;

import com.karigarnow.dto.request.CreateBookingRequest;
import com.karigarnow.exception.BadRequestException;
import com.karigarnow.model.*;
import com.karigarnow.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class BookingServiceValidationTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private ThekedarRepository thekedarRepository;
    @Mock
    private ThekedarServiceRepository thekedarServiceRepository;
    @Mock
    private AddressRepository addressRepository;

    @InjectMocks
    private BookingService bookingService;

    private UUID consumerId;
    private UUID thekedarId;
    private UUID serviceId;
    private UUID addressId;
    private Thekedar thekedar;
    private Address address;
    private ThekedarService thekedarService;

    @BeforeEach
    void setUp() {
        consumerId = UUID.randomUUID();
        thekedarId = UUID.randomUUID();
        serviceId = UUID.randomUUID();
        addressId = UUID.randomUUID();

        thekedar = Thekedar.builder()
                .id(thekedarId)
                .user(User.builder().id(thekedarId).name("Test Thekedar").build())
                .location("Indore")
                .teamSize(5)
                .build();

        address = Address.builder()
                .id(addressId)
                .city("Indore")
                .build();

        AppService appService = AppService.builder()
                .id(serviceId)
                .name("Plumbing")
                .build();

        thekedarService = ThekedarService.builder()
                .thekedar(thekedar)
                .service(appService)
                .customRate(new BigDecimal("500.00"))
                .build();
    }

    @Test
    void createBooking_ShouldFail_WhenCityMismatch() {
        // Arrange
        address.setCity("Bhopal");
        CreateBookingRequest request = CreateBookingRequest.builder()
                .thekedarId(thekedarId)
                .serviceId(serviceId)
                .addressId(addressId)
                .workersNeeded(1)
                .build();

        when(thekedarRepository.findById(thekedarId)).thenReturn(Optional.of(thekedar));
        when(thekedarServiceRepository.findByServiceIdAndThekedarId(serviceId, thekedarId))
                .thenReturn(Optional.of(thekedarService));
        when(addressRepository.findById(addressId)).thenReturn(Optional.of(address));

        // Act & Assert
        BadRequestException exception = assertThrows(BadRequestException.class, () -> {
            bookingService.createBooking(request, consumerId);
        });

        assertEquals("Thekedar not available in your city", exception.getMessage());
    }

    @Test
    void createBooking_ShouldSucceed_WhenCityMatches() {
        // Arrange
        address.setCity("Indore");
        thekedar.setLocation("Indore");
        
        CreateBookingRequest request = CreateBookingRequest.builder()
                .thekedarId(thekedarId)
                .serviceId(serviceId)
                .addressId(addressId)
                .workersNeeded(1)
                .jobDescription("Test job")
                .build();

        when(thekedarRepository.findById(thekedarId)).thenReturn(Optional.of(thekedar));
        when(thekedarServiceRepository.findByServiceIdAndThekedarId(serviceId, thekedarId))
                .thenReturn(Optional.of(thekedarService));
        when(addressRepository.findById(addressId)).thenReturn(Optional.of(address));
        
        // Mock save
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> {
            Booking b = i.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });

        // Act
        var response = bookingService.createBooking(request, consumerId);

        // Assert
        assertTrue(response.isSuccess());
        assertEquals("Booking created successfully", response.getMessage());
    }

    // Helper for any(Booking.class) since I didn't import it properly in mockito way if needed
    private <T> T any(Class<T> type) {
        return org.mockito.ArgumentMatchers.any(type);
    }
}
