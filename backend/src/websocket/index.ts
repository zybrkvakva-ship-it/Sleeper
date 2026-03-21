import { WebSocketServer, WebSocket } from 'ws';
import { logger } from '../utils/logger';
import { calcStorageMultiplier } from '../economy';
import { BASE_RATE_PER_MINUTE, MAX_TOTAL_MULTIPLIER } from '../economy/constants';

interface Client {
  ws: WebSocket;
  walletAddress?: string;
  nightId?: string;
  lastHeartbeat: number;
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
      
    case 'night:register':
      client.walletAddress = message.walletAddress;
      client.nightId = message.nightId;
      logger.info(`Night registered for client ${clientId}`, {
        wallet: message.walletAddress
      });
      break;
      
    case 'night:update': {
      const {
        wallet,
        sessionId,
        uptimeSeconds,
        storageMb,
        humanChecksPassed,
        humanChecksFailed,
        paidBoostMultiplier,
        hasGenesisNft,
      } = message as {
        wallet?: string;
        sessionId?: string;
        uptimeSeconds?: number;
        storageMb?: number;
        humanChecksPassed?: number;
        humanChecksFailed?: number;
        paidBoostMultiplier?: number;
        hasGenesisNft?: boolean;
      };

      const targetWallet = wallet ?? client.walletAddress;
      if (!targetWallet || uptimeSeconds == null) {
        logger.debug(`Night update from ${clientId} missing required fields`);
        break;
      }

      const storageMult = calcStorageMultiplier(storageMb ?? 0);
      const passed = humanChecksPassed ?? 0;
      const failed = humanChecksFailed ?? 0;
      const totalChecks = passed + failed;
      const humanFactor = totalChecks > 0 ? 0.5 + (passed / totalChecks) * 0.5 : 1.0;
      const nftMult = hasGenesisNft ? 3.0 : 1.0;
      const paidMult = paidBoostMultiplier ?? 1.0;

      const rawMultiplier = storageMult * humanFactor * paidMult * nftMult;
      const effectiveMultiplier = Math.min(rawMultiplier, MAX_TOTAL_MULTIPLIER);
      const npPerSecond = (BASE_RATE_PER_MINUTE / 60) * effectiveMultiplier;
      const pointsEarned = Math.floor(npPerSecond * uptimeSeconds);

      sendToWallet(targetWallet, {
        type: 'night:score',
        sessionId,
        pointsEarned,
        pointsPerSecond: Math.round(npPerSecond * 10000) / 10000,
        effectiveMultiplier: Math.round(effectiveMultiplier * 100) / 100,
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
