package com.sorokaandriy.inventory_service.service;

import com.sorokaandriy.inventory_service.dto.request.CommitReservationRequest;
import com.sorokaandriy.inventory_service.dto.request.CreateReservationRequest;
import com.sorokaandriy.inventory_service.dto.request.ReleaseReservationRequest;
import com.sorokaandriy.inventory_service.dto.request.ReservationItemRequest;
import com.sorokaandriy.inventory_service.dto.response.ReservationResponse;
import com.sorokaandriy.inventory_service.entity.Product;
import com.sorokaandriy.inventory_service.entity.Reservation;
import com.sorokaandriy.inventory_service.entity.ReservationItem;
import com.sorokaandriy.inventory_service.entity.ReservationStatus;
import com.sorokaandriy.inventory_service.entity.Stock;
import com.sorokaandriy.inventory_service.exception.ReservationConflictException;
import com.sorokaandriy.inventory_service.repository.ReservationItemRepository;
import com.sorokaandriy.inventory_service.repository.ReservationRepository;
import com.sorokaandriy.inventory_service.repository.StockRepository;
import com.sorokaandriy.inventory_service.service.mapper.ReservationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {
    // These IDs also detect accidentally sorting UUIDs by their signed numeric value.
    private static final UUID FIRST_PRODUCT = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_PRODUCT = UUID.fromString("80000000-0000-0000-0000-000000000002");
    private static final Instant OLD_TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationItemRepository reservationItemRepository;
    @Mock
    private StockRepository stockRepository;

    private ReservationService service;
    private Reservation reservation;
    private UUID orderId;
    private java.time.Clock clock;

    @BeforeEach
    void setUp() {
        ReservationMapper mapper = new ReservationMapper();
        clock = mock(java.time.Clock.class);
        ReservationSupportService support = new ReservationSupportService(
                reservationItemRepository, stockRepository, mapper, clock);
        lenient().when(clock.instant()).thenReturn(OLD_TIMESTAMP);
        service = new ReservationService(reservationRepository, support, mapper);
        orderId = UUID.randomUUID();
        reservation = Reservation.builder().id(UUID.randomUUID()).orderId(orderId)
                .status(ReservationStatus.RESERVED).reservationItems(List.of())
                .updatedAt(OLD_TIMESTAMP).build();
    }

    @Test
    void reservesAllItemsInStableProductOrderAndKeepsOnHandUnchanged() {
        stubNewReservation();
        Stock first = stock(FIRST_PRODUCT, 10L, 2L);
        Stock second = stock(SECOND_PRODUCT, 6L, 1L);
        when(stockRepository.findByProductId(FIRST_PRODUCT)).thenReturn(Optional.of(first));
        when(stockRepository.findByProductId(SECOND_PRODUCT)).thenReturn(Optional.of(second));
        stubSaveItems();
        Instant before = Instant.now();

        ReservationResponse result = service.reserve(request(
                item(SECOND_PRODUCT, 2L), item(FIRST_PRODUCT, 3L)));

        assertThat(result.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.rejectionReason()).isNull();
        assertThat(result.items()).extracting(it -> it.productId())
                .containsExactly(FIRST_PRODUCT, SECOND_PRODUCT);
        assertThat(result.items()).extracting(it -> it.quantity()).containsExactly(3L, 2L);
        assertThat(first.getReserved()).isEqualTo(5L);
        assertThat(second.getReserved()).isEqualTo(3L);
        assertThat(first.getOnHand()).isEqualTo(10L);
        assertThat(second.getOnHand()).isEqualTo(6L);
        assertThat(first.getUpdatedAt()).isBetween(before, Instant.now());
        assertThat(second.getUpdatedAt()).isEqualTo(first.getUpdatedAt());
        assertThat(result.updatedAt()).isBetween(before, Instant.now());
        assertThat(reservation.getRequestHash()).matches("[0-9a-f]{64}");

        InOrder locks = inOrder(stockRepository);
        locks.verify(stockRepository).findByProductId(FIRST_PRODUCT);
        locks.verify(stockRepository).findByProductId(SECOND_PRODUCT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReservationItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(reservationItemRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).allSatisfy(saved -> {
            assertThat(saved.getReservation()).isSameAs(reservation);
            assertThat(saved.getProduct()).isIn(first.getProduct(), second.getProduct());
        });
        assertThat(captor.getValue()).extracting(ReservationItem::getProduct)
                .containsExactly(first.getProduct(), second.getProduct());
    }

    @Test
    void allowsReservingExactlyAvailableQuantity() {
        stubNewReservation();
        Stock stock = stock(FIRST_PRODUCT, 10L, 7L);
        when(stockRepository.findByProductId(FIRST_PRODUCT)).thenReturn(Optional.of(stock));
        stubSaveItems();

        ReservationResponse result = service.reserve(request(item(FIRST_PRODUCT, 3L)));

        assertThat(result.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(stock.getReserved()).isEqualTo(stock.getOnHand());
        assertThat(result.items()).singleElement().satisfies(it -> assertThat(it.quantity()).isEqualTo(3L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "inactive", "currency", "price", "insufficient"})
    void rejectsInvalidItemWithoutReservingAnyStock(String cause) {
        stubNewReservation();
        Stock first = stock(FIRST_PRODUCT, 10L, 2L);
        Stock second = stock(SECOND_PRODUCT, 10L, 1L);
        when(stockRepository.findByProductId(FIRST_PRODUCT)).thenReturn(Optional.of(first));
        String reason = switch (cause) {
            case "missing" -> "Stock not found for product ";
            case "inactive" -> {
                second.getProduct().setActive(false);
                yield "Product is inactive: ";
            }
            case "currency" -> {
                second.getProduct().setCurrency("USD");
                yield "Currency mismatch for product ";
            }
            case "price" -> {
                second.getProduct().setPriceMinor(200L);
                yield "Price changed for product ";
            }
            case "insufficient" -> {
                second.setReserved(9L);
                yield "Insufficient stock for product ";
            }
            default -> throw new IllegalArgumentException(cause);
        };
        long originalReserved = second.getReserved();
        when(stockRepository.findByProductId(SECOND_PRODUCT))
                .thenReturn(cause.equals("missing") ? Optional.empty() : Optional.of(second));
        Instant before = Instant.now();

        ReservationResponse result = service.reserve(request(item(FIRST_PRODUCT, 3L), item(SECOND_PRODUCT, 2L)));

        assertThat(result.status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo(reason + SECOND_PRODUCT);
        assertThat(result.items()).isEmpty();
        assertThat(result.updatedAt()).isBetween(before, Instant.now());
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(first.getReserved()).isEqualTo(2L);
        assertThat(second.getReserved()).isEqualTo(originalReserved);
        assertThat(first.getOnHand()).isEqualTo(10L);
        assertThat(second.getOnHand()).isEqualTo(10L);
        assertThat(first.getUpdatedAt()).isEqualTo(OLD_TIMESTAMP);
        assertThat(second.getUpdatedAt()).isEqualTo(OLD_TIMESTAMP);
        verifyNoInteractions(reservationItemRepository);
    }

    @Test
    void rejectsDuplicateProductsBeforeAnyRepositoryCall() {
        assertThatThrownBy(() -> service.reserve(request(item(FIRST_PRODUCT, 1L), item(FIRST_PRODUCT, 2L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate productId: " + FIRST_PRODUCT);

        verifyNoInteractions(reservationRepository, reservationItemRepository, stockRepository);
    }

    @Test
    void replaysSameRequestWithReorderedItemsWithoutReservingAgain() {
        stubNewReservation();
        Stock first = stock(FIRST_PRODUCT, 10L, 2L);
        Stock second = stock(SECOND_PRODUCT, 10L, 1L);
        when(stockRepository.findByProductId(FIRST_PRODUCT)).thenReturn(Optional.of(first));
        when(stockRepository.findByProductId(SECOND_PRODUCT)).thenReturn(Optional.of(second));
        stubSaveItems();
        ReservationResponse original = service.reserve(request(item(FIRST_PRODUCT, 3L), item(SECOND_PRODUCT, 2L)));
        stubExistingReservation();
        clearInvocations(stockRepository, reservationItemRepository);

        ReservationResponse replayed = service.reserve(request(item(SECOND_PRODUCT, 2L), item(FIRST_PRODUCT, 3L)));

        assertThat(replayed).isEqualTo(original);
        assertThat(first.getReserved()).isEqualTo(5L);
        assertThat(second.getReserved()).isEqualTo(3L);
        verifyNoInteractions(stockRepository, reservationItemRepository);
    }

    @ParameterizedTest
    @EnumSource(ReservationStatus.class)
    void replaysPersistedStatusAndReasonWithoutTouchingStock(ReservationStatus status) {
        stubNewReservation();
        when(stockRepository.findByProductId(FIRST_PRODUCT)).thenReturn(Optional.empty());
        CreateReservationRequest request = request(item(FIRST_PRODUCT, 1L));
        service.reserve(request);
        reservation.setStatus(status);
        reservation.setRejectionReason(status == ReservationStatus.REJECTED ? "Previously rejected" : null);
        stubExistingReservation();
        clearInvocations(stockRepository, reservationItemRepository);

        ReservationResponse result = service.reserve(request);

        assertThat(result.status()).isEqualTo(status);
        assertThat(result.rejectionReason()).isEqualTo(reservation.getRejectionReason());
        assertThat(result.updatedAt()).isEqualTo(reservation.getUpdatedAt());
        verifyNoInteractions(stockRepository, reservationItemRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"currency", "quantity", "price", "product", "extraItem"})
    void conflictsWhenSameOrderIsReusedWithDifferentRequest(String changedField) {
        stubNewReservation();
        when(stockRepository.findByProductId(FIRST_PRODUCT)).thenReturn(Optional.empty());
        service.reserve(request(item(FIRST_PRODUCT, 1L)));
        stubExistingReservation();
        clearInvocations(stockRepository, reservationItemRepository);
        CreateReservationRequest changed = switch (changedField) {
            case "currency" -> new CreateReservationRequest(orderId, "USD", List.of(item(FIRST_PRODUCT, 1L)));
            case "quantity" -> request(item(FIRST_PRODUCT, 2L));
            case "price" -> request(new ReservationItemRequest(FIRST_PRODUCT, 1L, 200L));
            case "product" -> request(item(SECOND_PRODUCT, 1L));
            case "extraItem" -> request(item(FIRST_PRODUCT, 1L), item(SECOND_PRODUCT, 1L));
            default -> throw new IllegalArgumentException(changedField);
        };
        Instant originalUpdatedAt = reservation.getUpdatedAt();

        assertThatThrownBy(() -> service.reserve(changed))
                .isInstanceOf(ReservationConflictException.class)
                .hasMessageContaining(orderId.toString());

        assertThat(reservation.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        verifyNoInteractions(stockRepository, reservationItemRepository);
    }

    @Test
    void failsClearlyIfReservationCannotBeLoadedAfterInsert() {
        when(reservationRepository.insertIfAbsent(any(UUID.class), eq(orderId), anyString())).thenReturn(1);
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve(request(item(FIRST_PRODUCT, 1L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Reservation was not found after insertion");
        verifyNoInteractions(stockRepository, reservationItemRepository);
    }

    @Test
    void doesNotChangeStockWhenSavingItemsFails() {
        stubNewReservation();
        Stock stock = stock(FIRST_PRODUCT, 10L, 2L);
        when(stockRepository.findByProductId(FIRST_PRODUCT)).thenReturn(Optional.of(stock));
        RuntimeException failure = new RuntimeException("Storage unavailable");
        when(reservationItemRepository.saveAll(anyList())).thenThrow(failure);

        assertThatThrownBy(() -> service.reserve(request(item(FIRST_PRODUCT, 3L)))).isSameAs(failure);

        assertThat(stock.getReserved()).isEqualTo(2L);
        assertThat(stock.getUpdatedAt()).isEqualTo(OLD_TIMESTAMP);
        assertThat(reservation.getUpdatedAt()).isEqualTo(OLD_TIMESTAMP);
    }

    @Test
    void commitsPersistedItemsInStableLockOrderAndDoesNotWriteOffTwice() {
        Stock first = stock(FIRST_PRODUCT, 10L, 5L);
        Stock second = stock(SECOND_PRODUCT, 8L, 4L);
        // A paid reservation must still be committed if the catalog changed later.
        first.getProduct().setActive(false);
        first.getProduct().setPriceMinor(900L);
        reservation.setReservationItems(List.of(reservationItem(second, 2L), reservationItem(first, 3L)));
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(reservation));
        when(stockRepository.findByProductId(FIRST_PRODUCT)).thenReturn(Optional.of(first));
        when(stockRepository.findByProductId(SECOND_PRODUCT)).thenReturn(Optional.of(second));

        ReservationResponse result = service.commit(new CommitReservationRequest(orderId));

        assertThat(result.status()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(first.getOnHand()).isEqualTo(7L);
        assertThat(first.getReserved()).isEqualTo(2L);
        assertThat(second.getOnHand()).isEqualTo(6L);
        assertThat(second.getReserved()).isEqualTo(2L);
        assertThat(first.getUpdatedAt()).isAfter(OLD_TIMESTAMP);
        InOrder locks = inOrder(reservationRepository, stockRepository);
        locks.verify(reservationRepository).findByOrderId(orderId);
        locks.verify(stockRepository).findByProductId(FIRST_PRODUCT);
        locks.verify(stockRepository).findByProductId(SECOND_PRODUCT);

        clearInvocations(stockRepository);
        assertThat(service.commit(new CommitReservationRequest(orderId))).isEqualTo(result);
        assertThat(first.getOnHand()).isEqualTo(7L);
        assertThat(second.getOnHand()).isEqualTo(6L);
        verifyNoInteractions(stockRepository, reservationItemRepository);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"REJECTED", "RELEASED"})
    void cannotCommitAnUnreservedReservation(ReservationStatus status) {
        reservation.setStatus(status);
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.commit(new CommitReservationRequest(orderId)))
                .isInstanceOf(ReservationConflictException.class);

        assertThat(reservation.getStatus()).isEqualTo(status);
        verifyNoInteractions(stockRepository, reservationItemRepository);
    }

    @Test
    void cannotCommitMissingReservation() {
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.commit(new CommitReservationRequest(orderId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(orderId.toString());
        verifyNoInteractions(stockRepository, reservationItemRepository);
    }

    @Test
    void cannotCommitAnEmptyReservation() {
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.commit(new CommitReservationRequest(orderId)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no items");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        verifyNoInteractions(stockRepository, reservationItemRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "onHand", "reserved"})
    void inconsistentSecondStockDoesNotPartiallyCommitTheFirst(String cause) {
        Stock first = stock(FIRST_PRODUCT, 10L, 4L);
        Stock second = stock(SECOND_PRODUCT, 10L, 4L);
        reservation.setReservationItems(List.of(reservationItem(first, 2L), reservationItem(second, 3L)));
        if (cause.equals("onHand")) {
            second.setOnHand(2L);
        } else if (cause.equals("reserved")) {
            second.setReserved(2L);
        }
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(reservation));
        when(stockRepository.findByProductId(FIRST_PRODUCT)).thenReturn(Optional.of(first));
        when(stockRepository.findByProductId(SECOND_PRODUCT))
                .thenReturn(cause.equals("missing") ? Optional.empty() : Optional.of(second));

        assertThatThrownBy(() -> service.commit(new CommitReservationRequest(orderId)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(first.getOnHand()).isEqualTo(10L);
        assertThat(first.getReserved()).isEqualTo(4L);
        assertThat(first.getUpdatedAt()).isEqualTo(OLD_TIMESTAMP);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.getUpdatedAt()).isEqualTo(OLD_TIMESTAMP);
    }

    @Test
    void releasesAllItemsInStableLockOrderWithoutChangingOnHandAndDoesNotReleaseTwice() {
        Stock first = stock(FIRST_PRODUCT, 10L, 5L);
        Stock second = stock(SECOND_PRODUCT, 8L, 4L);
        reservation.setReservationItems(List.of(reservationItem(second, 2L), reservationItem(first, 3L)));
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(reservation));
        when(stockRepository.findByProductId(FIRST_PRODUCT)).thenReturn(Optional.of(first));
        when(stockRepository.findByProductId(SECOND_PRODUCT)).thenReturn(Optional.of(second));

        ReservationResponse result = service.release(new ReleaseReservationRequest(orderId));

        assertThat(result.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(first.getOnHand()).isEqualTo(10L);
        assertThat(first.getReserved()).isEqualTo(2L);
        assertThat(second.getOnHand()).isEqualTo(8L);
        assertThat(second.getReserved()).isEqualTo(2L);
        assertThat(first.getUpdatedAt()).isAfter(OLD_TIMESTAMP);
        InOrder locks = inOrder(reservationRepository, stockRepository);
        locks.verify(reservationRepository).insertReleasedIfAbsent(any(UUID.class), eq(orderId));
        locks.verify(reservationRepository).findByOrderId(orderId);
        locks.verify(stockRepository).findByProductId(FIRST_PRODUCT);
        locks.verify(stockRepository).findByProductId(SECOND_PRODUCT);

        clearInvocations(stockRepository);
        assertThat(service.release(new ReleaseReservationRequest(orderId))).isEqualTo(result);
        assertThat(first.getReserved()).isEqualTo(2L);
        assertThat(second.getReserved()).isEqualTo(2L);
        verifyNoInteractions(stockRepository, reservationItemRepository);
    }

    @Test
    void earlyReleasePreventsLateReserveAndCommit() {
        when(reservationRepository.insertReleasedIfAbsent(any(UUID.class), eq(orderId)))
                .thenAnswer(invocation -> {
                    reservation.setId(invocation.getArgument(0));
                    reservation.setStatus(ReservationStatus.RELEASED);
                    reservation.setUpdatedAt(Instant.now());
                    return 1;
                });
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(reservation));

        ReservationResponse released = service.release(new ReleaseReservationRequest(orderId));
        ReservationResponse lateReserve = service.reserve(request(item(FIRST_PRODUCT, 3L)));

        assertThat(released.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(lateReserve).isEqualTo(released);
        assertThat(reservation.getRequestHash()).isNull();
        assertThatThrownBy(() -> service.commit(new CommitReservationRequest(orderId)))
                .isInstanceOf(ReservationConflictException.class);
        verifyNoInteractions(stockRepository, reservationItemRepository);
    }

    @Test
    void cannotReleaseCommittedReservation() {
        reservation.setStatus(ReservationStatus.COMMITTED);
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.release(new ReleaseReservationRequest(orderId)))
                .isInstanceOf(ReservationConflictException.class);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        verifyNoInteractions(stockRepository, reservationItemRepository);
    }

    @Test
    void releaseOfRejectedReservationKeepsOriginalRejection() {
        reservation.setStatus(ReservationStatus.REJECTED);
        reservation.setRejectionReason("Insufficient stock");
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(reservation));

        ReservationResponse result = service.release(new ReleaseReservationRequest(orderId));

        assertThat(result.status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo("Insufficient stock");
        assertThat(result.updatedAt()).isEqualTo(OLD_TIMESTAMP);
        verifyNoInteractions(stockRepository, reservationItemRepository);
    }

    @Test
    void cannotReleaseAnEmptyReservedReservation() {
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.release(new ReleaseReservationRequest(orderId)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no items");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        verifyNoInteractions(stockRepository, reservationItemRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "reserved"})
    void inconsistentSecondStockDoesNotPartiallyReleaseTheFirst(String cause) {
        Stock first = stock(FIRST_PRODUCT, 10L, 4L);
        Stock second = stock(SECOND_PRODUCT, 10L, 2L);
        reservation.setReservationItems(List.of(reservationItem(first, 2L), reservationItem(second, 3L)));
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(reservation));
        when(stockRepository.findByProductId(FIRST_PRODUCT)).thenReturn(Optional.of(first));
        when(stockRepository.findByProductId(SECOND_PRODUCT))
                .thenReturn(cause.equals("missing") ? Optional.empty() : Optional.of(second));

        assertThatThrownBy(() -> service.release(new ReleaseReservationRequest(orderId)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(first.getOnHand()).isEqualTo(10L);
        assertThat(first.getReserved()).isEqualTo(4L);
        assertThat(first.getUpdatedAt()).isEqualTo(OLD_TIMESTAMP);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void normalReleaseKeepsRequestHashAndRejectsChangedLateReserve() {
        stubNewReservation();
        Stock stock = stock(FIRST_PRODUCT, 10L, 0L);
        when(stockRepository.findByProductId(FIRST_PRODUCT)).thenReturn(Optional.of(stock));
        stubSaveItems();
        service.reserve(request(item(FIRST_PRODUCT, 2L)));
        String originalHash = reservation.getRequestHash();
        service.release(new ReleaseReservationRequest(orderId));
        stubExistingReservation();
        clearInvocations(stockRepository);

        assertThat(service.reserve(request(item(FIRST_PRODUCT, 2L))).status())
                .isEqualTo(ReservationStatus.RELEASED);
        assertThatThrownBy(() -> service.reserve(request(item(FIRST_PRODUCT, 3L))))
                .isInstanceOf(ReservationConflictException.class);
        assertThat(reservation.getRequestHash()).isEqualTo(originalHash);
        assertThat(stock.getReserved()).isZero();
        assertThat(stock.getOnHand()).isEqualTo(10L);
        verifyNoInteractions(stockRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"before", "start", "during", "end", "after", "no-start", "no-end", "unbounded"})
    void respectsSaleWindowBoundaries(String scenario) {
        stubNewReservation();
        Stock stock = stock(FIRST_PRODUCT, 10L, 2L);
        Product product = stock.getProduct();
        product.setSaleStartsAt(OLD_TIMESTAMP);
        product.setSaleEndsAt(OLD_TIMESTAMP.plusSeconds(60));
        Instant now = switch (scenario) {
            case "before" -> OLD_TIMESTAMP.minusNanos(1);
            case "end" -> OLD_TIMESTAMP.plusSeconds(60);
            case "after" -> OLD_TIMESTAMP.plusSeconds(61);
            case "during" -> OLD_TIMESTAMP.plusSeconds(30);
            default -> OLD_TIMESTAMP;
        };
        if (scenario.equals("no-start") || scenario.equals("unbounded")) product.setSaleStartsAt(null);
        if (scenario.equals("no-end") || scenario.equals("unbounded")) product.setSaleEndsAt(null);
        when(clock.instant()).thenReturn(now);
        when(stockRepository.findByProductId(FIRST_PRODUCT)).thenReturn(Optional.of(stock));
        boolean rejected = List.of("before", "end", "after").contains(scenario);
        if (!rejected) stubSaveItems();

        ReservationResponse result = service.reserve(request(item(FIRST_PRODUCT, 3L)));

        assertThat(result.status()).isEqualTo(rejected ? ReservationStatus.REJECTED : ReservationStatus.RESERVED);
        assertThat(stock.getReserved()).isEqualTo(rejected ? 2L : 5L);
        assertThat(stock.getOnHand()).isEqualTo(10L);
        if (rejected) {
            assertThat(result.rejectionReason()).contains(scenario.equals("before") ? "not started" : "ended");
            verifyNoInteractions(reservationItemRepository);
        }
        InOrder order = inOrder(stockRepository, clock);
        order.verify(stockRepository).findByProductId(FIRST_PRODUCT);
        order.verify(clock).instant();
    }

    @Test
    void expiredSecondItemRejectsEntireReservation() {
        stubNewReservation();
        Stock first = stock(FIRST_PRODUCT, 10L, 2L);
        Stock second = stock(SECOND_PRODUCT, 10L, 1L);
        second.getProduct().setSaleEndsAt(OLD_TIMESTAMP);
        when(stockRepository.findByProductId(FIRST_PRODUCT)).thenReturn(Optional.of(first));
        when(stockRepository.findByProductId(SECOND_PRODUCT)).thenReturn(Optional.of(second));

        assertThat(service.reserve(request(item(FIRST_PRODUCT, 3L), item(SECOND_PRODUCT, 3L))).status())
                .isEqualTo(ReservationStatus.REJECTED);

        assertThat(first.getReserved()).isEqualTo(2L);
        assertThat(second.getReserved()).isEqualTo(1L);
        verifyNoInteractions(reservationItemRepository);
        InOrder order = inOrder(stockRepository, clock);
        order.verify(stockRepository).findByProductId(FIRST_PRODUCT);
        order.verify(stockRepository).findByProductId(SECOND_PRODUCT);
        order.verify(clock).instant();
        verify(clock, times(1)).instant();
    }

    @ParameterizedTest
    @ValueSource(strings = {"replay", "commit", "release"})
    void existingReservationRemainsUsableAfterSaleEnds(String operation) {
        stubNewReservation();
        Stock stock = stock(FIRST_PRODUCT, 10L, 0L);
        stock.getProduct().setSaleEndsAt(OLD_TIMESTAMP.plusSeconds(60));
        when(stockRepository.findByProductId(FIRST_PRODUCT)).thenReturn(Optional.of(stock));
        stubSaveItems();
        service.reserve(request(item(FIRST_PRODUCT, 2L)));
        lenient().when(clock.instant()).thenReturn(OLD_TIMESTAMP.plusSeconds(60));
        clearInvocations(clock);

        ReservationResponse result;
        if (operation.equals("replay")) {
            stubExistingReservation();
            result = service.reserve(request(item(FIRST_PRODUCT, 2L)));
        } else if (operation.equals("commit")) {
            result = service.commit(new CommitReservationRequest(orderId));
        } else {
            result = service.release(new ReleaseReservationRequest(orderId));
        }

        assertThat(result.status()).isEqualTo(switch (operation) {
            case "commit" -> ReservationStatus.COMMITTED;
            case "release" -> ReservationStatus.RELEASED;
            default -> ReservationStatus.RESERVED;
        });
        assertThat(stock.getReserved()).isEqualTo(operation.equals("replay") ? 2L : 0L);
        assertThat(stock.getOnHand()).isEqualTo(operation.equals("commit") ? 8L : 10L);
        verifyNoInteractions(clock);
    }

    private ReservationItem reservationItem(Stock stock, long quantity) {
        return ReservationItem.builder().id(UUID.randomUUID()).reservation(reservation)
                .product(stock.getProduct()).quantity(quantity).build();
    }

    private void stubNewReservation() {
        when(reservationRepository.insertIfAbsent(any(UUID.class), eq(orderId), anyString()))
                .thenAnswer(invocation -> {
                    reservation.setId(invocation.getArgument(0));
                    reservation.setRequestHash(invocation.getArgument(2));
                    return 1;
                });
        when(reservationRepository.findByOrderId(orderId)).thenReturn(Optional.of(reservation));
    }

    private void stubExistingReservation() {
        doReturn(0).when(reservationRepository).insertIfAbsent(any(UUID.class), eq(orderId), anyString());
    }

    private void stubSaveItems() {
        when(reservationItemRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CreateReservationRequest request(ReservationItemRequest... items) {
        return new CreateReservationRequest(orderId, "UAH", List.of(items));
    }

    private ReservationItemRequest item(UUID productId, long quantity) {
        return new ReservationItemRequest(productId, quantity, 100L);
    }

    private Stock stock(UUID productId, long onHand, long reserved) {
        Product product = Product.builder().id(productId).active(true).currency("UAH").priceMinor(100L).build();
        return Stock.builder().id(UUID.randomUUID()).product(product).onHand(onHand).reserved(reserved)
                .updatedAt(OLD_TIMESTAMP).build();
    }
}
