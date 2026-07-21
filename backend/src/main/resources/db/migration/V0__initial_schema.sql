-- Initial schema: creates all base tables for the tabletop helper application.
-- Subsequent migrations (V1-V6) add indexes, columns, and constraint updates.

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;

CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100),
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE campaigns (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    invite_code VARCHAR(8) NOT NULL UNIQUE,
    is_active BOOLEAN,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    dm_user_id UUID NOT NULL REFERENCES users(id)
);

CREATE TABLE campaign_members (
    id UUID PRIMARY KEY,
    role VARCHAR(255) NOT NULL,
    joined_at TIMESTAMPTZ,
    campaign_id UUID NOT NULL REFERENCES campaigns(id),
    user_id UUID NOT NULL REFERENCES users(id),
    UNIQUE(campaign_id, user_id),
    CONSTRAINT campaign_members_role_check CHECK (role IN ('DM', 'PLAYER'))
);

CREATE TABLE races (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    base_race_name VARCHAR(200),
    creature_type VARCHAR(50),
    size VARCHAR(20),
    speed JSONB,
    darkvision INTEGER,
    traits JSONB,
    ability_score_bonuses JSONB,
    proficiencies JSONB,
    resistances JSONB,
    source VARCHAR(100),
    created_at TIMESTAMPTZ,
    race_choices JSONB,
    additional_spells JSONB
);

CREATE TABLE character_classes (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    hit_dice INTEGER,
    primary_ability VARCHAR(50),
    saving_throw_proficiencies JSONB,
    armor_proficiencies JSONB,
    weapon_proficiencies JSONB,
    tool_proficiencies JSONB,
    skill_choices JSONB,
    starting_equipment JSONB,
    features JSONB,
    is_spellcaster BOOLEAN,
    is_known_caster BOOLEAN,
    is_prepared_caster BOOLEAN,
    is_pact_magic BOOLEAN,
    spellcasting_ability VARCHAR(20),
    spell_slot_progression JSONB,
    subclass_level INTEGER,
    source VARCHAR(100),
    created_at TIMESTAMPTZ,
    multiclass_proficiencies JSONB,
    multiclass_requirements JSONB
);

CREATE TABLE subclasses (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    features JSONB,
    additional_proficiencies JSONB,
    always_prepared_spells JSONB,
    expanded_spell_list JSONB,
    source VARCHAR(100),
    created_at TIMESTAMPTZ,
    character_class_id UUID NOT NULL REFERENCES character_classes(id)
);

CREATE TABLE backgrounds (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    skill_proficiencies JSONB,
    tool_proficiencies JSONB,
    language_proficiencies JSONB,
    starting_equipment JSONB,
    feature JSONB,
    source VARCHAR(100),
    created_at TIMESTAMPTZ,
    additional_spells JSONB,
    feats JSONB
);

CREATE TABLE player_characters (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    character_class VARCHAR(100),
    subclass VARCHAR(100),
    race VARCHAR(100),
    background VARCHAR(100),
    level INTEGER,
    experience_points INTEGER,
    alignment VARCHAR(50),
    strength INTEGER NOT NULL,
    dexterity INTEGER NOT NULL,
    constitution INTEGER NOT NULL,
    intelligence INTEGER NOT NULL,
    wisdom INTEGER NOT NULL,
    charisma INTEGER NOT NULL,
    hp_max INTEGER NOT NULL,
    hp_current INTEGER NOT NULL,
    hp_temp INTEGER,
    armour_class INTEGER NOT NULL,
    speed INTEGER,
    initiative_bonus INTEGER,
    proficiency_bonus INTEGER,
    saving_throw_proficiencies JSONB,
    skill_proficiencies JSONB,
    skill_expertises JSONB,
    armor_proficiencies JSONB,
    weapon_proficiencies JSONB,
    tool_proficiencies JSONB,
    language_proficiencies JSONB,
    features JSONB,
    equipment JSONB,
    equipped_items JSONB,
    attuned_items JSONB,
    currency JSONB,
    spell_slots JSONB,
    spells_known JSONB,
    prepared_spells JSONB,
    spellcasting_ability VARCHAR(20),
    spell_save_dc INTEGER,
    spell_attack_bonus INTEGER,
    hit_dice_total VARCHAR(20),
    hit_dice_remaining VARCHAR(20),
    hit_dice_map JSONB,
    death_save_successes INTEGER,
    death_save_failures INTEGER,
    personality_traits TEXT,
    ideals TEXT,
    bonds TEXT,
    flaws TEXT,
    notes TEXT,
    portrait_url VARCHAR(500),
    is_active BOOLEAN,
    ability_score_method VARCHAR(20),
    multiclass_entries JSONB,
    racial_ability_bonuses JSONB,
    level_history JSONB,
    feat_resources JSONB,
    damage_resistances JSONB,
    damage_immunities JSONB,
    condition_immunities JSONB,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    user_id UUID NOT NULL REFERENCES users(id),
    campaign_id UUID REFERENCES campaigns(id),
    race_id UUID REFERENCES races(id),
    class_id UUID REFERENCES character_classes(id),
    subclass_id UUID REFERENCES subclasses(id),
    background_id UUID REFERENCES backgrounds(id)
);

