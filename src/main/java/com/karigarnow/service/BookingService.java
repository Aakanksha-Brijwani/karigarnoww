package com.karigarnow.service;

import com.karigarnow.dto.request.CreateBookingRequest;
import com.karigarnow.dto.request.DispatchRequest;
import com.karigarnow.dto.request.OtpVerifyRequest;
import com.karigarnow.dto.response.*;
import com.karigarnow.exception.BadRequestException;
import com.karigarnow.exception.ForbiddenException;
import com.karigarnow.exception.InvalidStatusTransitionException;
import com.karigarnow.exception.ResourceNotFoundException;
import com.karigarnow.model.*;
import com.karigarnow.repository.*;
import com.karigarnow.utils.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingWorkerRepository bookingWorkerRepository;
    private final ThekedarServiceRepository thekedarServiceRepository;
    private final ThekedarRepository thekedarRepository;
    private final WorkerRepository workerRepository;
    private final AddressRepository addressRepository;
    private final EarningsRepository earningsRepository;

    private static final BigDecimal PLATFORM_COMMISSION = new BigDecimal("0.05");
    private static final SecureRandom random = new SecureRandom();

    // ---------- CREATE BOOKING ----------
    @Transactional
    public ApiResponse<BookingResponse> createBooking(CreateBookingRequest request, UUID consumerId) {
        log.info("Creating booking for consumer {} with thekedar {}", consumerId, request.getThekedarId());

        // Validate thekedar exists
        Thekedar thekedar = thekedarRepository.findById(request.getThekedarId())
                .orElseThrow(() -> new ResourceNotFoundException("Thekedar not found"));

        // Validate thekedar offers this service
        ThekedarService thekedarService = thekedarServiceRepository.findByServiceIdAndThekedarId(
                request.getServiceId(), request.getThekedarId())
                .orElseThrow(() -> new BadRequestException(
                        "Thekedar does not offer the requested service"));

        // Validate address exists
        Address address = addressRepository.findById(request.getAddressId())
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        // Validate address city matches thekedar location
        if (address.getCity() != null && thekedar.getLocation() != null) {
            if (!address.getCity().trim().equalsIgnoreCase(thekedar.getLocation().trim())) {
                log.warn("Booking failed: City mismatch. Consumer city: {}, Thekedar location: {}", 
                        address.getCity(), thekedar.getLocation());
                throw new BadRequestException("Thekedar not available in your city");
            }
        }

        // Validate team size
        int workersNeeded = request.getWorkersNeeded() != null ? request.getWorkersNeeded() : 1;
        if (thekedar.getTeamSize() == null || thekedar.getTeamSize() < workersNeeded) {
            log.warn("Booking failed: Insufficient workers. Requested {}, has {}", workersNeeded, thekedar.getTeamSize());
            throw new BadRequestException("Thekedar does not have enough workers. Team size: " 
                    + (thekedar.getTeamSize() != null ? thekedar.getTeamSize() : 0));
        }

        // Calculate total_amount = custom_rate * workers_needed
        // (MIN_HOURS removed as per request)
        BigDecimal customRate = thekedarService.getCustomRate();
        BigDecimal totalAmount = customRate
                .multiply(BigDecimal.valueOf(workersNeeded))
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal platformFee = totalAmount
                .multiply(PLATFORM_COMMISSION)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal thekedarPayout = totalAmount.subtract(platformFee)
                .setScale(2, RoundingMode.HALF_UP);

        Booking booking = Booking.builder()
                .consumer(User.builder().id(consumerId).build())
                .thekedar(thekedar)
                .service(thekedarService.getService())
                .address(address)
                .workersNeeded(workersNeeded)
                .jobDescription(request.getJobDescription())
                .scheduledAt(request.getScheduledAt())
                .bookingStatus("pending")
                .paymentStatus("held")
                .totalAmount(totalAmount)
                .platformFee(platformFee)
                .thekedarPayout(thekedarPayout)
                .build();

        booking = bookingRepository.save(booking);
        log.info("Booking created successfully with ID: {}", booking.getId());

        return new ApiResponse<>(true, "Booking created successfully",
                toBookingResponse(booking, true));
    }

    // ---------- LIST BOOKINGS ----------
    public ApiResponse<PagedBookingResponse> listBookings(
            String role, UUID userId, String status, int page, int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<Booking> bookings;

        if ("consumer".equals(role)) {
            if (status != null && !status.isBlank()) {
                bookings = bookingRepository.findByConsumerIdAndBookingStatusOrderByCreatedAtDesc(
                        userId, status, pageable);
            } else {
                bookings = bookingRepository.findByConsumerIdOrderByCreatedAtDesc(userId, pageable);
            }
        } else {
            UUID thekedarId = UUID.fromString(userId.toString());
            if (status != null && !status.isBlank()) {
                bookings = bookingRepository.findByThekedarIdAndBookingStatusOrderByCreatedAtDesc(
                        userId, status, pageable);
            } else {
                bookings = bookingRepository.findByThekedarIdOrderByCreatedAtDesc(userId, pageable);
            }
        }

        List<BookingListResponse> content = bookings.getContent().stream()
                .map(this::toBookingListResponse)
                .collect(Collectors.toList());

        PagedBookingResponse paged = PagedBookingResponse.builder()
                .content(content)
                .totalPages(bookings.getTotalPages())
                .totalElements(bookings.getTotalElements())
                .currentPage(page)
                .build();

        return new ApiResponse<>(true, "Bookings fetched successfully", paged);
    }

    // ---------- GET BOOKING BY ID ----------
    public ApiResponse<BookingResponse> getBookingById(UUID bookingId, String role, UUID userId) {
        Booking booking = bookingRepository.findByIdWithDetails(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        validateBookingAccess(booking, role, userId);

        return new ApiResponse<>(true, "Booking fetched successfully",
                toBookingResponse(booking, true));
    }

    // ---------- ACCEPT BOOKING ----------
    @Transactional
    public ApiResponse<BookingResponse> acceptBooking(UUID bookingId, UUID thekedarId) {
        Booking booking = bookingRepository.findByIdWithDetails(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        validateThekedarAccess(booking, thekedarId);

        if (!"pending".equals(booking.getBookingStatus())) {
            throw new InvalidStatusTransitionException(
                    "Booking cannot be accepted. Current status: " + booking.getBookingStatus());
        }

        String otp = generateOtp();
        booking.setOtp(otp);
        booking.setBookingStatus("accepted");
        booking = bookingRepository.save(booking);

        return new ApiResponse<>(true, "Booking accepted successfully",
                toBookingResponse(booking, true));
    }

    // ---------- REJECT BOOKING ----------
    @Transactional
    public ApiResponse<BookingResponse> rejectBooking(UUID bookingId, UUID thekedarId) {
        Booking booking = bookingRepository.findByIdWithDetails(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        validateThekedarAccess(booking, thekedarId);

        if (!"pending".equals(booking.getBookingStatus())) {
            throw new InvalidStatusTransitionException(
                    "Booking cannot be rejected. Current status: " + booking.getBookingStatus());
        }

        booking.setBookingStatus("cancelled");
        booking.setPaymentStatus("refunded");
        booking = bookingRepository.save(booking);

        return new ApiResponse<>(true, "Booking rejected successfully",
                toBookingResponse(booking, false));
    }

    // ---------- DISPATCH WORKERS ----------
    @Transactional
    public ApiResponse<BookingResponse> dispatchWorkers(UUID bookingId, DispatchRequest request, UUID thekedarId) {
        Booking booking = bookingRepository.findByIdWithDetails(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        validateThekedarAccess(booking, thekedarId);

        if (!"accepted".equals(booking.getBookingStatus())) {
            throw new InvalidStatusTransitionException(
                    "Booking cannot be dispatched. Current status: " + booking.getBookingStatus());
        }

        List<Worker> workers = workerRepository.findByIdIn(request.getWorkerIds());

        if (workers.size() != request.getWorkerIds().size()) {
            throw new BadRequestException("One or more workers not found");
        }

        for (Worker worker : workers) {
            if (!worker.getThekedar().getId().equals(thekedarId)) {
                throw new BadRequestException(
                        "Worker " + worker.getName() + " does not belong to this thekedar");
            }
        }

        if (workers.size() != booking.getWorkersNeeded()) {
            throw new BadRequestException("Expected " + booking.getWorkersNeeded()
                    + " workers but " + workers.size() + " provided");
        }

        // Clear existing booking workers if any
        booking.getBookingWorkers().clear();

        for (Worker worker : workers) {
            BookingWorker bw = BookingWorker.builder()
                    .booking(booking)
                    .worker(worker)
                    .build();
            booking.getBookingWorkers().add(bw);
        }

        booking.setBookingStatus("dispatched");
        booking = bookingRepository.save(booking);

        return new ApiResponse<>(true, "Workers dispatched successfully",
                toBookingResponse(booking, false));
    }

    // ---------- VERIFY OTP ----------
    @Transactional
    public ApiResponse<BookingResponse> verifyOtp(UUID bookingId, OtpVerifyRequest request, UUID consumerId) {
        Booking booking = bookingRepository.findByIdWithDetails(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (!booking.getConsumer().getId().equals(consumerId)) {
            throw new ForbiddenException("You are not authorized to verify this booking");
        }

        if (!"dispatched".equals(booking.getBookingStatus())) {
            throw new InvalidStatusTransitionException(
                    "OTP can only be verified when booking is dispatched. "
                            + "Current status: " + booking.getBookingStatus());
        }

        if (!request.getOtp().equals(booking.getOtp())) {
            throw new BadRequestException("Invalid OTP");
        }

        booking.setOtpVerified(true);
        booking.setBookingStatus("in_progress");
        booking = bookingRepository.save(booking);

        return new ApiResponse<>(true, "OTP verified successfully",
                toBookingResponse(booking, false));
    }

    // ---------- COMPLETE BOOKING ----------
    @Transactional
    public ApiResponse<BookingResponse> completeBooking(UUID bookingId, UUID consumerId) {
        Booking booking = bookingRepository.findByIdWithDetails(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        if (!booking.getConsumer().getId().equals(consumerId)) {
            throw new ForbiddenException("You are not authorized to complete this booking");
        }

        if (!"in_progress".equals(booking.getBookingStatus())) {
            throw new InvalidStatusTransitionException(
                    "Booking cannot be completed. Current status: " + booking.getBookingStatus());
        }

        booking.setBookingStatus("completed");
        booking.setPaymentStatus("released");

        // Create earnings record
        Earnings earnings = Earnings.builder()
                .thekedar(booking.getThekedar())
                .booking(booking)
                .amount(booking.getTotalAmount())
                .platformFee(booking.getPlatformFee())
                .netAmount(booking.getThekedarPayout())
                .build();
        earningsRepository.save(earnings);

        // Increment thekedar total_jobs
        Thekedar thekedar = booking.getThekedar();
        thekedar.setTotalJobs(thekedar.getTotalJobs() + 1);
        thekedarRepository.save(thekedar);

        booking = bookingRepository.save(booking);

        return new ApiResponse<>(true, "Booking completed successfully",
                toBookingResponse(booking, false));
    }

    // ---------- CANCEL BOOKING ----------
    @Transactional
    public ApiResponse<BookingResponse> cancelBooking(UUID bookingId, UUID userId, String role) {
        Booking booking = bookingRepository.findByIdWithDetails(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));

        // Check access
        boolean isConsumer = booking.getConsumer().getId().equals(userId);
        boolean isThekedar = booking.getThekedar().getId().equals(userId);

        if (!isConsumer && !isThekedar) {
            throw new ForbiddenException("You are not authorized to cancel this booking");
        }

        String currentStatus = booking.getBookingStatus();
        if (!"pending".equals(currentStatus) && !"accepted".equals(currentStatus)) {
            throw new InvalidStatusTransitionException(
                    "Booking cannot be cancelled at status: " + currentStatus);
        }

        booking.setBookingStatus("cancelled");
        if ("held".equals(booking.getPaymentStatus())) {
            booking.setPaymentStatus("refunded");
        }
        booking = bookingRepository.save(booking);

        return new ApiResponse<>(true, "Booking cancelled successfully",
                toBookingResponse(booking, false));
    }

    // ---------- PRIVATE HELPERS ----------

    private void validateBookingAccess(Booking booking, String role, UUID userId) {
        boolean isConsumer = booking.getConsumer().getId().equals(userId);
        boolean isThekedar = booking.getThekedar().getId().equals(userId);

        if (!isConsumer && !isThekedar) {
            throw new ForbiddenException("You are not authorized to view this booking");
        }
    }

    private void validateThekedarAccess(Booking booking, UUID thekedarId) {
        if (!booking.getThekedar().getId().equals(thekedarId)) {
            throw new ForbiddenException("You are not authorized to perform this action");
        }
    }

    private String generateOtp() {
        int otp = 1000 + random.nextInt(9000);
        return String.valueOf(otp);
    }

    private BookingResponse toBookingResponse(Booking booking, boolean includeOtp) {
        List<BookingWorkerResponse> workerResponses = null;
        if (booking.getBookingWorkers() != null && !booking.getBookingWorkers().isEmpty()) {
            workerResponses = booking.getBookingWorkers().stream()
                    .map(bw -> BookingWorkerResponse.builder()
                            .id(bw.getWorker().getId())
                            .name(bw.getWorker().getName())
                            .mobile(bw.getWorker().getMobile())
                            .skills(bw.getWorker().getSkills())
                            .dailyRate(bw.getWorker().getDailyRate())
                            .assignedAt(bw.getAssignedAt())
                            .build())
                    .collect(Collectors.toList());
        }

        String consumerName = null;
        String thekedarName = null;
        if (booking.getConsumer() != null && booking.getConsumer().getName() != null) {
            consumerName = booking.getConsumer().getName();
        }
        if (booking.getThekedar() != null && booking.getThekedar().getUser() != null) {
            thekedarName = booking.getThekedar().getUser().getName();
        }

        // Fetch names if not loaded (from fetch join)
        if (consumerName == null && booking.getConsumer() != null) {
            consumerName = booking.getConsumer().getName();
        }
        if (thekedarName == null && booking.getThekedar() != null) {
            thekedarName = booking.getThekedar().getUser().getName();
        }

        AddressInfoResponse addressResponse = null;
        if (booking.getAddress() != null) {
            addressResponse = AddressInfoResponse.builder()
                    .id(booking.getAddress().getId())
                    .addressLine1(booking.getAddress().getAddressLine1())
                    .addressLine2(booking.getAddress().getAddressLine2())
                    .city(booking.getAddress().getCity())
                    .state(booking.getAddress().getState())
                    .postalCode(booking.getAddress().getPostalCode())
                    .build();
        }

        ReviewResponse reviewResponse = null;
        if (booking.getReviews() != null && !booking.getReviews().isEmpty()) {
            Review firstReview = booking.getReviews().iterator().next();
            reviewResponse = ReviewResponse.builder()
                    .rating(firstReview.getRating())
                    .comment(firstReview.getComment())
                    .build();
        }

        return BookingResponse.builder()
                .id(booking.getId())
                .service(ServiceInfoResponse.builder()
                        .id(booking.getService().getId())
                        .slug(booking.getService().getSlug())
                        .name(booking.getService().getName())
                        .build())
                .consumerName(consumerName)
                .thekedarName(thekedarName)
                .thekedarId(booking.getThekedar().getId())
                .workersNeeded(booking.getWorkersNeeded())
                .address(addressResponse)
                .jobDescription(booking.getJobDescription())
                .scheduledAt(booking.getScheduledAt())
                .otp(includeOtp ? booking.getOtp() : null)
                .otpVerified(booking.getOtpVerified())
                .bookingStatus(booking.getBookingStatus())
                .paymentStatus(booking.getPaymentStatus())
                .totalAmount(booking.getTotalAmount())
                .platformFee(booking.getPlatformFee())
                .thekedarPayout(booking.getThekedarPayout())
                .assignedWorkers(workerResponses)
                .hasReview(booking.getReviews() != null && !booking.getReviews().isEmpty())
                .review(reviewResponse)
                .createdAt(booking.getCreatedAt())
                .build();
    }

    private BookingListResponse toBookingListResponse(Booking booking) {
        String consumerName = null;
        String thekedarName = null;

        if (booking.getConsumer() != null) {
            consumerName = booking.getConsumer().getName();
        }
        if (booking.getThekedar() != null && booking.getThekedar().getUser() != null) {
            thekedarName = booking.getThekedar().getUser().getName();
        }

        return BookingListResponse.builder()
                .id(booking.getId())
                .serviceName(booking.getService() != null ? booking.getService().getName() : null)
                .thekedarName(thekedarName)
                .consumerName(consumerName)
                .bookingStatus(booking.getBookingStatus())
                .paymentStatus(booking.getPaymentStatus())
                .totalAmount(booking.getTotalAmount())
                .scheduledAt(booking.getScheduledAt())
                .workersNeeded(booking.getWorkersNeeded())
                .createdAt(booking.getCreatedAt())
                .otp(booking.getOtp())
                .otpVerified(booking.getOtpVerified())
                .build();
    }
}
