const { createRoomAndEnter } = require('./create-room');
const { connectWebSocket } = require('./connect-websocket');

const hostName = "host";
const defaultGuestCount = 7;
const defaultRoomCount = 30;

async function setup(context) {
  const guestCount = Number(context.vars.guestCount ?? defaultGuestCount);
  const roomCount = Number(context.vars.roomCount ?? defaultRoomCount);
  const guests = Array.from({ length: guestCount }, (_, i) => `guest${i + 1}`);

  context.vars.hostName = hostName;
  context.vars.guests = guests;
  context.vars.joinCodes = [];

  for (let i = 0; i < roomCount; i++) {
    await createRoomAndEnter(context);
    context.vars.joinCodes.push(context.vars.joinCode);
    console.log(`Room ${i + 1}/${roomCount} created: ${context.vars.joinCode}`);
  }

  return new Promise((resolve, reject) => {
    let completedConnections = 0;
    const totalConnections = context.vars.joinCodes.length * (guests.length + 1);

    const checkAllConnected = () => {
      completedConnections++;
      if (completedConnections === totalConnections) {
        console.log(`All players connected (${context.vars.joinCodes.length} rooms, ${totalConnections} connections)`);
        resolve();
      }
    };

    context.vars.joinCodes.forEach(joinCode => {
      connectWebSocket(context, null, checkAllConnected, hostName, joinCode);

      guests.forEach(guestName => {
        connectWebSocket(context, null, checkAllConnected, guestName, joinCode);
      });
    });
  });
}

module.exports = { setup };
