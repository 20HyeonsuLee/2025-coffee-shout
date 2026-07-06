const { connectWebSocket } = require('./helpers/connect-websocket');
const { sendReadyTrue, sendReadyStorm } = require('./publish/ready');
const { setup } = require('./helpers/set-up');
const { setupRemovalStorm, disconnectGuests, reconnectHalfGuests } = require('./helpers/removal-storm');
const { sendUpdateMiniGames, sendStartMiniGame, sendTap, sendContinuousTaps, stopContinuousTaps } = require('./publish/mini-game');


module.exports = {
  connectWebSocket,
  sendReadyTrue,
  sendReadyStorm,
  sendUpdateMiniGames,
  sendStartMiniGame,
  sendTap,
  sendContinuousTaps,
  stopContinuousTaps,
  setup,
  setupRemovalStorm,
  disconnectGuests,
  reconnectHalfGuests
};
