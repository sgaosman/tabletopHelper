-- M24.5: Generic Resource Pool System
-- Three new reference tables + two new JSONB columns + three action economy boolean columns

-- Reference table: resource pool definitions (what pools exist)
CREATE TABLE resource_pool_definitions (
    pool_id VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    max_uses_formula VARCHAR(100),
    reset_on VARCHAR(20) NOT NULL,
    reset_amount VARCHAR(100),
    reset_check VARCHAR(20),
    spend_action_type VARCHAR(30),
    description TEXT,
    icon VARCHAR(30),
    metadata JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Junction table: which class/subclass gets which pool at which level
CREATE TABLE class_feature_pools (
    id UUID PRIMARY KEY,
    class_id UUID NOT NULL REFERENCES character_classes(id),
    subclass_id UUID REFERENCES subclasses(id),
    min_level INTEGER NOT NULL,
    pool_id VARCHAR(64) NOT NULL REFERENCES resource_pool_definitions(pool_id),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Trigger table: conditions that auto-create pools for monsters on encounter join
CREATE TABLE monster_pool_triggers (
    id UUID PRIMARY KEY,
    pool_id VARCHAR(64) NOT NULL REFERENCES resource_pool_definitions(pool_id),
    trigger_condition JSONB NOT NULL,
    priority INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- New JSONB column on player_characters for resource pools (subsumes feat_resources and hit_dice_map)
ALTER TABLE player_characters ADD COLUMN IF NOT EXISTS resource_pools JSONB;

-- New JSONB column on encounter_participants for in-combat resource tracking
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS resource_pools_current JSONB;

-- Action economy tracking columns on encounter_participants
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS action_used BOOLEAN DEFAULT FALSE;
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS bonus_action_used BOOLEAN DEFAULT FALSE;
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS reaction_used BOOLEAN DEFAULT FALSE;
