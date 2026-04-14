const { Client } = require('@stomp/stompjs');
const SockJS = require('sockjs-client');

function connectWebSocket(context, events, done, playerName, joinCode) {
  const host = context.vars.host;

  if (!context.vars.stompClients) {
    context.vars.stompClients = {};
  }

  if (!context.vars.stompClients[joinCode]) {
    context.vars.stompClients[joinCode] = {};
  }

  console.log(`Connecting WebSocket for player: ${playerName}, room: ${joinCode}`);

  const client = new Client({
    webSocketFactory: () => new SockJS(`${host}/ws`),
    connectHeaders: {},
    reconnectDelay: 0,
    heartbeatIncoming: 0,
    heartbeatOutgoing: 0,
  });

  client.onConnect = function () {
    console.log(`WebSocket connected for ${playerName} in room ${joinCode}`);

    let lastMessageTime = null;
    let messageCount = 0;

    client.subscribe(`/topic/room/${joinCode}/racing-game`, function (message) {
      const currentTime = Date.now();
      messageCount++;

      const data = JSON.parse(message.body);

      if (lastMessageTime === null) {
        console.log(`[${joinCode}/${playerName}] #${messageCount} First message received`);
      } else {
        const delay = currentTime - lastMessageTime;
        console.log(`[${joinCode}/${playerName}] #${messageCount} Delay: ${delay}ms (expected: 100ms, diff: ${delay - 100}ms)`);
      }
      console.log(data.data.players[0].position)
      lastMessageTime = currentTime;
    });

    client.subscribe(`/topic/room/${joinCode}/racing-game/state`, function (message) {
      const data = JSON.parse(message.body);
      console.log(data);
    });

    context.vars.stompClients[joinCode][playerName] = client;

    return done();
  };

  client.onStompError = function (frame) {
    console.error(`STOMP error for ${playerName}:`, frame.headers['message']);
    console.error('Details:', frame.body);
    return done(new Error('STOMP connection failed'));
  };

  client.onWebSocketError = function (event) {
    console.error(`WebSocket error for ${playerName}:`, event);
    return done(new Error('WebSocket connection failed'));
  };

  client.activate();
}

module.exports = { connectWebSocket };
