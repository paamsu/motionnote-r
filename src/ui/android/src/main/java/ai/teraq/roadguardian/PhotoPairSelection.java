package ai.teraq.roadguardian;

import java.util.*;

/**
 * 서로 다른 첨부 ID 두 개. 촬영 순서나 같은 장면인지는 판단하지 않는다
 */
public final class PhotoPairSelection {
    private final List<String> ids;
    private final Map<String, String> contents;
    private String first, second;

    public PhotoPairSelection(List<String> values) {
        this(values, null);
    }

    public PhotoPairSelection(List<String> values, Map<String, String> hashes) {
        if (values == null || values.size() < 2 || values.size() > 4 || new HashSet<>(values).size() != values.size())
            throw new IllegalArgumentException("Two to four distinct photos required");
        for (String id : values)
            if (id == null || !UUID.fromString(id).toString().equals(id))
                throw new IllegalArgumentException("Photo ID required");
        ids = Collections.unmodifiableList(new ArrayList<>(values));
        Map<String, String> copy = new HashMap<>();
        for (String id : ids) {
            String hash = hashes == null ? id : hashes.get(id);
            if (hash == null || (hashes != null && !hash.matches("[0-9a-f]{64}")))
                throw new IllegalArgumentException("Stored photo SHA-256 required");
            copy.put(id, hash);
        }
        contents = Collections.unmodifiableMap(copy);
        first = ids.get(0);
        for (int i = ids.size() - 1; i > 0; i--)
            if (!contents.get(first).equals(contents.get(ids.get(i)))) {
                second = ids.get(i);
                break;
            }
        if (second == null)
            throw new IllegalArgumentException("Two different photo contents required");
    }

    public String get(int slot) {
        if (slot != 0 && slot != 1) throw new IllegalArgumentException("Slot required");
        return slot == 0 ? first : second;
    }

    public boolean select(int slot, String id) {
        get(slot);
        if (!ids.contains(id)) throw new IllegalArgumentException("Unknown photo");
        if (contents.get(id).equals(contents.get(get(1 - slot)))) return false;
        if (slot == 0) first = id;
        else second = id;
        return true;
    }

    public void swap() {
        String previous = first;
        first = second;
        second = previous;
    }
}
