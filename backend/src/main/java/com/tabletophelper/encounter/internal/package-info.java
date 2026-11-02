/**
 * Live combat's one resolve/apply path (D160). The services take a command, check access and lock the
 * encounter; the adapters read spell and monster templates into Effects; rules.combat.Resolver resolves them;
 * and {@link com.tabletophelper.encounter.internal.EffectApplier} makes every change to hit points, life
 * state, conditions and concentration, and writes the combat log entries that describe them.
 */
package com.tabletophelper.encounter.internal;
