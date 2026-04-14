function sendReadyTrue(context, events, done) {
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
        destination: `/app/room/${joinCode}/update-ready`,
        body: JSON.stringify({
          isReady: true,
          joinCode: joinCode,
          playerName: playerName
        })
      });

      console.log(`✅ Ready sent: ${joinCode}/${playerName}`);
    }
  }

  return done();
}

module.exports = { sendReadyTrue };