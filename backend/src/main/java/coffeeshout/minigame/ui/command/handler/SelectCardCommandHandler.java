package coffeeshout.minigame.ui.command.handler;

import coffeeshout.cardgame.domain.service.CardGameCommandService;
import coffeeshout.global.exception.custom.InvalidArgumentException;
import coffeeshout.global.exception.custom.InvalidStateException;
import coffeeshout.minigame.ui.command.MiniGameCommandHandler;
import coffeeshout.minigame.ui.request.command.SelectCardCommand;
import coffeeshout.room.domain.JoinCode;
import coffeeshout.room.domain.player.PlayerName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SelectCardCommandHandler implements MiniGameCommandHandler<SelectCardCommand> {

    private final CardGameCommandService cardGameCommandService;

    @Override
    public void handle(final String joinCode, final SelectCardCommand command) {
        try {
            cardGameCommandService.selectCard(
                    new JoinCode(joinCode),
                    new PlayerName(command.playerName()),
                    command.cardIndex()
            );
            log.info("카드 선택 처리 성공: joinCode={}, playerName={}, cardIndex={}",
                    joinCode, command.playerName(), command.cardIndex());
        } catch (InvalidArgumentException | InvalidStateException e) {
            log.warn("카드 선택 처리 중 오류 발생: joinCode={}, playerName={}, cardIndex={}",
                    joinCode, command.playerName(), command.cardIndex(), e);
        } catch (Exception e) {
            log.error("카드 선택 처리 실패: joinCode={}, playerName={}, cardIndex={}",
                    joinCode, command.playerName(), command.cardIndex(), e);
        }
    }

    @Override
    public Class<SelectCardCommand> getCommandType() {
        return SelectCardCommand.class;
    }
}
