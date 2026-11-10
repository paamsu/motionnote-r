package ai.teraq.roadguardian;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 후보만 만든다. OCR 글자는 사용자가 확인해야 주차 기록이 된다
 */
public final class ParkingSigns {
    /**
     * 사진을 더해도 사용자가 고친 값(일부러 비운 값 포함)을 덮어쓰지 않는다
     */
    public static String nextInput(String current, boolean edited, boolean append, Set<String> candidates) {
        if (append && edited) return current;
        return candidates.size() == 1 ? candidates.iterator().next() : "";
    }

    public enum Comparison {UNKNOWN_FLOOR, DIFFERENT_FLOOR, NEED_LANDMARK, DIFFERENT_LANDMARK, SAME_SIGNS}

    public static Comparison compare(String savedFloor, String savedLandmark, String currentFloor, String currentLandmark) {
        String before = normalizedFloor(savedFloor), now = normalizedFloor(currentFloor);
        if (before.isEmpty() || now.isEmpty()) return Comparison.UNKNOWN_FLOOR;
        if (!before.equals(now)) return Comparison.DIFFERENT_FLOOR;
        String oldSign = savedLandmark.trim(), newSign = currentLandmark.trim();
        if (oldSign.isEmpty() || newSign.isEmpty()) return Comparison.NEED_LANDMARK;
        // 구역 글자, 문장 부호, 숫자는 지우지 않는다. 다른 장소를 가리킬 수 있다
        return oldSign.equals(newSign) ? Comparison.SAME_SIGNS : Comparison.DIFFERENT_LANDMARK;
    }

    public static Set<String> floors(String text) {
        Set<String> result = new LinkedHashSet<>();
        Matcher below = Pattern.compile("(?<![A-Z0-9])B\\s*([1-9][0-9]?)(?:\\s*F)?(?![A-Z0-9])", Pattern.CASE_INSENSITIVE).matcher(text);
        while (below.find()) result.add("B" + below.group(1));
        Matcher korean = Pattern.compile("지하\\s*([1-9][0-9]?)\\s*층").matcher(text);
        while (korean.find()) result.add("B" + korean.group(1));
        Matcher above = Pattern.compile("(?<![A-Z0-9])([1-9][0-9]?)\\s*F(?![A-Z0-9])", Pattern.CASE_INSENSITIVE).matcher(text);
        while (above.find()) {
            if (!text.substring(0, above.start()).toUpperCase(java.util.Locale.ROOT).matches("(?s).*B\\s*$"))
                result.add(above.group(1) + "F");
        }
        return result;
    }

    public static String normalizedFloor(String value) {
        String clean = value.trim().toUpperCase(java.util.Locale.ROOT).replace(" ", "");
        return clean.matches("B[1-9][0-9]?|[1-9][0-9]?F") ? clean : "";
    }

    public static Set<String> landmarks(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            String clean = line.trim();
            // 짧은 숫자 하나는 기둥 번호 후보로만 본다
            if (clean.matches("[0-9]{1,3}")) result.add(clean + "번 기둥");
            Matcher named = Pattern.compile("(?<![0-9])([0-9]{1,3})\\s*번?\\s*기둥").matcher(clean);
            while (named.find()) result.add(named.group(1) + "번 기둥");
        }
        return result;
    }
}
