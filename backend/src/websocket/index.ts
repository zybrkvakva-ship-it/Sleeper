import { WebSocketServer, WebSocket } from 'ws';
import { logger } from '../utils/logger';
import { query } from '../database';
import { calcBaseNp, calcStakingBoost, calcFinalNp, calcSkrBoost } from '../economy';
import { BASE_RATE_PER_MINUTE, MAX_TOTAL_MULTIPLIER, SkrBoostLevel } from '../economy/constants';

interface WalletProfile {
  hasGenesisNft: boolean;
  stakedSkrHuman: number;
  activeBoostLevel: SkrBoostLevel;
}

interface Client {
  ws: WebSocket;
  walletAddress?: string;
  nightId?: string;
  lastHeartbeat: number;
  profile?: WalletProfile;
}

const clients = new Map<string, Client>();

export function setupWebSocket(wss: WebSocketServer) {
  logger.info('Setting up WebSocket server');
  
  // Heartbeat interval
  const heartbeatInterval = setInterval(() => {
    const now = Date.now();
    clients.forEach((client, id) => {
      if (now - client.lastHeartbeat > 60000) { // 60s timeout
        logger.warn(`Client ${id} heartbeat timeout`);
        client.ws.close();
        clients.delete(id);
      }
    });
  }, 30000);
  
  wss.on('connection', (ws) => {
    const clientId = generateClientId();
    logger.info(`WebSocket client connected: ${clientId}`);
    
    const client: Client = {
      ws,
      lastHeartbeat: Date.now()
    };
    
    clients.set(clientId, client);
    
    // Send welcome message
    ws.send(JSON.stringify({
      type: 'connected',
      clientId,
      timestamp: Date.now()
    }));
    
    ws.on('message', (data) => {
      try {
        const message = JSON.parse(data.toString());
        handleMessage(clientId, message);
      } catch (error) {
        logger.error('Failed to parse WebSocket message:', error);
      }
    });
    
    ws.on('close', () => {
      logger.info(`WebSocket client disconnected: ${clientId}`);
      clients.delete(clientId);
    });
    
    ws.on('error', (error) => {
      logger.error(`WebSocket error for client ${clientId}:`, error);
    });
  });
  
  wss.on('close', () => {
    clearInterval(heartbeatInterval);
  });
}

function handleMessage(clientId: string, message: any) {
  const client = clients.get(clientId);
  if (!client) return;
  
  client.lastHeartbeat = Date.now();
  
  switch (message.type) {
    case 'ping':
      client.ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
      break;
      
    case 'night:register': {
      client.walletAddress = message.walletAddress;
      client.nightId = message.nightId;
      // Load server-side profile (staking + NFT) — not trusted from client
      if (message.walletAddress) {
        try {
          const rows = await query<{
            has_genesis_nft: boolean;
            staked_skr_raw: string;
            boost_level: string | null;
          }>(
            `SELECT u.has_genesis_nft,
                    COALESCE(u.staked_skr_raw, 0) AS staked_skr_raw,
                    p.boost_level
             FROM users u
             LEFT JOIN payments p ON p.wallet_address = u.wallet_address
               AND p.payment_type = 'SKR_BOOST'
               AND p.verified = true
               AND p.boost_expires_at > NOW()
             WHERE u.wallet_address = $1
             ORDER BY p.created_at DESC
             LIMIT 1`,
            [message.walletAddress]
          );
          if (rows.length > 0) {
            client.profile = {
              hasGenesisNft: rows[0].has_genesis_nft ?? false,
              stakedSkrHuman: Number(rows[0].staked_skr_raw) / 1_000_000,
              activeBoostLevel: (rows[0].boost_level as SkrBoostLevel) || SkrBoostLevel.NONE,
            };
          }
        } catch (err) {
          logger.warn('night:register profile load failed', { err });
        }
      }
      logger.info(`Night registered for client ${clientId}`, {
        wallet: message.walletAddress,
        hasGenesisNft: client.profile?.hasGenesisNft,
        staked: client.profile?.stakedSkrHuman,
      });
      break;
    }
      
    case 'night:update': {
      const {
        wallet,
        sessionId,
        uptimeSeconds,
        humanChecksPassed,
        humanChecksFailed,
        socialBoostPercent,
      } = message as {
        wallet?: string;
        sessionId?: string;
        uptimeSeconds?: number;
        humanChecksPassed?: number;
        humanChecksFailed?: number;
        socialBoostPercent?: number; // 0..0.20
      };

      const targetWallet = wallet ?? client.walletAddress;
      if (!targetWallet || uptimeSeconds == null) {
        logger.debug(`Night update from ${clientId} missing required fields`);
        break;
      }

      // Server-side profile (loaded on night:register, not trusted from client)
      const profile = client.profile ?? { hasGenesisNft: false, stakedSkrHuman: 0, activeBoostLevel: SkrBoostLevel.NONE };

      const passed = humanChecksPassed ?? 0;
      const failed = humanChecksFailed ?? 0;
      const totalChecks = passed + failed;
      const humanFactor = totalChecks > 0 ? 0.5 + (passed / totalChecks) * 0.5 : 1.0;

      // Server-authoritative: staking + NFT
      const stakingBoost = calcStakingBoost(profile.stakedSkrHuman);
      const skrBoostValue = calcSkrBoost(profile.activeBoostLevel);
      const socialBoost = Math.max(0, Math.min(socialBoostPercent ?? 0, 0.20));

      // baseNp per second — 1 minute = BASE_RATE, apply humanFactor
      const baseNpPerSecond = (BASE_RATE_PER_MINUTE / 60) * humanFactor;
      const { finalNp: finalNpPerSecond, totalMultiplier } = calcFinalNp(
        baseNpPerSecond,
        socialBoost,
        skrBoostValue,
        profile.hasGenesisNft,
        stakingBoost
      );

      const pointsEarned = Math.floor(finalNpPerSecond * uptimeSeconds);

      sendToWallet(targetWallet, {
        type: 'night:score',
        sessionId,
        pointsEarned,
        pointsPerSecond: Math.round(finalNpPerSecond * 10000) / 10000,
        effectiveMultiplier: Math.round(totalMultiplier * 100) / 100,
        uptimeSeconds,
      });
      break;
    }
      
    default:
      logger.warn(`Unknown message type: ${message.type}`);
  }
}

function generateClientId(): string {
  return `client_${Date.now()}_${Math.random().toString(36).substring(7)}`;
}

/**
 * Broadcast message to all clients
 */
export function broadcast(message: any) {
  const data = JSON.stringify(message);
  clients.forEach((client) => {
    if (client.ws.readyState === WebSocket.OPEN) {
      client.ws.send(data);
    }
  });
}

/**
 * Send message to specific wallet
 */
export function sendToWallet(walletAddress: string, message: any) {
  const data = JSON.stringify(message);
  clients.forEach((client) => {
    if (client.walletAddress === walletAddress && client.ws.readyState === WebSocket.OPEN) {
      client.ws.send(data);
    }
  });
}
