-- Shigu's Dream — схема PostgreSQL (инициализируется автоматически docker-entrypoint-initdb.d)

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Аккаунты backend. Первый созданный аккаунт получает роль admin (bootstrap).
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username TEXT UNIQUE NOT NULL,
    role TEXT NOT NULL DEFAULT 'user',
    mc_uuid UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Точечные права для обычных пользователей: 'client.admin', 'client.action.<name>'.
CREATE TABLE IF NOT EXISTS user_permissions (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission TEXT NOT NULL,
    PRIMARY KEY (user_id, permission)
);

-- Одноразовые коды привязки Minecraft UUID к аккаунту.
CREATE TABLE IF NOT EXISTS link_codes (
    code TEXT PRIMARY KEY,
    mc_uuid UUID NOT NULL,
    mc_name TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending', -- pending | confirmed | expired
    user_id UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
ALTER TABLE link_codes ADD COLUMN IF NOT EXISTS is_force BOOLEAN NOT NULL DEFAULT FALSE;

-- Команды (по ТЗ + request_id для дедупликации и mode для immediate/queued).
CREATE TABLE IF NOT EXISTS commands (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id TEXT UNIQUE NOT NULL,
    sender_id UUID NOT NULL REFERENCES users(id),
    target_id UUID NOT NULL REFERENCES users(id),
    action_id TEXT NOT NULL,
    payload JSONB NOT NULL,
    mode TEXT NOT NULL DEFAULT 'immediate',   -- immediate | queued
    status TEXT NOT NULL DEFAULT 'pending',   -- pending | delivered | executed | failed | expired | cancelled
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    executed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_commands_target_status ON commands (target_id, status);
CREATE INDEX IF NOT EXISTS idx_commands_sender ON commands (sender_id);
