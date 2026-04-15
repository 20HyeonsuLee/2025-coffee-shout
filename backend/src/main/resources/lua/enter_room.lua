-- enter_room.lua
-- KEYS[1] = room:{joinCode}:meta
-- KEYS[2] = room:{joinCode}:players
-- ARGV[1] = playerName
-- ARGV[2] = maxPlayers (string integer)
-- ARGV[3] = envelopeJson
-- Returns: 1 OK / -1 full / -2 duplicate name / -3 room not found
local exists = redis.call('EXISTS', KEYS[1])
if exists == 0 then
    return -3
end
local count = redis.call('SCARD', KEYS[2])
if count >= tonumber(ARGV[2]) then
    return -1
end
local already = redis.call('SISMEMBER', KEYS[2], ARGV[1])
if already == 1 then
    return -2
end
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('PUBLISH', 'coffeeshout:events', ARGV[3])
return 1