CREATE TABLE spells (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    level INTEGER NOT NULL,
    school VARCHAR(50),
    casting_time VARCHAR(500),
    range_distance VARCHAR(100),
    components JSONB,
    duration VARCHAR(100),
    concentration BOOLEAN,
    ritual BOOLEAN,
    description TEXT,
    higher_levels TEXT,
    damage_dice VARCHAR(50),
    damage_type VARCHAR(50),
    save_ability VARCHAR(20),
    classes JSONB,
    source VARCHAR(100),
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE monsters (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    size VARCHAR(20),
    type VARCHAR(100),
    subtype VARCHAR(100),
    alignment VARCHAR(50),
    armour_class INTEGER,
    ac_type VARCHAR(200),
    hit_points INTEGER,
    hit_dice VARCHAR(50),
    speed JSONB,
    strength INTEGER,
    dexterity INTEGER,
    constitution INTEGER,
    intelligence INTEGER,
    wisdom INTEGER,
    charisma INTEGER,
    saving_throws JSONB,
    skills JSONB,
    damage_resistances JSONB,
    damage_immunities JSONB,
    damage_vulnerabilities JSONB,
    condition_immunities JSONB,
    senses JSONB,
    languages VARCHAR(500),
    challenge_rating VARCHAR(10),
    experience_points INTEGER,
    traits JSONB,
    actions JSONB,
    reactions JSONB,
    legendary_actions JSONB,
    lair_actions JSONB,
    source VARCHAR(100),
    is_homebrew BOOLEAN,
    created_by_user_id UUID,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE conditions (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT NOT NULL,
    effects JSONB,
    source VARCHAR(100),
    created_at TIMESTAMPTZ
);

CREATE TABLE items (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    type VARCHAR(50),
    subtype VARCHAR(50),
    rarity VARCHAR(50),
    description TEXT,
    properties JSONB,
    damage_dice VARCHAR(20),
    damage_type VARCHAR(50),
    weight DOUBLE PRECISION,
    cost VARCHAR(50),
    requires_attunement BOOLEAN,
    attunement_condition VARCHAR(200),
    source VARCHAR(100),
    is_homebrew BOOLEAN,
    created_by_user_id UUID,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE feats (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    prerequisite JSONB,
    ability_score_increase JSONB,
    grants_features JSONB,
    effects JSONB,
    source VARCHAR(100),
    created_at TIMESTAMPTZ
);

CREATE TABLE optional_features (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    feature_type VARCHAR(50) NOT NULL,
    description TEXT,
    prerequisite JSONB,
    source VARCHAR(100),
    created_at TIMESTAMPTZ
);

CREATE TABLE encounters (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    status VARCHAR(255) NOT NULL,
    round_number INTEGER,
    current_turn_index INTEGER,
    session_code VARCHAR(8) UNIQUE,
    version BIGINT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    campaign_id UUID NOT NULL REFERENCES campaigns(id),
    CONSTRAINT encounters_status_check CHECK (status IN ('PREPARING', 'ACTIVE', 'PAUSED', 'COMPLETED'))
);

CREATE TABLE encounter_participants (
    id UUID PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    participant_type VARCHAR(255) NOT NULL,
    hp_max INTEGER NOT NULL,
    hp_current INTEGER NOT NULL,
    hp_temp INTEGER,
    armour_class INTEGER NOT NULL,
    initiative INTEGER,
    initiative_modifier INTEGER,
    sort_order INTEGER,
    is_current_turn BOOLEAN,
    is_alive BOOLEAN,
    is_visible_to_players BOOLEAN,
    active_conditions JSONB,
    concentration_spell VARCHAR(200),
    spell_slots_current JSONB,
    death_save_successes INTEGER,
    death_save_failures INTEGER,
    notes TEXT,
    controlled_by_user_id UUID,
    version BIGINT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    encounter_id UUID NOT NULL REFERENCES encounters(id),
    character_id UUID REFERENCES player_characters(id),
    monster_id UUID REFERENCES monsters(id),
    CONSTRAINT encounter_participants_participant_type_check CHECK (participant_type IN ('PLAYER', 'MONSTER', 'COMPANION'))
);

CREATE TABLE combat_logs (
    id UUID PRIMARY KEY,
    action_type VARCHAR(30) NOT NULL,
    actor_id UUID,
    actor_name VARCHAR(200),
    target_id UUID,
    target_name VARCHAR(200),
    description TEXT NOT NULL,
    roll_value INTEGER,
    roll_total INTEGER,
    damage_dealt INTEGER,
    healing_done INTEGER,
    round_number INTEGER,
    turn_participant_name VARCHAR(200),
    created_at TIMESTAMPTZ,
    encounter_id UUID NOT NULL REFERENCES encounters(id),
    CONSTRAINT combat_logs_action_type_check CHECK (action_type IN (
        'ATTACK', 'DAMAGE', 'HEAL',
        'CONDITION_ADD', 'CONDITION_REMOVE',
        'DEATH_SAVE', 'CONCENTRATION_CHECK', 'CONCENTRATION_LOST',
        'TURN_ADVANCE', 'TURN_BACK',
        'STABILIZE', 'KILL', 'REVIVE',
        'SPELL_SLOT_USE', 'SPELL_SLOT_RESTORE'
    ))
);

CREATE INDEX idx_monsters_name_trgm ON monsters USING GIN (lower(name) gin_trgm_ops);
