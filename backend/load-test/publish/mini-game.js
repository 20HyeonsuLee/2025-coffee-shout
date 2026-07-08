function sendUpdateMiniGames(context, events, done) {
  const clients = context.vars.stompClients;

  if (!clients) {
    console.error('No STOMP clients found');
    return done(new Error('No STOMP clients'));
  }

  for (const joinCode in clients) {
    const hostClient = clients[joinCode]["host"];

    if (!hostClient || typeof hostClient.publish !== 'function') {
      console.warn(`Skipping ${joinCode} - host client not ready`);
      continue;
    }

    hostClient.publish({
      destination: `/app/room/${joinCode}/update-minigames`,
      body: JSON.stringify({
        "hostName": "host",
        "miniGameTypes": [
          "RACING_GAME"
        ]
      })
    });

    console.log(`✅ Update minigames sent: ${joinCode}`);
  }

  return done();
}

function sendStartMiniGame(context, events, done) {
  const clients = context.vars.stompClients;

  if (!clients) {
    console.error('No STOMP clients found');
    return done(new Error('No STOMP clients'));
  }

  for (const joinCode in clients) {
    const hostClient = clients[joinCode]["host"];

    if (!hostClient || typeof hostClient.publish !== 'function') {
      console.warn(`Skipping ${joinCode} - host client not ready`);
      continue;
    }

    hostClient.publish({
      destination: `/app/room/${joinCode}/minigame/command`,
      body: JSON.stringify({
        "commandRequest": {
          "hostName": "host"
        },
        "commandType": "START_MINI_GAME"
      })
    });

    console.log(`✅ Start minigame command sent: ${joinCode}`);
  }

  return done();
}

function sendTap(context, events, done) {
  const allClients = context.vars.stompClients;

  if (!allClients) {
    console.error('No STOMP clients found');
    return done(new Error('No STOMP clients'));
  }

  for (const joinCode in allClients) {
    const roomClients = allClients[joinCode];

    for (const playerName in roomClients) {
      const client = roomClients[playerName];

      if (!client || typeof client.publish !== 'function') {
        console.warn(`Skipping ${playerName} - client not ready`);
        continue;
      }

      client.publish({
        destination: `/app/room/${joinCode}/racing-game/tap`,
        body: JSON.stringify({
          "playerName": playerName,
          "tapCount": 1
        })
      });

      console.log(`✅ Tap sent: ${joinCode}/${playerName}`);
    }
  }

  return done();
}

function sendContinuousTaps(context, events, done) {
  const allClients = context.vars.stompClients;

  if (!allClients) {
    console.error('No STOMP clients found');
    return done(new Error('No STOMP clients'));
  }

  const tapIntervals = [];

  for (const joinCode in allClients) {
    const roomClients = allClients[joinCode];

    for (const playerName in roomClients) {
      const client = roomClients[playerName];

      if (!client || typeof client.publish !== 'function') {
        console.warn(`Skipping ${playerName} - client not ready`);
        continue;
      }

      const BASE_INTERVAL = 300;
      const JITTER = 50;
      const initialDelay = Math.floor(Math.random() * BASE_INTERVAL);
      const state = { timerId: null, stopped: false };

      function scheduleTap() {
        if (state.stopped) return;
        const jitteredInterval = BASE_INTERVAL + Math.floor(Math.random() * JITTER * 2) - JITTER;
        state.timerId = setTimeout(() => {
          client.publish({
            destination: `/app/room/${joinCode}/racing-game/tap`,
            body: JSON.stringify({
              "playerName": playerName,
              "tapCount": 30
            })
          });
          scheduleTap();
        }, jitteredInterval);
      }

      state.timerId = setTimeout(() => { scheduleTap(); }, initialDelay);
      tapIntervals.push(state);
    }
  }

  console.log(`✅ Started continuous taps for all players (300ms ± 50ms jitter, staggered start)`);

  if (!context.vars.tapIntervals) {
    context.vars.tapIntervals = [];
  }
  context.vars.tapIntervals.push(...tapIntervals);

  return done();
}

function stopContinuousTaps(context, events, done) {
  if (context.vars.tapIntervals) {
    context.vars.tapIntervals.forEach(state => {
      state.stopped = true;
      clearTimeout(state.timerId);
    });
    console.log(`✅ Stopped all continuous taps`);
    context.vars.tapIntervals = [];
  }

  return done();
}

module.exports = { sendUpdateMiniGames, sendStartMiniGame, sendTap, sendContinuousTaps, stopContinuousTaps };
