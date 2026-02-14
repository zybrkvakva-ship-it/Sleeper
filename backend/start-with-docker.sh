#!/bin/bash

echo "🌙 Starting NightMiner Backend with Docker PostgreSQL"
echo "====================================================="
echo ""

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    echo "❌ Docker not found!"
    echo ""
    echo "Please install Docker Desktop:"
    echo "  https://www.docker.com/products/docker-desktop"
    echo ""
    exit 1
fi

# Check if Docker is running
if ! docker info &> /dev/null; then
    echo "❌ Docker is not running!"
    echo "Please start Docker Desktop and try again."
    exit 1
fi

echo "✅ Docker is running"
echo ""

# Check if docker-compose is available
if command -v docker-compose &> /dev/null; then
    COMPOSE_CMD="docker-compose"
elif docker compose version &> /dev/null; then
    COMPOSE_CMD="docker compose"
else
    echo "❌ docker-compose not found!"
    exit 1
fi

# Stop any existing containers
echo "🔄 Stopping existing containers..."
$COMPOSE_CMD down 2>/dev/null

# Start PostgreSQL
echo "🚀 Starting PostgreSQL in Docker..."
$COMPOSE_CMD up -d

if [ $? -ne 0 ]; then
    echo "❌ Failed to start PostgreSQL"
    exit 1
fi

echo "✅ PostgreSQL started"
echo ""

# Wait for PostgreSQL to be ready
echo "⏳ Waiting for PostgreSQL to be ready..."
for i in {1..30}; do
    if docker exec nightminer-db pg_isready -U nightminer &> /dev/null; then
        echo "✅ PostgreSQL is ready!"
        break
    fi
    echo -n "."
    sleep 1
done
echo ""

# Install dependencies if needed
if [ ! -d "node_modules" ]; then
    echo "📦 Installing dependencies..."
    npm install
    echo "✅ Dependencies installed"
fi

# Run migrations
echo ""
echo "🔄 Running database migrations..."
npm run db:migrate

if [ $? -ne 0 ]; then
    echo "❌ Migration failed"
    echo ""
    echo "Try running manually:"
    echo "  npm run db:migrate"
    exit 1
fi

echo ""
echo "✅ Database ready!"
echo ""
echo "🚀 Starting backend server..."
echo ""
echo "Backend will be available at:"
echo "  📡 HTTP:      http://localhost:3000"
echo "  🔌 WebSocket: ws://localhost:3001"
echo ""
echo "PostgreSQL running in Docker:"
echo "  🐘 Port: 5432"
echo "  👤 User: nightminer"
echo "  🔑 Pass: nightminer123"
echo "  💾 DB:   nightminer"
echo ""
echo "Press Ctrl+C to stop"
echo ""

# Start server
npm run dev
