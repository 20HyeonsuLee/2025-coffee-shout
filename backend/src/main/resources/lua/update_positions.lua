-- update_positions.lua
-- 100ms tick 마다 RacingGame positions snapshot을 Hash에 반영 + TTL 연장 + PUBLISH.
-- KEYS[1] = room:{joinCode}:positions
-- ARGV[1] = ttlSeconds
-- ARGV[2] = envelopeJson
-- ARGV[3..]  playerName 과 position 이 번갈아 들어온 flat list (name1, pos1, name2, pos2, ...)
-- Returns: 1 OK / -1 잘못된 ARGV 쌍
local argc = #ARGV
if (argc - 2) % 2 ~= 0 then
    return -1
end
for i = 3, argc, 2 do
    redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
end
redis.call('EXPIRE', KEYS[1], ARGV[1])
redis.call('PUBLISH', 'coffeeshout:events', ARGV[2])
return 1
