-- All components are validated before any counter is changed. Receipts have no TTL.
local function integer(n) return type(n)=='number' and n==math.floor(n) and math.abs(n)<=9007199254740991 end
local old = redis.call('GET', KEYS[1])
if old then
  local r = cjson.decode(old)
  if r.reservationFingerprint ~= ARGV[1] then return redis.error_reply('RECONCILE fingerprint') end
  return old
end
local components = cjson.decode(ARGV[2])
local rejected = false
for _, c in ipairs(components) do
  if (c.mode~='DIRECT_DEBIT' and c.mode~='RESERVE_ADJUST') or not integer(c.reserved) or c.reserved<0 then return redis.error_reply('RECONCILE component') end
  local kind = redis.call('TYPE', c.key).ok
  if kind ~= 'none' and kind ~= 'string' then return redis.error_reply('RECONCILE counter type') end
  local raw = redis.call('GET', c.key)
  if raw and (not string.match(raw,'^%-?%d+$') or not integer(tonumber(raw))) then return redis.error_reply('RECONCILE counter value') end
  local current = tonumber(raw or '0')
  if c.mode == 'DIRECT_DEBIT' then
    if not raw then return redis.error_reply('RECONCILE missing balance') end
    if current < c.reserved then rejected = true end
  else
    if not integer(c.limit) or not integer(c.ttlMillis) or c.ttlMillis<=0 or not integer(current+c.reserved) then return redis.error_reply('RECONCILE component range') end
    local epochType = redis.call('TYPE', c.epochKey).ok
    if epochType ~= 'none' and epochType ~= 'string' then return redis.error_reply('RECONCILE epoch type') end
    if raw then
      c.window = redis.call('GET', c.epochKey) or c.window
      c.ttl = redis.call('PTTL', c.key)
    else c.ttl = c.ttlMillis end
    if current + c.reserved > c.limit then rejected = true end
  end
end
local result = {phase = rejected and 'REJECTED' or 'RESERVED', reservationFingerprint = ARGV[1], components = components}
if not rejected then
  for _, c in ipairs(components) do
    if c.mode == 'RESERVE_ADJUST' then
      redis.call('INCRBY', c.key, c.reserved)
      if c.ttl > 0 then
        redis.call('PEXPIRE', c.key, c.ttl)
        redis.call('SET', c.epochKey, c.window, 'PX', c.ttl)
      else redis.call('SET', c.epochKey, c.window) end
    end
  end
end
local encoded = cjson.encode(result)
redis.call('SET', KEYS[1], encoded)
return encoded
