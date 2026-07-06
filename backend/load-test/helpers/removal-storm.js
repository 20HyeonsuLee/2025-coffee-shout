const { Client } = require('@stomp/stompjs');
const SockJS = require('sockjs-client');
const { createRoomAndEnter } = require('./create-room');

const hostName = 'host';

/**
 * playerKey 바인딩된 WebSocket 연결.
 *
 * 기존 connect-websocket.js는 connectHeaders가 비어 있어 서버가 세션과 playerKey를
 * 연결하지 못한다 (SessionConnectEventListener가 joinCode/playerName 헤더 요구).
 * 지연 삭제 스케줄링은 playerKey 바인딩이 전제이므로 헤더를 포함해 연결한다.
 */
function connectBound(context, playerName, joinCode) {
  return new Promise((resolve, reject) => {
    const host = context.vars.host;
    const client = new Client({
      webSocketFactory: () => new SockJS(`${host}/ws`),
      connectHeaders: { joinCode, playerName },
      reconnectDelay: 0,
      heartbeatIncoming: 0,
      heartbeatOutgoing: 0,
    });

    client.onConnect = () => {
      context.vars.boundClients[joinCode][playerName] = client;
      resolve();
    };
    client.onStompError = frame => reject(new Error(`STOMP error for ${playerName}: ${frame.headers['message']}`));
    client.onWebSocketError = () => reject(new Error(`WebSocket error for ${playerName}`));

    client.activate();
  });
}

async function setupRemovalStorm(context) {
  const guestCount = Number(context.vars.guestCount ?? 7);
  const roomCount = Number(context.vars.roomCount ?? 20);
  const guests = Array.from({ length: guestCount }, (_, i) => `guest${i + 1}`);

  context.vars.hostName = hostName;
  context.vars.guests = guests;
  context.vars.joinCodes = [];
  context.vars.boundClients = {};

  for (let i = 0; i < roomCount; i++) {
    await createRoomAndEnter(context);
    context.vars.joinCodes.push(context.vars.joinCode);
    console.log(`Room ${i + 1}/${roomCount} created: ${context.vars.joinCode}`);
  }

  for (const joinCode of context.vars.joinCodes) {
    context.vars.boundClients[joinCode] = {};
    await connectBound(context, hostName, joinCode);
    for (const guestName of guests) {
      await connectBound(context, guestName, joinCode);
    }
  }
  console.log(`All bound connections established (${roomCount} rooms x ${guestCount + 1} players)`);
}

/** 모든 guest 연결을 끊어 지연 삭제 스케줄 storm을 일으킨다. host는 유지. */
async function disconnectGuests(context, events) {
  let count = 0;
  for (const joinCode of context.vars.joinCodes) {
    for (const guestName of context.vars.guests) {
      const client = context.vars.boundClients[joinCode][guestName];
      if (client) {
        await client.deactivate();
        count++;
      }
    }
  }
  console.log(`Disconnected ${count} guests → removal schedule storm`);
}

/** grace period(15초) 안에 guest 절반을 재접속시켜 취소 경로를 검증한다. */
async function reconnectHalfGuests(context, events) {
  const half = Math.floor(context.vars.guests.length / 2);
  let count = 0;
  for (const joinCode of context.vars.joinCodes) {
    for (const guestName of context.vars.guests.slice(0, half)) {
      await connectBound(context, guestName, joinCode);
      count++;
    }
  }
  console.log(`Reconnected ${count} guests within grace period → cancel path`);
}

module.exports = { setupRemovalStorm, disconnectGuests, reconnectHalfGuests };
