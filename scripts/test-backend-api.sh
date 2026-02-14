#!/bin/bash

API_URL="http://localhost:3000/api/v1"

echo "🧪 Testing NightMiner Backend API"
echo "=================================="
echo ""
echo "Using API: $API_URL"
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test function
test_endpoint() {
    local name=$1
    local method=$2
    local endpoint=$3
    local data=$4
    
    echo -n "Testing $name... "
    
    if [ "$method" = "GET" ]; then
        response=$(curl -s -w "\n%{http_code}" "$API_URL$endpoint")
    else
        response=$(curl -s -w "\n%{http_code}" -X "$method" "$API_URL$endpoint" \
            -H "Content-Type: application/json" \
            -d "$data")
    fi
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    
    if [ "$http_code" -ge 200 ] && [ "$http_code" -lt 300 ]; then
        echo -e "${GREEN}✅ PASS${NC} (HTTP $http_code)"
        echo "   Response: $(echo $body | jq -c . 2>/dev/null || echo $body)"
    else
        echo -e "${RED}❌ FAIL${NC} (HTTP $http_code)"
        echo "   Error: $body"
    fi
    echo ""
}

# Wait for user to press enter
read -p "Make sure backend is running (npm run dev). Press Enter to continue..."
echo ""

# Health Check
test_endpoint "Health Check" "GET" "/../../health"

# Register User
TEST_WALLET="AyScSXbjnjmuTZGk8u5FyYVWC6Uqz4B3dMSVKvjPyTUY"
test_endpoint "Register User" "POST" "/user/register" \
    "{\"walletAddress\":\"$TEST_WALLET\",\"skrUsername\":\"test.skr\"}"

# Get User Profile
test_endpoint "Get User Profile" "GET" "/user/$TEST_WALLET"

# Get Current Season
test_endpoint "Get Current Season" "GET" "/season/current"

# Check NFT Eligibility
test_endpoint "Check NFT Eligibility" "POST" "/nft/check-eligibility" \
    "{\"walletAddress\":\"$TEST_WALLET\",\"skrUsername\":\"test.skr\"}"

# Get NFT Supply
test_endpoint "Get NFT Supply" "GET" "/nft/supply"

# End Night Session
test_endpoint "End Night Session" "POST" "/night/end" \
    "{\"walletAddress\":\"$TEST_WALLET\",\"minutesSlept\":480,\"storageMb\":100,\"movementViolations\":0,\"screenOnCount\":0}"

# Get Night History
test_endpoint "Get Night History" "GET" "/night/history/$TEST_WALLET"

# Get Leaderboard
test_endpoint "Get Leaderboard" "GET" "/leaderboard?limit=10"

# Get User Rank
test_endpoint "Get User Rank" "GET" "/leaderboard/rank/$TEST_WALLET"

echo "=================================="
echo "✅ API Testing Complete!"
echo ""
echo "Check backend logs for detailed info"
echo ""
