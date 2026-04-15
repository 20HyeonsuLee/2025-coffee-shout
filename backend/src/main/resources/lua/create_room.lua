-- create_room.lua
-- 전제: joinCode 유일성은 호출 전에 JoinCodeRepository(claim_joincode.lua)가 이미 선점함.
-- 이 스크립트는 meta/players 작성 + TTL + PUBLISH만 원자적으로 수행.
-- room:{joinCode}:meta 가 이미 존재하면 -1 반환 (중복 생성 방어).
-- KEYS[1] = room:{joinCode}:meta
-- KEYS[2] = room:{joinCode}:players
-- KEYS[3] = joincode:{joinCode}  (TTL 동기화용)
-- ARGV[1] = hostName
-- ARGV[2] = state
-- ARGV[3] = gameType
-- ARGV[4] = createdAt (epoch millis string)
-- ARGV[5] = maxPlayers
-- ARGV[6] = ttlSeconds
-- ARGV[7] = envelopeJson
-- Returns: 1 (created) / -1 (already exists)
if redis.call('EXISTS', KEYS[1]) == 1 then
    return -1
end
redis.call('HSET', KEYS[1],
    'hostName', ARGV[1],
    'state', ARGV[2],
    'gameType', ARGV[3],
    'createdAt', ARGV[4],
    'maxPlayers', ARGV[5]
)
redis.call('EXPIRE', KEYS[1], ARGV[6])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('EXPIRE', KEYS[2], ARGV[6])
redis.call('EXPIRE', KEYS[3], ARGV[6])
redis.call('PUBLISH', 'coffeeshout:events', ARGV[7])
return 1
