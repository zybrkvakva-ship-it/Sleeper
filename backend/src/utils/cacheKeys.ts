/**
 * Centralized Redis cache key patterns and TTL constants.
 * Using :v1: namespace — increment to :v2: to invalidate all keys on schema change.
 */

export const CacheKeys = {
  deviceNft:   (wallet: string) => `device_nft:v1:${wallet}`,
  stakingInfo: (wallet: string) => `staking_info:v1:${wallet}`,
};

export const CacheTTL = {
  DEVICE_NFT_S: 3600,     // 1 hour — device ownership is stable
  STAKING_INFO_S: 300,    // 5 minutes — staking balances change with inflation events
};
