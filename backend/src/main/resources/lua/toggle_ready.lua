-- toggle_ready.lua
-- KEYS[1] = room:{joinCode}:players
-- KEYS[2] = room:{joinCode}:ready
-- ARGV[1] = playerName
-- ARGV[2] = newValue ("true" or "false")
-- ARGV[3] = envelopeJson
-- Returns: 1 OK / -1 player not found
local member = redis.call('SISMEMBER', KEYS[1], ARGV[1])
if member == 0 then
    return -1
end
redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
redis.call('PUBLISH', 'coffeeshout:events', ARGV[3])
return 1
