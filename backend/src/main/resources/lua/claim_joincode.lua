-- claim_joincode.lua
-- KEYS[1] = joincode:{joinCode}
-- ARGV[1] = ttlSeconds
-- Returns: 1 (acquired) / 0 (duplicate)
local ok = redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1])
if ok then
    return 1
end
return 0
