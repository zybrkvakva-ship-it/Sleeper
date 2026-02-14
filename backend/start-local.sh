#!/bin/bash

echo "🌙 Starting NightMiner Backend (Local Testing)"
echo "=============================================="
echo ""

# Check if Node.js is installed
if ! command -v node &> /dev/null; then
    echo "❌ Node.js not found. Please install Node.js 18+"
    exit 1
fi

# Check if PostgreSQL is running
if ! command -v psql &> /dev/null; then
    echo "⚠️  PostgreSQL CLI not found. Make sure PostgreSQL is installed."
fi

# Install dependencies if needed
if [ ! -d "node_modules" ]; then
    echo "📦 Installing dependencies..."
    npm install
    echo "✅ Dependencies installed"
    echo ""
fi

# Check if .env exists
if [ ! -f ".env" ]; then
    echo "⚠️  No .env file found. Creating from .env.example..."
    cp .env.example .env
    echo "✅ Created .env file"
    echo "⚠️  Please edit .env with your database credentials!"
    echo ""
fi

# Check if database exists
echo "🔍 Checking database connection..."
if psql -lqt | cut -d \| -f 1 | grep -qw nightminer; then
    echo "✅ Database 'nightminer' exists"
else
    echo "⚠️  Database 'nightminer' not found"
    echo "Creating database..."
    createdb nightminer 2>/dev/null
    if [ $? -eq 0 ]; then
        echo "✅ Database created"
    else
        echo "❌ Failed to create database. Please create manually:"
        echo "   createdb nightminer"
        exit 1
    fi
fi

# Run migrations
echo ""
echo "🔄 Running database migrations..."
npm run db:migrate

if [ $? -ne 0 ]; then
    echo "❌ Migration failed. Check your DATABASE_URL in .env"
    exit 1
fi

echo ""
echo "✅ Database ready!"
echo ""
echo "🚀 Starting development server..."
echo ""
echo "Backend will be available at:"
echo "  📡 HTTP:      http://localhost:3000"
echo "  🔌 WebSocket: ws://localhost:3001"
echo ""
echo "Press Ctrl+C to stop"
echo ""

# Start server
npm run dev
