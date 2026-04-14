const { connectWebSocket } = require('./helpers/connect-websocket');
const { sendReadyTrue } = require('./publish/ready');
const { setup } = require('./helpers/set-up');
const { sendUpdateMiniGames, sendStartMiniGame, sendTap, sendContinuousTaps, stopContinuousTaps } = require('./publish/mini-game');


module.exports = {
  connectWebSocket,
  sendReadyTrue,
  sendUpdateMiniGames,
  sendStartMiniGame,
  sendTap,
  sendContinuousTaps,
  stopContinuousTaps,
  setup
};
