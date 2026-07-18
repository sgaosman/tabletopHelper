package com.questkeeper.seeder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FiveEToolsMarkupParser {

    private static final Pattern TAG_PATTERN = Pattern.compile("\\{@(\\w+)\\s+([^}]*)}");

    private FiveEToolsMarkupParser() {}

    public static String parse(String text) {
        if (text == null) return null;

        String result = text;
        Matcher matcher = TAG_PATTERN.matcher(result);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String tag = matcher.group(1);
            String content = matcher.group(2);
            String replacement = convertTag(tag, content);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String convertTag(String tag, String content) {
        return switch (tag) {
            case "atk" -> formatAttackType(content);
            case "hit" -> "+" + content;
            case "h" -> "";
            case "damage", "dice" -> content.contains("|") ? content.substring(0, content.indexOf('|')) : content;
            case "dc" -> "DC " + content;
            case "spell" -> extractDisplayName(content);
            case "creature" -> extractDisplayName(content);
            case "condition", "status" -> extractDisplayName(content);
            case "skill" -> extractDisplayName(content);
            case "item" -> extractDisplayName(content);
            case "scaledamage" -> {
                String[] parts = content.split("\\|");
                yield parts.length >= 3 ? parts[2] : content;
            }
            case "recharge" -> "(Recharge " + content + "-6)";
            case "book" -> {
                String[] parts = content.split("\\|");
                yield parts[0];
            }
            case "filter" -> extractDisplayName(content);
            case "sense" -> extractDisplayName(content);
            case "note" -> content;
            case "b" -> content;
            case "i" -> content;
            case "action" -> extractDisplayName(content);
            case "chance" -> content + "%";
            case "quickref" -> {
                String[] parts = content.split("\\|");
                yield parts.length >= 5 ? parts[4] : parts[0];
            }
            default -> content.contains("|") ? content.substring(0, content.indexOf('|')) : content;
        };
    }

    private static String formatAttackType(String content) {
        return switch (content) {
            case "mw" -> "Melee Weapon Attack:";
            case "rw" -> "Ranged Weapon Attack:";
            case "mw,rw" -> "Melee or Ranged Weapon Attack:";
            case "ms" -> "Melee Spell Attack:";
            case "rs" -> "Ranged Spell Attack:";
            case "ms,rs" -> "Melee or Ranged Spell Attack:";
            default -> "Attack:";
        };
    }

    private static String extractDisplayName(String content) {
        if (content.contains("||")) {
            String afterPipes = content.substring(content.lastIndexOf("||") + 2);
            return afterPipes.isEmpty() ? content.substring(0, content.indexOf("||")) : afterPipes;
        }
        return content.contains("|") ? content.substring(0, content.indexOf('|')) : content;
    }
}
