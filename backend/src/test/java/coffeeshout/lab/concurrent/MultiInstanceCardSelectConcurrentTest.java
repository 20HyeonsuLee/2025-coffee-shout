package coffeeshout.lab.concurrent;

import static coffeeshout.room.domain.menu.MenuTemperature.ICE;

import coffeeshout.CoffeeShoutApplication;
import coffeeshout.cardgame.domain.CardGame;
import coffeeshout.cardgame.domain.CardHand;
import coffeeshout.minigame.domain.MiniGameType;
import coffeeshout.minigame.ui.command.handler.SelectCardCommandHandler;
import coffeeshout.minigame.ui.request.command.SelectCardCommand;
import coffeeshout.room.application.RoomService;
import coffeeshout.room.domain.Room;
import coffeeshout.room.domain.player.Player;
import coffeeshout.room.ui.request.SelectedMenuRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 멀티 인스턴스 환경에서 카드 선택 동시성 테스트
 * <p>
 * 목적: Redis Stream을 통한 카드 선택 이벤트 처리 시 동시성 문제 검증
 * <p>
 * 시나리오: 1. 8080, 8081 포트에 두 개의 Spring Boot 인스턴스 시작 2. 두 인스턴스에 동일한 방/게임 상태 생성 3. 9명의 플레이어가 Redis Stream을 통해 동시에 같은
 * 카드(cardIndex=0) 선택 4. 각 인스턴스의 상태 확인 -> 중복 선택 발생 여부 검증
 * <p>
 * 실행 전 준비: - Redis 실행: docker run -d -p 6379:6379 redis:7-alpine
 */
//@Disabled("멀티 인스턴스 테스트 - 필요 시 수동 실행")
class MultiInstanceCardSelectConcurrentTest {

    private List<ConfigurableApplicationContext> instances;

    @BeforeEach
    void setUp() {
        instances = Stream.of(8080, 8081).map(this::initializeApplication).toList();
    }

    @AfterEach
    void tearDown() {
//        instances.forEach(ConfigurableApplicationContext::close);
    }

    private ConfigurableApplicationContext initializeApplication(int port) {
        return new SpringApplicationBuilder(CoffeeShoutApplication.class)
                .profiles("local")
                .properties(
                        "server.port=" + port,
                        "spring.redis.host=" + "localhost",
                        "spring.redis.port=" + 6379,
                        "logging.level.coffeeshout.cardgame=DEBUG"
                ).run();
    }

    private String createRoom(String hostName) {
        String joinCode = "";
        for (var instance : instances) {
            final Room room = createRoom(hostName, instance);
            joinCode = room.getJoinCode().getValue();
        }
        return joinCode;
    }

    private void enterRoom(String guestName, String joinCode) {
        instances.forEach(instance -> enterRoom(guestName, joinCode, instance));
    }

    private void initializeMiniGame(String joinCode, String hostName) {
        instances.forEach(instance -> initializeMiniGame(joinCode, hostName, instance));
    }

    private Room createRoom(String hostName, ConfigurableApplicationContext instance) {
        var roomService = instance.getBean(RoomService.class);
        return roomService.createRoom(hostName, new SelectedMenuRequest(1L, null, ICE));
    }

    private void enterRoom(String guestName, String joinCode, ConfigurableApplicationContext instance) {
        var roomService = instance.getBean(RoomService.class);
        roomService.enterRoom(joinCode, guestName, new SelectedMenuRequest(1L, null, ICE));
    }

    private void initializeMiniGame(String joinCode, String hostName, ConfigurableApplicationContext instance) {
        var roomService = instance.getBean(RoomService.class);
        var miniGames = List.of(MiniGameType.CARD_GAME);
        roomService.updateMiniGames(joinCode, hostName, miniGames);
    }

    private void startCardGame(String joinCode, String hostName) {
        instances.forEach(instance -> startCardGame(joinCode, hostName, instance));
    }

    private void startCardGame(String joinCode, String hostName, ConfigurableApplicationContext instance) {
        var roomService = instance.getBean(RoomService.class);
        final Room room = roomService.getRoomByJoinCode(joinCode);
        room.getPlayers().forEach(player -> player.updateReadyState(true));
        final CardGame cardGame = (CardGame) room.startNextGame(hostName);
        cardGame.startPlay();
    }

    private void selectCard(String joinCode, String playerName, ConfigurableApplicationContext instance) {
        var handler = instance.getBean(SelectCardCommandHandler.class);
        handler.handle(joinCode, new SelectCardCommand(playerName, 0));
    }

    /*
    Redis Stream에서 너무 빠르게 pull해서 같은 카드가 선택될 가능성은?
     */

    private CardGame getCardGame(String joinCode, String hostName,  ConfigurableApplicationContext instance) {
        var roomService = instance.getBean(RoomService.class);
        final Room room = roomService.getRoomByJoinCode(joinCode);
        return (CardGame) room.findMiniGame(MiniGameType.CARD_GAME);
    }

    @Test
    void 두_인스턴스에서_9명이_동시에_같은_카드_선택_시_동시성_문제_발생() throws Exception {
        final String hostName = "host";
        final String guestNameFormat = "guest %d";

        final String joinCode = createRoom(hostName);

        IntStream.range(1, 9).forEach(index -> enterRoom(String.format(guestNameFormat, index), joinCode));

        initializeMiniGame(joinCode, hostName);

        startCardGame(joinCode, hostName);

        final CountDownLatch readyLatch = new CountDownLatch(instances.size());
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(instances.size());

        final ExecutorService executorService = Executors.newFixedThreadPool(instances.size());

        executeSelectCard(joinCode, "host", instances.get(0), executorService, readyLatch, startLatch, doneLatch);
        executeSelectCard(joinCode, "guest 1", instances.get(1), executorService, readyLatch, startLatch, doneLatch);

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();

        executorService.shutdown();

        Thread.sleep(5000);


    }

    private void executeSelectCard(
            String joinCode,
            String playerName,
            ConfigurableApplicationContext instance,
            ExecutorService executorService,
            CountDownLatch readyLatch,
            CountDownLatch startLatch,
            CountDownLatch doneLatch
    ) {
        executorService.submit(() -> {
            try {
                readyLatch.countDown();
                startLatch.await();

                selectCard(joinCode, playerName, instance);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });
    }
}
