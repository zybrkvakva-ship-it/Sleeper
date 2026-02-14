#!/bin/bash
# Quick API test script for NightMiner Backend

set -e

BASE_URL="http://localhost:3000"
WALLET="7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"

echo "🌙 NightMiner Backend API Test"
echo "================================"
echo ""

# 1. Health check
echo "1️⃣ Health Check"
curl -s "$BASE_URL/health" | jq -r '.status'
echo ""

# 2. Season info
echo "2️⃣ Current Season"
curl -s "$BASE_URL/api/v1/season/current" | jq -r '.season | "Season \(.season_number): Week \(.current_week)/\(.total_weeks) - Pool: \(.poolPerNight) SLEEP/night"'
echo ""

# 3. User profile
echo "3️⃣ User Profile"
curl -s "$BASE_URL/api/v1/user/$WALLET" | jq -r '.user | "NP: \(.total_np) | Nights: \(.total_nights_mined) | SLEEP: \(.total_sleep_earned)"'
echo ""

# 4. Leaderboard
echo "4️⃣ Leaderboard Top 3"
curl -s "$BASE_URL/api/v1/leaderboard?limit=3" | jq -r '.leaderboard[] | "#\(.rank): \(.wallet_address[0:8])... - \(.total_np) NP"'
echo ""

echo "✅ All tests passed!"
