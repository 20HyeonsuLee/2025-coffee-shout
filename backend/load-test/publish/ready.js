function publishReadyForAll(context, isReady) {
  const allClients = context.vars.stompClients;

  if (!allClients) {
    throw new Error('No STOMP clients');
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
        destination: `/app/room/${joinCode}/update-ready`,
        body: JSON.stringify({
          isReady: isReady,
          joinCode: joinCode,
          playerName: playerName
        })
      });

      console.log(`Ready sent: ${joinCode}/${playerName} -> ${isReady}`);
    }
  }
}

function sendReadyTrue(context, events, done) {
  try {
    publishReadyForAll(context, true);
    return done();
  } catch (error) {
    console.error(error.message);
    return done(error);
  }
}

function sendReadyStorm(context, events, done) {
  const rounds = Number(context.vars.readyStormRounds ?? 20);
  const intervalMs = Number(context.vars.readyStormIntervalMs ?? 20);
  let currentRound = 0;

  const publishRound = () => {
    try {
      publishReadyForAll(context, currentRound % 2 === 0);
      currentRound++;
      if (currentRound >= rounds) {
        return done();
      }
      setTimeout(publishRound, intervalMs);
    } catch (error) {
      console.error(error.message);
      return done(error);
    }
  };

  publishRound();
}

module.exports = { sendReadyTrue, sendReadyStorm };
