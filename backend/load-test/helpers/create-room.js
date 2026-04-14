const axios = require('axios');

async function createRoomAndEnter(context) {
  const host = context.vars.host;
  const menu = {
    id: context.vars.menuId,
    customName: context.vars.menuCustomName,
    temperature: context.vars.menuTemperature
  };

  try {
    const createResponse = await axios.post(`${host}/rooms`, {
      playerName: context.vars.hostName,
      menu: menu
    });

    const joinCode = createResponse.data.joinCode;

    context.vars.joinCode = joinCode;
    context.vars.stompClients = context.vars.stompClients || {};
    context.vars.stompClients[joinCode] = context.vars.stompClients[joinCode] || {};

    console.log(`Room created with joinCode: ${joinCode}`);

    const guests = context.vars.guests || [];
    for (const guestName of guests) {
      await enterGuest(context, joinCode, guestName, host, menu);
    }

    console.log(`Successfully entered ${guests.length + 1} players to room ${joinCode}`);

  } catch (error) {
    console.error('Setup failed:', error.message);
    if (error.response) {
      console.error('Response data:', error.response.data);
      console.error('Response status:', error.response.status);
    }
    throw error;
  }
}

async function enterGuest(context, joinCode, guestName, host, menu) {
  await axios.post(`${host}/rooms/${joinCode}`, {
    playerName: guestName,
    menu: menu
  });
  context.vars.stompClients[joinCode][guestName] = null;
  console.log(`Guest ${guestName} entered room ${joinCode}`);
}

module.exports = { createRoomAndEnter };
