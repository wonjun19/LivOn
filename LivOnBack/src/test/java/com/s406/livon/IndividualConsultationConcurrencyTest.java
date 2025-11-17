package com.s406.livon;

import com.s406.livon.domain.coach.dto.request.IndivualConsultationReservationRequestDto;
import com.s406.livon.domain.coach.entity.Consultation;
import com.s406.livon.domain.coach.repository.ConsultationReservationRepository;
import com.s406.livon.domain.coach.repository.IndividualConsultationRepository;
import com.s406.livon.domain.coach.repository.ParticipantRepository;
import com.s406.livon.domain.coach.service.IndividualConsultationService;
import com.s406.livon.domain.goodsChat.repository.GoodsChatRoomRepository;
import com.s406.livon.domain.user.entity.User;
import com.s406.livon.domain.user.enums.Gender;
import com.s406.livon.domain.user.enums.Role;
import com.s406.livon.domain.user.repository.UserRepository;
import com.s406.livon.global.error.handler.CoachHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class IndividualConsultationConcurrencyTest {

    @Autowired
    private IndividualConsultationService individualConsultationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConsultationReservationRepository consultationRepository;

    @Autowired
    private ParticipantRepository participantRepository;

    @Autowired
    private IndividualConsultationRepository individualConsultationRepository;

    private User coach;
    private List<User> users;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String testPrefix;
    private List<User> createdUsers; // 정리용

    @BeforeEach
    void setUp() {
        // 고유 테스트 식별자 생성
        testPrefix = "TEST_" + UUID.randomUUID().toString().substring(0, 8);
        createdUsers = new ArrayList<>();

        // 코치 생성 (기존 데이터와 절대 겹치지 않는 고유한 데이터)
        coach = createTestCoach();

        // 일반 사용자 10명 생성
        users = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            User user = createTestUser(i);
            users.add(user);
        }

        // 검증 규칙에 맞는 상담 시간 설정
        startAt = createValidDateTime();
        endAt = startAt.plusHours(1);
    }

    @AfterEach
    void tearDown() {
        // 테스트에서 생성한 데이터만 안전하게 정리
        cleanupTestData();
    }

    private User createTestCoach() {
        User coach = User.builder()
                        .email(testPrefix + "_coach@test.com")
                        .nickname(testPrefix + "_코치")
                        .password("password")
                        .gender(Gender.남자)
                        .birthdate(LocalDate.of(1990, 1, 1))
                        .roles(List.of(Role.COACH))
                        .build();

        User savedCoach = userRepository.save(coach);
        createdUsers.add(savedCoach);
        return savedCoach;
    }

    private User createTestUser(int index) {
        User user = User.builder()
                        .email(testPrefix + "_user" + index + "@test.com")
                        .nickname(testPrefix + "_사용자" + index)
                        .password("password")
                        .gender(Gender.남자)
                        .birthdate(LocalDate.of(1995, 1, 1))
                        .roles(List.of(Role.MEMBER))  // MEMBER 역할로 수정
                        .build();

        User savedUser = userRepository.save(user);
        createdUsers.add(savedUser);
        return savedUser;
    }

    /**
     * validateReservationTime 규칙에 맞는 날짜 생성:
     * - 미래 시간 (과거 금지)
     * - 정시 (분,초,나노초 모두 0)
     * - 09:00-18:00 범위
     * - DEFAULT_TIME_SLOTS에 존재하는 시간
     */
    private LocalDateTime createValidDateTime() {
        return LocalDateTime.now()
                        .plusDays(7)  // 1주일 후로 설정 (충분히 미래)
                        .withHour(14)  // 14:00 (오후 2시, 허용 범위 내)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);   // 나노초까지 0으로 설정
    }

    /**
     * 다른 시간대용 검증된 시간 생성
     */
    private LocalDateTime createValidDateTimeWithOffset(int hourOffset) {
        int targetHour = 9 + hourOffset; // 9시부터 시작
        if (targetHour >= 18) { // 18시 이후면 다음날로
            return LocalDateTime.now()
                            .plusDays(8)
                            .withHour(9 + (hourOffset % 9)) // 9-17시 범위 내에서 순환
                            .withMinute(0)
                            .withSecond(0)
                            .withNano(0);
        }

        return LocalDateTime.now()
                        .plusDays(7)
                        .withHour(targetHour)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
    }

    private void cleanupTestData() {
        try {
            // 1. 이 테스트에서 생성한 상담과 관련 데이터 정리
            if (coach != null) {
                List<Consultation> testConsultations = consultationRepository.findByCoachId(coach.getId());

                for (Consultation consultation : testConsultations) {
                    // 참가자 삭제
                    participantRepository.deleteByConsultationId(consultation.getId());
                    // 개별상담 삭제 (있다면)
                    if (individualConsultationRepository.existsById(consultation.getId())) {
                        individualConsultationRepository.deleteById(consultation.getId());
                    }
                }

                // 상담 삭제
                consultationRepository.deleteAll(testConsultations);
            }

            // 2. 생성한 사용자들 삭제
            if (createdUsers != null && !createdUsers.isEmpty()) {
                userRepository.deleteAll(createdUsers);
            }

        } catch (Exception e) {
            System.err.println("테스트 데이터 정리 중 오류 발생: " + e.getMessage());
            // 정리 실패해도 테스트는 계속 진행
        }
    }

    @Test
    @DisplayName("동시에 10명이 같은 시간대 1:1 상담 예약 시 1명만 성공해야 한다")
    void concurrentReservationTest() throws InterruptedException {
        // given
        int threadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when
        for (int i = 0; i < threadCount; i++) {
            int index = i;
            executorService.submit(() -> {
                try {
                    IndivualConsultationReservationRequestDto requestDto =
                                    new IndivualConsultationReservationRequestDto(
                                                    coach.getId(),
                                                    startAt,
                                                    endAt,
                                                    "사전 질문 " + index
                                    );

                    individualConsultationService.reserveConsultation(
                                    users.get(index).getId(),
                                    requestDto
                    );
                    successCount.incrementAndGet();
                    System.out.println("✅ 성공: 사용자" + index);

                } catch (CoachHandler e) {
                    failCount.incrementAndGet();
                    String errorMessage = e.getCode() != null
                                    ? e.getCode().getMessage()
                                    : e.getMessage();
                    System.out.println("❌ 실패(CoachHandler): 사용자" + index + " - " + errorMessage);

                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.out.println("❌ 실패(Exception): 사용자" + index + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());

                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // then
        System.out.println("\n=== 테스트 결과 ===");
        System.out.println("성공 횟수: " + successCount.get());
        System.out.println("실패 횟수: " + failCount.get());

        // 정확히 1명만 성공해야 함
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(9);

        // 생성된 상담 수 확인
        List<Consultation> consultations = consultationRepository.findByCoachId(coach.getId());
        assertThat(consultations).hasSize(1);
    }

    @Test
    @DisplayName("다른 시간대 예약은 동시에 진행 가능해야 한다")
    void differentTimeSlotReservationTest() throws InterruptedException {
        // given
        int threadCount = 5;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);

        // when - 각각 다른 시간대로 예약
        for (int i = 0; i < threadCount; i++) {
            int hourOffset = i;
            executorService.submit(() -> {
                try {
                    LocalDateTime slotStart = createValidDateTimeWithOffset(hourOffset);
                    LocalDateTime slotEnd = slotStart.plusHours(1);

                    IndivualConsultationReservationRequestDto requestDto =
                                    new IndivualConsultationReservationRequestDto(
                                                    coach.getId(),
                                                    slotStart,
                                                    slotEnd,
                                                    "사전 질문 " + hourOffset
                                    );

                    individualConsultationService.reserveConsultation(
                                    users.get(hourOffset).getId(),
                                    requestDto
                    );
                    successCount.incrementAndGet();
                    System.out.println("✅ 성공: " + hourOffset + "시간 후 예약");

                } catch (Exception e) {
                    System.out.println("❌ 실패: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // then
        System.out.println("\n=== 다른 시간대 예약 결과 ===");
        System.out.println("성공 횟수: " + successCount.get());

        // 모두 성공해야 함
        assertThat(successCount.get()).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("같은 시간대, 다른 코치 예약은 동시에 진행 가능해야 한다")
    void differentCoachReservationTest() throws InterruptedException {
        // given - 추가 코치 생성
        User coach2 = User.builder()
                        .email(testPrefix + "_coach2@test.com")
                        .nickname(testPrefix + "_코치2")
                        .password("password")
                        .gender(Gender.여자)
                        .birthdate(LocalDate.of(1990, 1, 1))
                        .roles(List.of(Role.COACH))
                        .build();
        coach2 = userRepository.save(coach2);
        createdUsers.add(coach2); // 정리 목록에 추가

        int threadCount = 2;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);

        // when - 같은 시간, 다른 코치에게 예약
        List<User> coaches = List.of(coach, coach2);
        for (int i = 0; i < threadCount; i++) {
            int index = i;
            executorService.submit(() -> {
                try {
                    IndivualConsultationReservationRequestDto requestDto =
                                    new IndivualConsultationReservationRequestDto(
                                                    coaches.get(index).getId(),
                                                    startAt,
                                                    endAt,
                                                    "사전 질문 " + index
                                    );

                    individualConsultationService.reserveConsultation(
                                    users.get(index).getId(),
                                    requestDto
                    );
                    successCount.incrementAndGet();
                    System.out.println("✅ 성공: 코치" + (index + 1) + " 예약");

                } catch (Exception e) {
                    System.out.println("❌ 실패: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // then
        System.out.println("\n=== 다른 코치 예약 결과 ===");
        System.out.println("성공 횟수: " + successCount.get());

        // 모두 성공해야 함 (다른 코치니까)
        assertThat(successCount.get()).isEqualTo(threadCount);
    }
}
