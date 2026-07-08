module.exports = {
  randomGuest
};

function randomGuest(context, events, done) {
  const guestIndex = Math.floor(Math.random() * 8);
  context.vars.guestName = `guest-${guestIndex}`;
  return done();
}
