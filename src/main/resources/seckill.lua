local voucherId = ARGV[1]
local userId = ARGV[2]


local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end

if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 仅扣库存和记录用户，不写 Stream
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)

return 0