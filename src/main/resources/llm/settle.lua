local function integer(n) return type(n)=='number' and n==math.floor(n) and math.abs(n)<=9007199254740991 end
local old = redis.call('GET', KEYS[1])
if not old then return redis.error_reply('RECONCILE missing reservation') end
local receipt = cjson.decode(old)
if receipt.reservationFingerprint ~= ARGV[1] then return redis.error_reply('RECONCILE reservation fingerprint') end
if receipt.phase == 'APPLIED' then
  if receipt.fingerprint ~= ARGV[2] or receipt.version ~= tonumber(ARGV[3]) or receipt.amount ~= tonumber(ARGV[4]) then
    return redis.error_reply('RECONCILE settlement fingerprint')
  end
  return old
end
if receipt.phase ~= 'RESERVED' then return redis.error_reply('RECONCILE reservation state') end
local amount = tonumber(ARGV[4])
if not integer(amount) or amount < 0 or not integer(tonumber(ARGV[3])) then return redis.error_reply('RECONCILE amount') end
local deltas = {}
for _, c in ipairs(receipt.components) do
  if (c.mode~='DIRECT_DEBIT' and c.mode~='RESERVE_ADJUST') or not integer(c.reserved) or c.reserved<0 then return redis.error_reply('RECONCILE component') end
  local kind = redis.call('TYPE', c.key).ok
  if kind ~= 'none' and kind ~= 'string' then return redis.error_reply('RECONCILE counter type') end
  local raw = redis.call('GET', c.key)
  if raw and (not string.match(raw,'^%-?%d+$') or not integer(tonumber(raw))) then return redis.error_reply('RECONCILE counter value') end
  local delta = 0
  local expired = false
  if c.mode == 'DIRECT_DEBIT' then
    if not raw then return redis.error_reply('RECONCILE missing balance') end
    delta = -amount
  else
    local epochType = redis.call('TYPE', c.epochKey).ok
    if epochType ~= 'none' and epochType ~= 'string' then return redis.error_reply('RECONCILE epoch type') end
    if raw and redis.call('GET', c.epochKey) == c.window then delta = amount - c.reserved
    else expired = true end
  end
  if not integer(tonumber(raw or '0')+delta) then return redis.error_reply('RECONCILE counter range') end
  table.insert(deltas, {key=c.key, mode=c.mode, window=c.window, delta=delta, expired=expired, before=tonumber(raw or '0')})
end
local countKey = ARGV[5]
if countKey ~= '' then
  local kind = redis.call('TYPE', countKey).ok
  if kind ~= 'none' and kind ~= 'string' then return redis.error_reply('RECONCILE metric type') end
  local raw = redis.call('GET', countKey)
  if raw and (not string.match(raw,'^%-?%d+$') or not integer(tonumber(raw)) or not integer(tonumber(raw)+1)) then return redis.error_reply('RECONCILE metric value') end
end
for _, d in ipairs(deltas) do
  if d.delta ~= 0 then redis.call('INCRBY', d.key, d.delta) end
  d.after = d.before + d.delta
end
if countKey ~= '' and ARGV[6] == 'true' then redis.call('INCRBY', countKey, 1) end
receipt.phase='APPLIED'
receipt.fingerprint=ARGV[2]
receipt.version=tonumber(ARGV[3])
receipt.amount=amount
receipt.deltas=deltas
receipt.requestCountApplied=ARGV[6] == 'true'
local encoded=cjson.encode(receipt)
redis.call('SET', KEYS[1], encoded)
return encoded
