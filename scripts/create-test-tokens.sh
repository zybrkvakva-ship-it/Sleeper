#!/bin/bash

echo "🪙 Creating Test Tokens on Solana Devnet"
echo "========================================="
echo ""

# Check if Solana CLI is installed
if ! command -v solana &> /dev/null; then
    echo "❌ Solana CLI not found!"
    echo ""
    echo "Install with:"
    echo "  sh -c \"\$(curl -sSfL https://release.solana.com/v1.18.0/install)\""
    exit 1
fi

# Check if spl-token CLI is installed
if ! command -v spl-token &> /dev/null; then
    echo "❌ SPL Token CLI not found!"
    echo ""
    echo "Install with:"
    echo "  cargo install spl-token-cli"
    echo "  or"
    echo "  npm install -g @solana/spl-token"
    exit 1
fi

# Set to devnet
echo "🔧 Configuring Solana to use devnet..."
solana config set --url https://api.devnet.solana.com
echo "✅ Set to devnet"
echo ""

# Check/Create wallet
WALLET_PATH="$HOME/.config/solana/devnet-wallet.json"
if [ ! -f "$WALLET_PATH" ]; then
    echo "💼 Creating devnet wallet..."
    solana-keygen new --outfile "$WALLET_PATH" --no-bip39-passphrase
    echo "✅ Wallet created at: $WALLET_PATH"
else
    echo "✅ Using existing wallet: $WALLET_PATH"
fi

# Get wallet address
WALLET_ADDRESS=$(solana address)
echo "📍 Wallet Address: $WALLET_ADDRESS"
echo ""

# Airdrop SOL if needed
BALANCE=$(solana balance | awk '{print $1}')
if (( $(echo "$BALANCE < 2" | bc -l) )); then
    echo "💰 Requesting SOL airdrop..."
    solana airdrop 2
    echo "✅ Airdrop received"
else
    echo "✅ Sufficient SOL balance: $BALANCE SOL"
fi
echo ""

# Create SLEEP token (SLP)
echo "🌙 Creating SLEEP Token (SLP)..."
SLEEP_MINT=$(spl-token create-token --decimals 9 | grep "Creating token" | awk '{print $3}')
echo "✅ SLEEP Token created!"
echo "   Mint Address: $SLEEP_MINT"
echo ""

# Create SLEEP token account
echo "📦 Creating SLEEP token account..."
spl-token create-account "$SLEEP_MINT"
echo "✅ Token account created"
echo ""

# Mint initial supply (5 million)
echo "⚡ Minting 5,000,000 SLEEP tokens..."
spl-token mint "$SLEEP_MINT" 5000000
SLEEP_BALANCE=$(spl-token balance "$SLEEP_MINT")
echo "✅ Minted! Balance: $SLEEP_BALANCE SLEEP"
echo ""

# Create SKR test token
echo "💎 Creating SKR Token (for payments)..."
SKR_MINT=$(spl-token create-token --decimals 9 | grep "Creating token" | awk '{print $3}')
echo "✅ SKR Token created!"
echo "   Mint Address: $SKR_MINT"
echo ""

# Create SKR token account
echo "📦 Creating SKR token account..."
spl-token create-account "$SKR_MINT"
echo "✅ Token account created"
echo ""

# Mint SKR for testing (1 million)
echo "⚡ Minting 1,000,000 SKR tokens..."
spl-token mint "$SKR_MINT" 1000000
SKR_BALANCE=$(spl-token balance "$SKR_MINT")
echo "✅ Minted! Balance: $SKR_BALANCE SKR"
echo ""

# Create treasury wallet
TREASURY_PATH="$HOME/.config/solana/treasury-wallet.json"
if [ ! -f "$TREASURY_PATH" ]; then
    echo "🏦 Creating treasury wallet..."
    solana-keygen new --outfile "$TREASURY_PATH" --no-bip39-passphrase
    echo "✅ Treasury wallet created"
else
    echo "✅ Using existing treasury wallet"
fi

TREASURY_ADDRESS=$(solana address -k "$TREASURY_PATH")
echo "📍 Treasury Address: $TREASURY_ADDRESS"
echo ""

# Airdrop SOL to treasury
echo "💰 Airdroping SOL to treasury..."
solana transfer "$TREASURY_ADDRESS" 1 --allow-unfunded-recipient
echo "✅ Treasury funded"
echo ""

# Create treasury token accounts
echo "📦 Creating treasury token accounts..."
spl-token create-account "$SLEEP_MINT" --owner "$TREASURY_ADDRESS"
spl-token create-account "$SKR_MINT" --owner "$TREASURY_ADDRESS"
echo "✅ Treasury token accounts created"
echo ""

# Transfer some SLEEP to treasury (for distribution)
echo "🎁 Transferring SLEEP to treasury..."
TREASURY_SLEEP_ACCOUNT=$(spl-token accounts "$SLEEP_MINT" --owner "$TREASURY_ADDRESS" | grep -oP '(?<=Account: )\w+' | head -1)
spl-token transfer "$SLEEP_MINT" 4000000 "$TREASURY_SLEEP_ACCOUNT"
echo "✅ Transferred 4M SLEEP to treasury"
echo ""

# Summary
echo "========================================="
echo "✅ TEST TOKENS CREATED SUCCESSFULLY!"
echo "========================================="
echo ""
echo "📋 SAVE THESE ADDRESSES:"
echo ""
echo "SLEEP Token (SLP):"
echo "  Mint: $SLEEP_MINT"
echo "  Your Balance: $SLEEP_BALANCE SLEEP"
echo ""
echo "SKR Token:"
echo "  Mint: $SKR_MINT"
echo "  Your Balance: $SKR_BALANCE SKR"
echo ""
echo "Wallets:"
echo "  Your Wallet: $WALLET_ADDRESS"
echo "  Treasury:    $TREASURY_ADDRESS"
echo ""
echo "========================================="
echo ""
echo "📝 Next Steps:"
echo "1. Update backend/.env with these addresses:"
echo "   SLEEP_TOKEN_MINT=$SLEEP_MINT"
echo "   SKR_TOKEN_MINT=$SKR_MINT"
echo "   TREASURY_WALLET=$TREASURY_ADDRESS"
echo ""
echo "2. Update Android app with token addresses"
echo ""
echo "3. Start backend: cd backend && npm run dev"
echo ""
echo "🎉 Ready to test with REAL tokens!"
echo ""

# Save to file
CONFIG_FILE="$(dirname "$0")/token-config.env"
cat > "$CONFIG_FILE" << EOF
# Auto-generated token configuration
# Date: $(date)

# Network
SOLANA_CLUSTER=devnet
SOLANA_RPC_URL=https://api.devnet.solana.com

# Tokens
SLEEP_TOKEN_MINT=$SLEEP_MINT
SKR_TOKEN_MINT=$SKR_MINT

# Wallets
TREASURY_WALLET=$TREASURY_ADDRESS
TEST_WALLET=$WALLET_ADDRESS

# For Android
# Copy these to TokenConfig.kt
EOF

echo "💾 Token config saved to: $CONFIG_FILE"
echo ""
