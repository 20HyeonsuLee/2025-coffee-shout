-- remove_player.lua
-- KEYS[1] = room:{joinCode}:players
-- KEYS[2] = room:{joinCode}:ready
-- KEYS[3] = room:{joinCode}:positions
-- ARGV[1] = playerName
-- ARGV[2] = envelopeJson
-- Returns: 1 OK / 0 not found
local removed = redis.call('SREM', KEYS[1], ARGV[1])
if removed == 0 then
    return 0
end
redis.call('HDEL', KEYS[2], ARGV[1])
redis.call('HDEL', KEYS[3], ARGV[1])
redis.call('PUBLISH', 'coffeeshout:events', ARGV[2])
return 1
